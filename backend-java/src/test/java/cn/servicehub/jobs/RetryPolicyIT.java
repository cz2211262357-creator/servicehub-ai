package cn.servicehub.jobs;

import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryPolicyIT {
    final JobRuntimeFixture f=new JobRuntimeFixture();
    @BeforeAll void start() throws Exception {f.start();}
    @BeforeEach void reset(){f.reset();}
    @AfterAll void stop(){f.close();}
    Object claim(long id,ExecutionClass type){String owner="retry-"+UUID.randomUUID();f.permit(type,owner);return f.claimed(id,owner);}
    @Test void threeTotalAttemptsUseFiveAndThirtySecondBackoffWithoutResettingDeadline() {
        long id=f.background();Object lease=claim(id,ExecutionClass.BACKGROUND);
        var started=f.instant("started_at",id);var deadline=f.instant("deadline_at",id);
        for(int attempt=1;attempt<=3;attempt++){
            var before=f.now();f.fail(lease,true);var after=f.now();
            assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,id)).isEqualTo(attempt);
            assertThat(f.instant("started_at",id)).isEqualTo(started);assertThat(f.instant("deadline_at",id)).isEqualTo(deadline);
            if(attempt<3){
                long seconds=attempt==1?5:30;
                assertThat(f.state(id)).isEqualTo("RETRY_WAIT");assertThat(f.instant("available_at",id)).isBetween(before.plusSeconds(seconds),after.plusSeconds(seconds));
                f.ready(id);lease=claim(id,ExecutionClass.BACKGROUND);
            }else assertThat(f.state(id)).isEqualTo("FAILED");
        }
        assertThat(f.claim(id,"fourth-attempt")).isEmpty();
        assertThat(f.app.db.queryForObject("SELECT lifetime_attempts FROM jobs WHERE id=?",Integer.class,id)).isEqualTo(3);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE job_id=?",Integer.class,id)).isEqualTo(3);
    }
    @Test void permanentFailureDoesNotCreateRetry() {
        long id=f.background();Object lease=claim(id,ExecutionClass.BACKGROUND);f.fail(lease,false);
        assertThat(f.state(id)).isEqualTo("FAILED");assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,id)).isEqualTo(1);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE job_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void cancelledJobsNeverReclaimOrConsumeAnAttempt() {
        long id=f.background();f.app.db.update("UPDATE jobs SET state='CANCELLED' WHERE id=?",id);
        assertThat(f.claim(id,"cancelled-delivery")).isEmpty();f.recover();assertThat(f.state(id)).isEqualTo("CANCELLED");
        assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,id)).isZero();
    }
    @Test void expiredQueueHasNoExecutionAndActiveDeadlineIsNeverReset() throws Exception {
        long queued=f.foreground();f.app.db.update("UPDATE jobs SET accepted_at=UTC_TIMESTAMP(6)-INTERVAL 61 SECOND WHERE id=?",queued);
        String owner="queue-"+UUID.randomUUID();f.permit(ExecutionClass.INTERACTIVE,owner);assertThat(f.claim(queued,owner)).isEmpty();
        assertThat(f.state(queued)).isEqualTo("TIMED_OUT");assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,queued)).isZero();
        long conversation=f.app.db.queryForObject("SELECT conversation_id FROM jobs WHERE id=?",Long.class,queued);
        var response=f.app.request("GET","/api/v1/conversations/"+conversation,null,f.app.login(2003));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(f.app.body(response).path("ai_reply").path("state").asString()).isEqualTo("failed");
        assertThat(f.app.body(response).path("ai_reply").path("error_code").asString()).isEqualTo("queue_timeout");
        long running=f.foreground();Object lease=claim(running,ExecutionClass.INTERACTIVE);
        f.app.db.update("UPDATE jobs SET deadline_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",running);var fixed=f.instant("deadline_at",running);
        f.recover();assertThat(f.state(running)).isEqualTo("TIMED_OUT");assertThat(f.instant("deadline_at",running)).isEqualTo(fixed);
    }
}
