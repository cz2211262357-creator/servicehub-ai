package cn.servicehub.knowledge;
import cn.servicehub.jobs.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackgroundScopeIT {
    final KnowledgeFixture f=new KnowledgeFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    @Test void switchingAndStoppingForegroundDoesNotCancelKnowledgeImport() throws Exception{
        var d=f.upload("后台独立的知识导入");var lease=f.claim(d);f.entered=new CountDownLatch(1);f.release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->f.ingest(lease));
            try{
                assertThat(f.entered.await(5,TimeUnit.SECONDS)).isTrue();
                var created=f.app.request("POST","/api/v1/conversations",Map.of(),f.customer);assertThat(created.statusCode()).isEqualTo(201);
                long room=f.app.body(created).path("id").asLong(),epoch=f.app.body(created).path("generation_epoch").asLong();
                var switched=f.app.request("POST","/api/v1/conversations/"+room+"/topics/switch",Map.of("mode","NEW","expected_generation_epoch",epoch),f.customer);assertThat(switched.statusCode()).isEqualTo(200);
                var stopped=f.app.request("POST","/api/v1/conversations/"+room+"/interrupt",Map.of("expected_generation_epoch",f.app.body(switched).path("generation_epoch").asLong()),f.customer);assertThat(stopped.statusCode()).isEqualTo(200);
                f.release.countDown();pending.get(5,TimeUnit.SECONDS);
                assertThat(f.detail(d.path("id").asLong()).path("active_version_id").asLong()).isEqualTo(d.path("latest_version").path("id").asLong());
                assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND author_role='ai'",Integer.class,room)).isZero();
            }finally{f.release.countDown();}
        }
    }
    @Test void revokedUploaderCannotActivateEvenIfEmbeddingResponseArrives() throws Exception{
        var d=f.upload("撤权期间执行导入");long job=d.path("latest_version").path("job_id").asLong();
        f.entered=new CountDownLatch(1);f.release=new CountDownLatch(1);var ack=new AtomicInteger();
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->f.app.context.getBean(JobConsumer.class).accept(f.app.json.writeValueAsBytes(Map.of("job_id",job,"schema_version",1)),ExecutionClass.BACKGROUND,ack::incrementAndGet,requeue->fail("Unexpected rejection")));
            try{
                assertThat(f.entered.await(5,TimeUnit.SECONDS)).isTrue();
                f.app.db.update("UPDATE users SET is_active=0,session_version=session_version+1 WHERE id=2001");
                f.release.countDown();pending.get(5,TimeUnit.SECONDS);
                var visible=f.app.body(f.app.request("GET","/api/v1/knowledge/documents/"+d.path("id").asLong(),null,f.agent));
                assertThat(visible.path("latest_version").path("status").asString()).isEqualTo("failed");
                assertThat(ack).hasValue(1);assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,job)).isEqualTo("CANCELLED");
                assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM knowledge_active_versions WHERE document_id=?",Integer.class,d.path("id").asLong())).isZero();
            }finally{f.release.countDown();}
        }
    }
    @Test void expiredUnreclaimedLeaseCannotActivateStagedKnowledge() throws Exception{
        var d=f.upload("租约边界的导入");var lease=f.claim(d);f.entered=new CountDownLatch(1);f.release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->f.ingest(lease));
            try{
                assertThat(f.entered.await(5,TimeUnit.SECONDS)).isTrue();
                f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",lease.jobId());f.release.countDown();
                assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM knowledge_active_versions WHERE document_id=?",Integer.class,d.path("id").asLong())).isZero();
                f.app.context.getBean(JobRecoveryScheduler.class).recoverOnce();
                assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,lease.jobId())).isEqualTo("RETRY_WAIT");
            }finally{f.release.countDown();}
        }
    }
}
