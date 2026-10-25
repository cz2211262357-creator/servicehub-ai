package cn.servicehub.ai;
import cn.servicehub.common.BusinessException;
import cn.servicehub.jobs.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistedAgentBudgetIT {
    final AgentFixture f=new AgentFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    @Test void reservationCountersSurviveRecoveryAndNeverExtendWholeDeadline(){
        var original=f.lease;
        assertThat(Duration.between(original.startedAt(),original.deadlineAt())).isEqualTo(Duration.ofSeconds(120));
        f.reserve("model");f.reserve("model");f.reserve("tool");
        assertThat(f.app.db.queryForObject("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",Integer.class,original.jobId())).isEqualTo(2);
        f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",original.jobId());
        f.app.context.getBean(JobRecoveryScheduler.class).recoverOnce();
        f.app.db.update("UPDATE jobs SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",original.jobId());f.claim(original.jobId());
        assertThat(f.lease.deadlineAt()).isEqualTo(original.deadlineAt());assertThat(f.lease.startedAt()).isEqualTo(original.startedAt());
        assertThat(f.app.db.queryForObject("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",Integer.class,original.jobId())).isEqualTo(2);
        assertThat(f.app.db.queryForObject("SELECT tool_executions_reserved FROM ai_runs WHERE job_id=?",Integer.class,original.jobId())).isEqualTo(1);
        assertThatThrownBy(()->AgentFixture.call(f.budget(),"reserve",new Class<?>[]{JobLease.class,String.class},original,"model")).isInstanceOf(ExecutionCancelledException.class);
    }
    @Test void exactPersistentCapsRejectSeventhModelAndFifthTool(){
        for(int i=0;i<6;i++)f.reserve("model");for(int i=0;i<4;i++)f.reserve("tool");
        assertThatThrownBy(()->f.reserve("model")).isInstanceOf(BusinessException.class);assertThatThrownBy(()->f.reserve("tool")).isInstanceOf(BusinessException.class);
        assertThat(f.app.db.queryForObject("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",Integer.class,f.lease.jobId())).isEqualTo(6);
        assertThat(f.app.db.queryForObject("SELECT tool_executions_reserved FROM ai_runs WHERE job_id=?",Integer.class,f.lease.jobId())).isEqualTo(4);
    }
    @Test void expiredDeadlineRejectsReservationWithoutIncrement(){
        f.app.db.update("UPDATE jobs SET deadline_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",f.lease.jobId());
        assertThatThrownBy(()->f.reserve("model")).isInstanceOf(ExecutionCancelledException.class);
        assertThat(f.app.db.queryForObject("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",Integer.class,f.lease.jobId())).isZero();
    }
}
