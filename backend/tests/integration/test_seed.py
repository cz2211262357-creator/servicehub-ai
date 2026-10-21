import pytest
from servicehub.auth.models import Tenant, User
from servicehub.jobs.models import Job
from servicehub.knowledge.models import KnowledgeDocument
from servicehub.orders.models import Order
from sqlalchemy import func, select

pytestmark = pytest.mark.integration


async def test_development_seed_is_idempotent_and_preserves_existing_members(env):
    from scripts.seed import seed_development

    settings = env.settings.model_copy(update={"environment": "development"})
    names = tuple(t.name for t in env.tenants)
    first = await seed_development(env.sessions, settings, env.password, tenant_names=names)

    async def counts():
        async with env.sessions() as session:
            return tuple(
                [
                    await session.scalar(
                        select(func.count())
                        .select_from(model)
                        .where(model.tenant_id.in_([t.id for t in env.tenants]))
                    )
                    for model in (User, Order, KnowledgeDocument, Job)
                ]
            )

    before = await counts()
    second = await seed_development(env.sessions, settings, env.password, tenant_names=names)
    assert first == second
    assert await counts() == before
    assert before[1:] == (6, 2, 2)
    async with env.sessions() as session:
        original = await session.get(User, env.users["customer"].id)
        assert original.password_hash == env.users["customer"].password_hash
        assert (
            await session.scalar(
                select(func.count()).select_from(Tenant).where(Tenant.name.in_(names))
            )
            == 2
        )


async def test_seed_refuses_non_development_environment_before_writing(env):
    from scripts.seed import seed_development

    for environment in ("test", "production"):
        with pytest.raises(RuntimeError, match="development"):
            await seed_development(
                env.sessions,
                env.settings.model_copy(update={"environment": environment}),
                env.password,
            )
