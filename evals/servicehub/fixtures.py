"""Real MySQL/Redis fixtures with owned resources; no global flush operations."""

import shutil
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
from pwdlib import PasswordHash
from sqlalchemy import delete, select

from evals.servicehub.schema import DATA_ROOT
from servicehub.after_sales.eligibility import AfterSaleFacts, evaluate_eligibility
from servicehub.after_sales.models import AfterSaleTicket, TicketDraft
from servicehub.ai.models import AIRun
from servicehub.auth.models import Tenant, User
from servicehub.auth.security import digest
from servicehub.conversations.models import Conversation
from servicehub.db.metadata import metadata
from servicehub.jobs.models import Job
from servicehub.jobs.service import JobService, database_now
from servicehub.knowledge.indexer import KnowledgeIndexer
from servicehub.knowledge.models import KnowledgeChunk
from servicehub.main import create_app
from servicehub.orders.models import Order

PASSWORD = "Synthetic-evaluation-2026!"


def validate_test_resources(settings):
    database = urlsplit(settings.database_url)
    redis = urlsplit(settings.redis_url)
    if database.path != "/servicehub_test" or database.hostname not in {"127.0.0.1", "localhost"}:
        raise ValueError("Evaluation requires the dedicated local servicehub_test database")
    if redis.hostname not in {"127.0.0.1", "localhost"} or redis.port != 6380:
        raise ValueError("Evaluation requires dedicated local Redis on 6380")


def trusted_facts(order, runtime_now):
    """Translate frozen relative dates onto the real server UTC day, preserving unknowns.

    Warranty zero means the calibration day's end. No production clock is patched.
    Extra synthetic annotations (e.g. safety_issue) are not valid trusted schema fields;
    their explicit requires_manual_review flag remains authoritative.
    """
    data = dict(order.get("facts") or {})
    if order.get("category") is not None:
        data["category"] = order["category"]
    if order.get("delivered_days_ago") is not None:
        signed = runtime_now - timedelta(days=order["delivered_days_ago"])
        data["signed_at"] = signed.isoformat()
    if "warranty_remaining_days" in data:
        remaining = data.pop("warranty_remaining_days")
        if remaining is not None:
            data["warranty_until"] = (
                runtime_now.replace(hour=23, minute=59, second=59, microsecond=999999)
                + timedelta(days=remaining)
            ).isoformat()
    return {key: value for key, value in data.items() if key in AfterSaleFacts.model_fields}


