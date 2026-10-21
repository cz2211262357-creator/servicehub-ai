from sqlalchemy import select

from servicehub.ai.models import AIRun
from servicehub.conversations.schemas import AIReplyDTO, ConversationDTO
from servicehub.jobs.models import Job


async def conversation_snapshot(session, conversation):
    dto = ConversationDTO.model_validate(conversation)
    if conversation.status != "AI_ACTIVE":
        return dto
    row = (
        await session.execute(
            select(AIRun, Job)
            .join(Job, (Job.tenant_id == AIRun.tenant_id) & (Job.id == AIRun.job_id))
            .where(
                AIRun.tenant_id == conversation.tenant_id,
                AIRun.conversation_id == conversation.id,
                AIRun.generation_epoch == conversation.generation_epoch,
            )
        )
    ).one_or_none()
    if row:
        run, job = row
        state = "cancelled" if run.state == "cancelled" else job.state
        dto.ai_reply = AIReplyDTO(
            run_id=run.id,
            state=state,
            error_code=job.error_code,
            will_retry=job.state == "retrying",
            generation_epoch=run.generation_epoch,
            attempts=job.attempts,
        )
    return dto
