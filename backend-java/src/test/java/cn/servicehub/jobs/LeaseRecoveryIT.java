package cn.servicehub.jobs;

import cn.servicehub.ai.ExecutionCancelledException;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static cn.servicehub.jobs.JobRuntimeFixture.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaseRecoveryIT {
    final JobRuntimeFixture f=new JobRuntimeFixture();
    @BeforeAll void start() throws Exception {f.start();}
    @BeforeEach void reset(){f.reset();}
    @AfterAll void stop(){f.close();}
    String owner(){return "lease-"+UUID.randomUUID();}
    @Test void expiredLeaseCannotWriteEvenBeforeAnotherWorkerClaims() {
        long id=f.background();String owner=owner();f.permit(ExecutionClass.BACKGROUND,owner);Object lease=f.claimed(id,owner);
        f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
        assertThat(f.renew(lease)).isFalse();
        assertThatThrownBy(()->f.requireValid(lease)).isInstanceOf(ExecutionCancelledException.class);
        assertThatThrownBy(()->f.finish(lease,"SUCCEEDED")).isInstanceOf(ExecutionCancelledException.class);
        assertThat(f.state(id)).isEqualTo("RUNNING");
        assertThat(f.app.db.queryForObject("SELECT result IS NULL FROM jobs WHERE id=?",Boolean.class,id)).isTrue();
    }
    @Test void expiredOwnerCannotCompleteRecoveredJob() {
        long id=f.background();String a=owner();f.permit(ExecutionClass.BACKGROUND,a);Object old=f.claimed(id,a);
        f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
        f.recover();assertThat(f.state(id)).isEqualTo("RETRY_WAIT");f.ready(id);
        String b=owner();f.permit(ExecutionClass.BACKGROUND,b);Object current=f.claimed(id,b);
        assertThat(number(current,"executionVersion")).isGreaterThan(number(old,"executionVersion"));
        assertThat(f.renew(old)).isFalse();f.finish(current,"SUCCEEDED");
        assertThatThrownBy(()->f.finish(old,"FAILED")).isInstanceOf(ExecutionCancelledException.class);
        assertThat(f.state(id)).isEqualTo("SUCCEEDED");
        assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void duplicateDeliveryDoesNotIncrementAttemptsOrCreateLogicalJob() {
        long id=f.background();String a=owner();f.permit(ExecutionClass.BACKGROUND,a);Object first=f.claimed(id,a);
        String duplicate=owner();f.permit(ExecutionClass.BACKGROUND,duplicate);assertThat(f.claim(id,duplicate)).isEmpty();
        assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,id)).isEqualTo(1);
        f.finish(first,"SUCCEEDED");assertThat(f.claim(id,owner())).isEmpty();
    }
    @Test void renewalExtendsOnlyMatchingCurrentUnexpiredOwner() {
        long id=f.background();String owner=owner();f.permit(ExecutionClass.BACKGROUND,owner);Object lease=f.claimed(id,owner);
        var before=f.instant("lease_expires_at",id);assertThat(f.renew(lease)).isTrue();assertThat(f.instant("lease_expires_at",id)).isAfterOrEqualTo(before);
        f.app.db.update("UPDATE jobs SET owner='different-owner' WHERE id=?",id);assertThat(f.renew(lease)).isFalse();
    }
    @Test void claimRequiresExistingExecutionAdmission() {
        long id=f.background();assertThat(f.claim(id,owner())).isEmpty();
        assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,id)).isZero();
    }
}
