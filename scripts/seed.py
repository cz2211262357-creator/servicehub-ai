"""Idempotent fictional development data. Never resets existing accounts or business records."""

import asyncio
import io
import json
import os
from decimal import Decimal

from servicehub.auth.models import Tenant, User
from servicehub.auth.schemas import Principal
from servicehub.auth.security import hash_password
from servicehub.core.config import load_settings
from servicehub.db.engine import create_database
from servicehub.db.metadata import metadata  # noqa: F401 -- register all tenant foreign keys
from servicehub.knowledge.models import KnowledgeDocument
from servicehub.knowledge.service import DocumentService
from servicehub.orders.models import Order, OrderItem
from sqlalchemy import select, text
from starlette.datastructures import UploadFile

POLICY_TITLE = "商城售后服务政策（开发示例）"
POLICY = """# 商城售后服务政策
此文档是本地开发用的虚构商家政策，实际业务规则应由商家管理员上传。
## 七天退货
签收七天内，未使用且包装、配件完整的商品可以申请退货。定制、已激活等特殊商品需人工核实。
## 质量问题
发现无法开机、破损或功能故障，请保留照片、视频和包装，提供订单号，并选择退货、换货或维修申请。
系统只准备申请草稿，客户点击确认后生成待审核工单。客服审核后告知寄回要求。
## 物流
订单发货后可查询承运商、物流单号和最新记录。物流延迟或签收异常请转人工核查。
## 退款
本平台记录售后申请和审核流程，不直接执行支付退款。实际退款需由商家在支付渠道处理，
到账时间由商家和支付渠道核实，客服不能在未确认时承诺到账日期。
"""


async def seed_development(
    sessions, settings, password, *, tenant_names=("星舟数码商城", "青禾生活商城")
):
    if settings.environment != "development":
        raise RuntimeError("Seed is allowed only in development")
    if len(password) < 12:
        raise RuntimeError("Development account password must be at least 12 characters")
    manifest = {"tenants": []}
    engine = sessions.kw["bind"]
    # A server connection retains this advisory lock across the short business transactions.
    async with engine.connect() as lock:
        key = "servicehub:seed:" + settings.database_url.rsplit("/", 1)[-1]
        if await lock.scalar(text("SELECT GET_LOCK(:key, 10)"), {"key": key}) != 1:
            raise RuntimeError("Another seed process is running")
        await lock.commit()
        try:
            hashed = await asyncio.to_thread(hash_password, password)
            for name in tenant_names:
                async with sessions() as session, session.begin():
                    tenant = await session.scalar(select(Tenant).where(Tenant.name == name))
                    if not tenant:
                        tenant = Tenant(name=name)
                        session.add(tenant)
                        await session.flush()
                    users = {}
                    for local, role, display in [
                        ("admin", "admin", "商城管理员"),
                        ("agent", "agent", "客服 · 林晓"),
                        ("agent2", "agent", "客服 · 陈然"),
                        ("customer", "customer", "客户 · 周宁"),
                        ("customer2", "customer", "客户 · 许嘉"),
                    ]:
                        email = local + "@example.com"
                        user = await session.scalar(
                            select(User).where(User.tenant_id == tenant.id, User.email == email)
                        )
                        if not user:
                            user = User(
                                tenant_id=tenant.id,
                                email=email,
                                display_name=display,
                                role=role,
                                password_hash=hashed,
                            )
                            session.add(user)
                            await session.flush()
                        users[local] = user
                    order_numbers = []
                    for number, customer, product, price, status in [
                        ("SH20261021001", "customer", "无线降噪耳机", "599.00", "DELIVERED"),
                        ("SH20261021002", "customer", "机械键盘", "899.00", "SHIPPED"),
                        ("SH20261021003", "customer2", "智能清洁机器人", "1299.00", "DELIVERED"),
                    ]:
                        row = await session.scalar(
                            select(Order).where(
                                Order.tenant_id == tenant.id, Order.order_number == number
                            )
                        )
                        if not row:
                            row = Order(
                                tenant_id=tenant.id,
                                customer_id=users[customer].id,
                                order_number=number,
                                total_amount=Decimal(price),
                                status=status,
                                carrier="本地示例承运商",
                                tracking_number="LOCAL-" + number,
                                delivery_summary="已签收，订单仅供本地开发"
                                if status == "DELIVERED"
                                else "运输中，订单仅供本地开发",
                            )
                            session.add(row)
                            await session.flush()
                            session.add(
                                OrderItem(
                                    tenant_id=tenant.id,
                                    order_id=row.id,
                                    product_name=product,
                                    sku=number[-3:],
                                    quantity=1,
                                    unit_price=Decimal(price),
                                )
                            )
                        order_numbers.append(number)
                    document = await session.scalar(
                        select(KnowledgeDocument.id).where(
                            KnowledgeDocument.tenant_id == tenant.id,
                            KnowledgeDocument.title == POLICY_TITLE,
                            KnowledgeDocument.deleted.is_(False),
                        )
                    )
                    principal = Principal(users["admin"].id, tenant.id, "admin")
                if not document:
                    await DocumentService(sessions, settings).upload(
                        principal,
                        UploadFile(
                            io.BytesIO((f"# {name}\n\n" + POLICY).encode()),
                            filename="after-sales.md",
                        ),
                        POLICY_TITLE,
                        request_id="development-seed",
                    )
                manifest["tenants"].append(
                    {
                        "id": tenant.id,
                        "name": name,
                        "accounts": [
                            {"email": user.email, "role": user.role} for user in users.values()
                        ],
                        "orders": order_numbers,
                    }
                )
        finally:
            await lock.execute(text("SELECT RELEASE_LOCK(:key)"), {"key": key})
            await lock.commit()
    return manifest


async def main():
    settings = load_settings()
    if settings.environment != "development":
        raise RuntimeError("Seed is allowed only in development")
    engine, sessions = create_database(settings)
    try:
        result = await seed_development(
            sessions, settings, os.environ.get("SERVICEHUB_SEED_PASSWORD", "ServiceHub-2026!")
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print(
            "Existing accounts are preserved. New account password: SERVICEHUB_SEED_PASSWORD or documented development default."
        )
    finally:
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
