package cn.servicehub.knowledge;
import cn.servicehub.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionCleanupIT {
    final KnowledgeFixture f=new KnowledgeFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    @Test void cleanupRemovesOldExecutionVectorsButPreservesActivatedGeneration() throws Exception{
        var d=f.upload("待重领的知识");long version=d.path("latest_version").path("id").asLong();
        var first=f.claim(d);var service=f.app.context.getBean(KnowledgeService.class);var index=f.app.context.getBean(VectorIndex.class);
        var chunks=service.stage(first,List.of(new DocumentParser.TextChunk(1,"旧执行分块")));
        index.write(1001,version,first.executionVersion(),chunks.getFirst().id(),IngestionIT.unitVector());
        f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",first.jobId());
        f.app.context.getBean(JobRecoveryScheduler.class).recoverOnce();
        f.app.db.update("UPDATE jobs SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",first.jobId());
        var second=f.claim(d);f.ingest(second);
        assertThat(index.search(1001,IngestionIT.unitVector(),30,Map.of(version,first.executionVersion()))).isNotEmpty();
        f.app.db.update("UPDATE knowledge_index_generations SET last_cleanup_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE WHERE version_id=?",version);
        PrivateFileStoreTest.call(f.app.requiredBean("cn.servicehub.knowledge.InactiveIndexCleanup"),"cleanupOnce",new Class<?>[]{});
        assertThat(index.search(1001,IngestionIT.unitVector(),30,Map.of(version,first.executionVersion()))).isEmpty();
        assertThat(index.search(1001,IngestionIT.unitVector(),30,Map.of(version,second.executionVersion()))).isNotEmpty();
        assertThat(f.detail(d.path("id").asLong()).path("active_version_id").asLong()).isEqualTo(version);
    }
    @Test void repeatedCleanupCollectsLateOrphanWritesAndCannotTouchAnotherTenant() throws Exception{
        var d=f.upload("已取消的导入");long version=d.path("latest_version").path("id").asLong();var lease=f.claim(d);
        var chunks=f.app.context.getBean(KnowledgeService.class).stage(lease,List.of(new DocumentParser.TextChunk(1,"取消的分块")));
        var index=f.app.context.getBean(VectorIndex.class);long chunk=chunks.getFirst().id();
        index.write(1001,version,lease.executionVersion(),chunk,IngestionIT.unitVector());
        index.write(1002,version,lease.executionVersion(),chunk,IngestionIT.unitVector());
        f.app.context.getBean(JobCoordinator.class).fail(lease,"execution_cancelled",false);
        for(int i=0;i<2;i++){
            f.app.db.update("UPDATE knowledge_index_generations SET last_cleanup_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE WHERE version_id=?",version);
            PrivateFileStoreTest.call(f.app.requiredBean("cn.servicehub.knowledge.InactiveIndexCleanup"),"cleanupOnce",new Class<?>[]{});
            assertThat(index.search(1001,IngestionIT.unitVector(),30,Map.of(version,lease.executionVersion()))).isEmpty();
            if(i==0)index.write(1001,version,lease.executionVersion(),chunk,IngestionIT.unitVector());
        }
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM knowledge_chunks WHERE version_id=?",Integer.class,version)).isZero();
        assertThat(index.search(1002,IngestionIT.unitVector(),30,Map.of(version,lease.executionVersion()))).hasSize(1);
    }
}