class FixtureEnvironment:
    def __init__(self, app, settings, manifest):
        self.app, self.settings, self.manifest = app, settings, manifest
        self.sessions, self.redis = app.state.sessions, app.state.redis
        self.run_tag = uuid4().hex
        self.tenants, self.clients, self.redis_keys, self.chunk_ids = [], [], set(), []
        self.password_hash = PasswordHash.recommended().hash(PASSWORD)
        self.policy_by_document = {}
        self.calibration = {}

    async def login(self, user):
        client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=self.app, raise_app_exceptions=False),
            base_url="http://testserver",
            headers={"origin": "http://localhost:5173"},
        )
        self.clients.append(client)
        response = await client.post(
            "/api/v1/auth/login",
            json={"tenant_id": user.tenant_id, "email": user.email, "password": PASSWORD},
        )
        if response.status_code != 200:
            raise RuntimeError(f"fixture_login_http_{response.status_code}")
        client.headers["x-csrf-token"] = response.json()["csrf_token"]
        token = client.cookies.get(self.settings.session_cookie_name)
        self.redis_keys.add("servicehub:session:" + digest(token))
        identity = f"{user.tenant_id}:{user.email}:127.0.0.1"
        self.redis_keys.add("servicehub:login:" + digest(identity))
        return client

    async def initialize(self):
        async with self.sessions() as session, session.begin():
            now = await database_now(session)
            self.runtime_now = now
            fixed = datetime.fromisoformat(self.manifest["as_of"].replace("Z", "+00:00"))
            self.calibration = {
                "fixed_as_of": fixed.isoformat(),
                "server_utc_at_start": now.isoformat(),
                "offset_seconds": (now - fixed).total_seconds(),
                "method": "relative ages mapped to real server day; warranty zero ends that UTC day; no clock patch",
            }
            self.tenants = [Tenant(name=f"eval-{self.run_tag}-{i}") for i in range(2)]
            session.add_all(self.tenants)
            await session.flush()
            self.admin = User(
                tenant_id=self.tenants[0].id,
                email="eval-admin@example.com",
                display_name="Synthetic admin",
                password_hash=self.password_hash,
                role="admin",
            )
            session.add(self.admin)
            await session.flush()
        admin = await self.login(self.admin)
        for document in self.manifest["documents"]:
            raw = (DATA_ROOT / document["path"]).read_bytes()
            response = await admin.post(
                "/api/v1/knowledge/documents",
                files={"file": (document["policy_id"] + ".txt", raw, "text/plain")},
            )
            if response.status_code not in {200, 201}:
                raise RuntimeError(f"corpus_upload_http_{response.status_code}")
            payload = response.json()
            self.policy_by_document[payload["id"]] = document["policy_id"]
            version = payload["latest_version"]
            lease = await JobService(self.sessions).acquire(
                version["job_id"], "eval-index-" + self.run_tag, 300
            )
            if lease is None:
                raise RuntimeError("corpus_index_lease_unavailable")
            await KnowledgeIndexer(self.sessions, self.redis, self.settings).build_version(
                lease, version["id"]
            )
        async with self.sessions() as session:
            self.chunk_ids = list(
                await session.scalars(
                    select(KnowledgeChunk.id).where(KnowledgeChunk.tenant_id == self.tenants[0].id)
                )
            )

    async def prepare_case(self, case):
        async with self.sessions() as session, session.begin():
            case_now = await database_now(session)
            actors = {}
            for owner in ("self", "other", "other_tenant"):
                actor = User(
                    tenant_id=self.tenants[owner == "other_tenant"].id,
                    email=f"{case['id'].lower()}-{owner}-{self.run_tag[:8]}@example.com",
                    display_name="Synthetic " + owner,
                    password_hash=self.password_hash,
                    role="customer",
                )
                session.add(actor)
                actors[owner] = actor
            await session.flush()
            orders = []
            for item in case["initial_state"].get("orders", []):
                owner = actors[item["owner"]]
                row = Order(
                    tenant_id=owner.tenant_id,
                    customer_id=owner.id,
                    order_number=item["order_number"],
                    status=item["status"],
                    total_amount=Decimal(item["amount"]),
                    tracking_number=item.get("tracking_number"),
                    carrier=item.get("carrier"),
                    delivery_summary=item.get("delivery_summary"),
                    after_sale_facts=trusted_facts(item, case_now),
                )
                session.add(row)
                orders.append(row)
            await session.flush()
        client = await self.login(actors["self"])
        response = await client.post("/api/v1/conversations", json={})
        if response.status_code not in {200, 201}:
            raise RuntimeError(f"conversation_create_http_{response.status_code}")
        return {
            "client": client,
            "actor": actors["self"],
            "orders": orders,
            "conversation_id": response.json()["id"],
            "runtime_now": case_now,
        }

    async def before_turn(self, case, context, index):
        updates = case["initial_state"].get("turn_state_updates", [])
        async with self.sessions() as session, session.begin():
            for change in updates:
                if change["before_turn"] != index:
                    continue
                row = next(
                    order
                    for order in context["orders"]
                    if order.order_number == change["order_number"]
                )
                order = await session.get(Order, row.id)
                if "status" in change:
                    order.status = change["status"]
                item = {"facts": dict(order.after_sale_facts or {})}
                item["facts"].update(change.get("facts_patch", {}))
                if "delivered_days_ago" in change:
                    item["delivered_days_ago"] = change["delivered_days_ago"]
                order.after_sale_facts = trusted_facts(item, context["runtime_now"])
            for change in case["initial_state"].get("actor_state_updates", []):
                if change["before_turn"] == index:
                    actor = await session.get(User, context["actor"].id)
                    actor.is_active = change["active"]

    async def snapshot(self, context):
        actor = context["actor"]
        ids = [row.id for row in context["orders"]]
        async with self.sessions() as session:
            now = await database_now(session)
            orders = (
                list(await session.scalars(select(Order).where(Order.id.in_(ids)))) if ids else []
            )
            state = {
                row.order_number: {
                    "status": row.status,
                    "amount": str(row.total_amount),
                    "facts": row.after_sale_facts,
                }
                for row in orders
            }
            drafts = list(
                await session.scalars(
                    select(TicketDraft)
                    .where(
                        TicketDraft.tenant_id == actor.tenant_id,
                        TicketDraft.customer_id == actor.id,
                    )
                    .order_by(TicketDraft.id)
                )
            )
            tickets = list(
                await session.scalars(
                    select(AfterSaleTicket).where(
                        AfterSaleTicket.tenant_id == actor.tenant_id,
                        AfterSaleTicket.customer_id == actor.id,
                    )
                )
            )
            conversation = await session.get(Conversation, context["conversation_id"])
            current_actor = await session.get(User, actor.id)
            valid = []
            for draft in drafts:
                run = await session.get(AIRun, draft.run_id) if draft.run_id else None
                job = await session.get(Job, run.job_id) if run else None
                order = next((row for row in orders if row.id == draft.order_id), None)
                compatible = bool(
                    run
                    and job
                    and run.tenant_id == actor.tenant_id
                    and job.tenant_id == actor.tenant_id
                    and run.conversation_id == conversation.id
                    and job.kind == "ai_reply"
                    and job.payload.get("ai_run_id") == run.id
                    and job.payload.get("conversation_id") == conversation.id
                    and run.state == "succeeded"
                    and job.state == "succeeded"
                    and job.execution_version == draft.execution_version
                    and draft.conversation_id == conversation.id
                    and conversation.status == "AI_ACTIVE"
                )
                eligible = (
                    order
                    and evaluate_eligibility(
                        order.status, order.after_sale_facts, draft.kind, draft.reason_code, now
                    ).eligible
                )
                if (
                    current_actor.is_active
                    and current_actor.role == "customer"
                    and order is not None
                    and order.customer_id == actor.id
                    and not draft.consumed
                    and draft.expires_at > now
                    and compatible
                    and eligible
                ):
                    valid.append(draft)
            order_numbers = {row.id: row.order_number for row in orders}
            return {
                "orders": state,
                "draft_count": len(valid),
                "draft_total": len(drafts),
                "ticket_count": len(tickets),
                "latest_draft_order": order_numbers.get(valid[-1].order_id) if valid else None,
                "drafts": [
                    {
                        "id": row.id,
                        "order_number": order_numbers.get(row.order_id),
                        "kind": row.kind,
                        "run_id": row.run_id,
                        "execution_version": row.execution_version,
                        "consumed": row.consumed,
                        "expires_at": row.expires_at.isoformat(),
                        "valid": row in valid,
                    }
                    for row in drafts
                ],
            }

    async def cleanup(self):
        for client in self.clients:
            await client.aclose()
        tenant_ids = [tenant.id for tenant in self.tenants]
        if tenant_ids:
            # Discover owned index hashes before deleting SQL; never delete another tenant's keys.
            async for key in self.redis.scan_iter(match="servicehub:*"):
                kind = await self.redis.type(key)
                if kind in {"hash", b"hash"}:
                    tenant = await self.redis.hget(key, "tenant")
                    if (
                        tenant is not None
                        and str(tenant).strip("b'\"").isdigit()
                        and int(tenant) in tenant_ids
                    ):
                        self.redis_keys.add(key)
            for tenant_id in tenant_ids:
                async for key in self.redis.scan_iter(match=f"servicehub:rate:{tenant_id}:*"):
                    self.redis_keys.add(key)
            if self.redis_keys:
                await self.redis.delete(*self.redis_keys)
            async with self.sessions() as session, session.begin():
                for table in reversed(metadata.sorted_tables):
                    if "tenant_id" in table.c:
                        await session.execute(
                            delete(table).where(table.c.tenant_id.in_(tenant_ids))
                        )
                await session.execute(delete(Tenant).where(Tenant.id.in_(tenant_ids)))
        target = self.settings.upload_path
        base = (self.settings.project_root / ".cache" / "evaluation").resolve()
        if target.is_relative_to(base) and target != base and target.exists():
            shutil.rmtree(target)


@asynccontextmanager
async def evaluation_environment(settings, manifest):
    validate_test_resources(settings)
    settings = settings.model_copy(
        update={"upload_dir": Path(".cache/evaluation") / uuid4().hex, "rate_limit_requests": 10000}
    )
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        environment = FixtureEnvironment(app, settings, manifest)
        try:
            await environment.initialize()
            yield environment
        finally:
            await environment.cleanup()
