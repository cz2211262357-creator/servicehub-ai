"""Import all domain mappings before Alembic or schema inspection."""

# Imports register tables with SQLAlchemy; they are intentional side effects.
# ruff: noqa: F401
from servicehub.after_sales import models as after_sales
from servicehub.ai import models as ai
from servicehub.auth import models as auth
from servicehub.conversations import models as conversations
from servicehub.db.base import Base
from servicehub.jobs import models as jobs
from servicehub.knowledge import models as knowledge
from servicehub.operations import models as operations
from servicehub.orders import models as orders

metadata = Base.metadata
