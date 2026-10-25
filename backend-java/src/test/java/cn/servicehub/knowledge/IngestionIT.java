package cn.servicehub.knowledge;
import cn.servicehub.jobs.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionIT {
    final KnowledgeFixture f=new KnowledgeFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    void consume(tools.jackson.databind.JsonNode document){
        long job=document.path("latest_version").path("job_id").asLong();var ack=new AtomicInteger();
        f.app.context.getBean(JobConsumer.class).accept(f.app.json.writeValueAsBytes(Map.of("job_id",job,"schema_version",1)),ExecutionClass.BACKGROUND,ack::incrementAndGet,requeue->fail("Unexpected rejection"));
        assertThat(ack).hasValue(1);
    }
    @Test void realHttpEmbeddingActivatesOnlyValidated1024DimensionVectors() throws Exception{
        var d=f.upload("售后政策与退货运费。".repeat(180));long id=d.path("id").asLong(),version=d.path("latest_version").path("id").asLong();
        var lease=f.claim(d);f.ingest(lease);var ready=f.detail(id);
        assertThat(ready.path("active_version_id").asLong()).isEqualTo(version);assertThat(ready.path("latest_version").path("status").asString()).isEqualTo("ready");
        assertThat(f.app.db.queryForObject("SELECT embedding_dimensions FROM document_versions WHERE id=?",Integer.class,version)).isEqualTo(1024);
        assertThat(f.requests.get()).isGreaterThan(0);
        assertThat(f.received).allSatisfy(request->{assertThat(request.path("model").asString()).isEqualTo("text-embedding-v4");assertThat(request.path("dimensions").asInt()).isEqualTo(1024);assertThat(request.path("input").size()).isBetween(1,10);});
        var chunks=f.app.db.queryForList("SELECT id,text,execution_version FROM knowledge_chunks WHERE version_id=? ORDER BY ordinal",version);assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(row->{assertThat(String.valueOf(row.get("text")).length()).isLessThanOrEqualTo(800);assertThat(((Number)row.get("execution_version")).longValue()).isEqualTo(lease.executionVersion());});
        Object index=f.app.requiredBean("cn.servicehub.knowledge.VectorIndex");
        var ids=(List<?>)PrivateFileStoreTest.call(index,"search",new Class<?>[]{long.class,float[].class,int.class,Map.class},1001L,unitVector(),30,Map.of(version,lease.executionVersion()));
        assertThat(ids).isNotEmpty();
    }
    @Test void pdfPagesAndMarkdownAreParsedWithPhysicalPageNumbers() throws Exception{
        var response=f.upload("/api/v1/knowledge/documents","policy.pdf","application/pdf",pdf("PageOnePolicy","PageTwoPolicy"),"PDF policy",f.admin);
        assertThat(response.statusCode()).isEqualTo(201);var d=f.app.body(response);f.ingest(f.claim(d));
        long version=d.path("latest_version").path("id").asLong();
        assertThat(f.app.db.queryForList("SELECT page FROM knowledge_chunks WHERE version_id=? ORDER BY ordinal",Integer.class,version)).containsExactly(1,2);
        var md=f.version(d.path("id").asLong(),"# Markdown\n中文售后知识");f.ingest(f.claim(md));
        assertThat(f.detail(d.path("id").asLong()).path("active_version_id").asLong()).isEqualTo(md.path("latest_version").path("id").asLong());
    }
    @Test void wrongDimensionDoesNotActivateVersion() throws Exception{
        var original=f.upload("旧的有效政策");f.ingest(f.claim(original));long active=original.path("latest_version").path("id").asLong();
        var next=f.version(original.path("id").asLong(),"新的政策");f.dimension.set(1023);consume(next);
        var after=f.detail(original.path("id").asLong());assertThat(after.path("active_version_id").asLong()).isEqualTo(active);
        assertThat(after.path("latest_version").path("status").asString()).isEqualTo("failed");
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,next.path("latest_version").path("job_id").asLong())).isEqualTo("FAILED");
    }
    @Test void transientFailureRetriesSameVersionWithoutHiddenSdkRetries() throws Exception{
        var d=f.upload("可以重试的政策");long job=d.path("latest_version").path("job_id").asLong();f.status.set(503);consume(d);
        assertThat(f.requests).hasValue(1);assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,job)).isEqualTo("RETRY_WAIT");
        f.status.set(200);f.app.db.update("UPDATE jobs SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",job);consume(d);
        assertThat(f.requests).hasValue(2);assertThat(f.detail(d.path("id").asLong()).path("active_version_id").asLong()).isEqualTo(d.path("latest_version").path("id").asLong());
        assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,job)).isEqualTo(2);
    }
    @Test void lateOldVersionCannotReplaceNewActiveVersion() throws Exception{
        var old=f.upload("旧版本在远程计算中");var lease=f.claim(old);f.entered=new CountDownLatch(1);var release=new CountDownLatch(1);f.release=release;
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->f.ingest(lease));
            try{
                assertThat(f.entered.await(5,TimeUnit.SECONDS)).isTrue();f.entered=null;f.release=null;
                var latest=f.version(old.path("id").asLong(),"新版本合法完成");f.ingest(f.claim(latest));release.countDown();
                assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(f.detail(old.path("id").asLong()).path("active_version_id").asLong()).isEqualTo(latest.path("latest_version").path("id").asLong());
            }finally{release.countDown();}
        }
    }
    @Test void pdfWithoutExtractableTextFailsExplicitly() throws Exception{
        var r=f.upload("/api/v1/knowledge/documents","scan.pdf","application/pdf",pdf(""),"No text",f.admin);assertThat(r.statusCode()).isEqualTo(201);var d=f.app.body(r);consume(d);
        var detail=f.detail(d.path("id").asLong());assertThat(detail.path("active_version_id").isNull()).isTrue();assertThat(detail.path("latest_version").path("status").asString()).isEqualTo("failed");
        assertThat(detail.path("latest_version").path("error_code").asString()).isEqualTo("pdf_no_text");assertThat(f.requests).hasValue(0);
    }
    @Test void providerAuthorizationFailureIsPermanent() throws Exception{
        var d=f.upload("配置权限无效");f.status.set(401);consume(d);
        assertThat(f.requests).hasValue(1);
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,d.path("latest_version").path("job_id").asLong())).isEqualTo("FAILED");
        assertThat(f.detail(d.path("id").asLong()).path("active_version_id").isNull()).isTrue();
    }
    @Test void malformedSuccessResponseCannotBecomeRetryingOrReady() throws Exception{
        var d=f.upload("协议损坏");f.responseMode="missing_data";consume(d);
        assertThat(f.requests).hasValue(1);
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,d.path("latest_version").path("job_id").asLong())).isEqualTo("FAILED");
        assertThat(f.detail(d.path("id").asLong()).path("latest_version").path("error_code").asString()).isEqualTo("invalid_embedding");
    }
    @Test void nonFiniteEmbeddingIsRejectedBeforeAnyActivation() throws Exception{
        var d=f.upload("无效非有限向量");f.responseMode="nonfinite";consume(d);
        assertThat(f.requests).hasValue(1);
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,d.path("latest_version").path("job_id").asLong())).isEqualTo("FAILED");
        assertThat(f.detail(d.path("id").asLong()).path("active_version_id").isNull()).isTrue();
    }
    static float[] unitVector(){float[] v=new float[1024];v[0]=1;return v;}
    static byte[] pdf(String...texts){
        var objects=new ArrayList<String>();objects.add("<< /Type /Catalog /Pages 2 0 R >>");StringBuilder kids=new StringBuilder();
        for(int i=0;i<texts.length;i++)kids.append(3+i*2).append(" 0 R ");
        objects.add("<< /Type /Pages /Kids ["+kids+"] /Count "+texts.length+" >>");int font=3+texts.length*2;
        for(int i=0;i<texts.length;i++){
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 "+font+" 0 R >> >> /Contents "+(4+i*2)+" 0 R >>");
            String content="BT /F1 12 Tf 72 720 Td ("+texts[i]+") Tj ET";objects.add("<< /Length "+content.length()+" >>\nstream\n"+content+"\nendstream");
        }
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");StringBuilder body=new StringBuilder("%PDF-1.4\n");var offsets=new ArrayList<Integer>();
        for(int i=0;i<objects.size();i++){offsets.add(body.length());body.append(i+1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");}
        int xref=body.length();body.append("xref\n0 ").append(objects.size()+1).append("\n0000000000 65535 f \n");
        for(int offset:offsets)body.append(String.format(Locale.ROOT,"%010d 00000 n \n",offset));
        body.append("trailer\n<< /Size ").append(objects.size()+1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");return body.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
