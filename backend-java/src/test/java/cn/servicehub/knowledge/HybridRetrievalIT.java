package cn.servicehub.knowledge;
import cn.servicehub.ai.*;
import cn.servicehub.common.BusinessException;
import cn.servicehub.jobs.ExecutionClass;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridRetrievalIT {
    final RetrievalFixture f=new RetrievalFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    @Test void chineseFulltextAndNonLexicalQueryUseBothAuthorizedRoutes() throws Exception{
        var d=f.ready("商品签收七天内可申请退货，运费由商家承担。");
        assertThat(f.keyword("退货")).isNotEmpty();
        int before=f.k.requests.get();var lexical=f.search("退货",6);var nonLexical=f.search("寄回去快递谁买单",6);
        assertThat(lexical).anySatisfy(e->assertThat(RetrievalFixture.number(e,"documentId")).isEqualTo(d.path("id").asLong()));
        assertThat(nonLexical).anySatisfy(e->assertThat(RetrievalFixture.number(e,"documentId")).isEqualTo(d.path("id").asLong()));
        assertThat(f.k.requests.get()).isEqualTo(before+2);
        assertThat(f.k.received.getLast().path("input").get(0).asString()).isEqualTo("寄回去快递谁买单");
    }
    @Test void emptyQueryAndNoActiveVersionsDoNotCallProvider() {
        assertThat(f.search("   ",6)).isEmpty();assertThat(f.search("无知识库",6)).isEmpty();assertThat(f.k.requests).hasValue(0);
    }
    @Test void vectorCandidatesReturnThirtyBeforeFinalSixAndOrderIsStable() throws Exception{
        var d=f.ready("退货运费政策。".repeat(3300));long version=d.path("active_version_id").asLong();
        long generation=f.k.app.db.queryForObject("SELECT index_execution_version FROM document_versions WHERE id=?",Long.class,version);
        assertThat(f.k.app.context.getBean(VectorIndex.class).search(1001,IngestionIT.unitVector(),30,Map.of(version,generation))).hasSize(30);
        var first=f.search("退货",99);var second=f.search("退货",99);assertThat(first).hasSize(6);
        assertThat(first.stream().map(e->RetrievalFixture.number(e,"chunkId")).toList()).isEqualTo(second.stream().map(e->RetrievalFixture.number(e,"chunkId")).toList());
        for(int i=1;i<first.size();i++)assertThat(((Number)RetrievalFixture.field(first.get(i-1),"score")).doubleValue()).isGreaterThanOrEqualTo(((Number)RetrievalFixture.field(first.get(i),"score")).doubleValue());
    }
    @Test void tenantAndActivatedGenerationAreFilteredBeforeTopK() throws Exception{
        var d=f.ready("退货政策正文");long version=d.path("active_version_id").asLong();
        long generation=f.k.app.db.queryForObject("SELECT index_execution_version FROM document_versions WHERE id=?",Long.class,version);
        var index=f.k.app.context.getBean(VectorIndex.class);
        for(int i=0;i<35;i++){index.write(1002,version,generation,900000+i,IngestionIT.unitVector());index.write(1001,version,generation+1,910000+i,IngestionIT.unitVector());}
        var hits=index.search(1001,IngestionIT.unitVector(),30,Map.of(version,generation));assertThat(hits).hasSize(1);assertThat(hits.getFirst().chunkId()).isLessThan(900000);
        assertThat(f.search("退货",6)).allSatisfy(e->{
            long id=RetrievalFixture.number(e,"chunkId");
            assertThat(f.k.app.db.queryForObject("SELECT tenant_id FROM knowledge_chunks WHERE id=?",Long.class,id)).isEqualTo(1001);
            assertThat(RetrievalFixture.number(e,"versionId")).isEqualTo(version);
        });
    }
    @Test void disabledAndOldVersionCannotReturnEvenIfVectorsRemain() throws Exception{
        var d=f.ready("原有效退货政策");long doc=d.path("id").asLong(),old=d.path("active_version_id").asLong();
        var next=f.k.version(doc,"更新后的退货政策");f.ingest(next);
        assertThat(f.search("退货",6)).isNotEmpty().allSatisfy(e->assertThat(RetrievalFixture.number(e,"versionId")).isNotEqualTo(old));
        var current=f.k.detail(doc);var response=f.k.app.request("PATCH","/api/v1/knowledge/documents/"+doc,Map.of("enabled",false,"version",current.path("version").asInt()),f.k.admin);
        assertThat(response.statusCode()).isEqualTo(200);assertThat(f.search("退货",6)).isEmpty();assertThat(f.keyword("退货")).isEmpty();
    }
    @Test void vectorDependencyFailureCannotMasqueradeAsFullHybridResult() throws Exception{
        f.ready("退货政策关键词仍能命中");assertThat(f.keyword("退货")).isNotEmpty();
        var index=f.k.app.context.getBean(VectorIndex.class);var name=VectorIndex.class.getDeclaredField("index");name.trySetAccessible();
        byte[] key=((String)name.get(index)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        f.k.app.context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).execute(
            (org.springframework.data.redis.core.RedisCallback<Object>)connection->connection.execute("FT.DROPINDEX",key));
        assertThatThrownBy(()->f.search("退货",6)).isInstanceOf(BusinessException.class)
            .satisfies(error->assertThat(((BusinessException)error).code()).isEqualTo("retrieval_unavailable"));
    }
    @Test void foregroundQueryUsesReservedModelSlotWhileThreeBackgroundCallsRun() throws Exception{
        f.ready("退货运费政策");var entered=new CountDownLatch(3);var release=new CountDownLatch(1);var slots=f.k.app.context.getBean(GlobalModelSlots.class);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<3;i++)tasks.add(pool.submit(()->slots.withPermit(ExecutionClass.BACKGROUND,Duration.ofSeconds(3),()->{entered.countDown();release.await(6,TimeUnit.SECONDS);return null;},()->{})));
            try{
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();var query=pool.submit(()->f.search("退货",6));
                assertThat(query.get(3,TimeUnit.SECONDS)).isNotEmpty();
            }finally{release.countDown();for(var task:tasks)task.get(3,TimeUnit.SECONDS);}
        }
    }
}
