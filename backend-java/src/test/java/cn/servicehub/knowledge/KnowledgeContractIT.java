package cn.servicehub.knowledge;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeContractIT {
    final KnowledgeFixture f=new KnowledgeFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    @Test void uploadCreatesPrivateVersionAndDurableBackgroundJob() throws Exception{
        var document=f.upload("七天无理由退货政策");long id=document.path("id").asLong();
        assertThat(id).isPositive();assertThat(document.path("title").asString()).isEqualTo("Synthetic policy");
        var version=document.path("latest_version");assertThat(version.path("number").asInt()).isEqualTo(1);assertThat(version.path("status").asString()).isEqualTo("queued");assertThat(version.path("job_id").asLong()).isPositive();
        assertThat(document.has("storage_key")).isFalse();assertThat(version.has("storage_key")).isFalse();assertThat(document.toString()).doesNotContain(f.root.toString());
        long job=version.path("job_id").asLong();
        assertThat(f.app.db.queryForObject("SELECT execution_class FROM jobs WHERE id=?",String.class,job)).isEqualTo("BACKGROUND");
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE job_id=?",Integer.class,job)).isEqualTo(1);
        assertThat(f.requests).hasValue(0);
    }
    @Test void onlyAdminCanUploadOrMutateDocuments() throws Exception{
        for(var user:java.util.List.of(f.customer,f.agent)){
            var response=f.upload("/api/v1/knowledge/documents","policy.txt","text/plain",new byte[]{'x'},"policy",user);
            assertThat(response.statusCode()).isEqualTo(403);
        }
        var document=f.upload("真实政策");long id=document.path("id").asLong();
        assertThat(f.app.request("PATCH","/api/v1/knowledge/documents/"+id,Map.of("enabled",false,"version",1),f.agent).statusCode()).isEqualTo(403);
        assertThat(f.app.request("DELETE","/api/v1/knowledge/documents/"+id,null,f.customer).statusCode()).isEqualTo(403);
    }
    @Test void listDetailAndCrossTenantIsolationPreserveWireContract() throws Exception{
        var document=f.upload("售后政策");long id=document.path("id").asLong();
        var list=f.app.request("GET","/api/v1/knowledge/documents?limit=1",null,f.customer);assertThat(list.statusCode()).isEqualTo(200);
        assertThat(f.app.body(list).path("items").size()).isEqualTo(1);assertThat(f.app.body(list).has("next_cursor")).isTrue();
        assertThat(f.detail(id).path("latest_version").path("filename").asString()).isEqualTo("synthetic.txt");
        assertThat(f.app.request("GET","/api/v1/knowledge/documents/"+id,null,f.other).statusCode()).isEqualTo(404);
        assertThat(f.app.request("GET","/api/v1/knowledge/documents?limit=0",null,f.admin).statusCode()).isEqualTo(422);
    }
    @Test void versionUploadRetainsOriginalAndUsesCurrentDocumentRevision() throws Exception{
        var original=f.upload("旧政策");var second=f.version(original.path("id").asLong(),"新政策");
        assertThat(second.path("version").asInt()).isEqualTo(2);assertThat(second.path("latest_version").path("number").asInt()).isEqualTo(2);
        long job=second.path("latest_version").path("job_id").asLong();
        assertThat(f.app.db.queryForObject("SELECT CAST(JSON_EXTRACT(payload,'$.document_revision') AS UNSIGNED) FROM jobs WHERE id=?",Long.class,job)).isEqualTo(2L);
    }
    @Test void enableUsesOptimisticVersionAndDeletedDocumentIsUnavailable() throws Exception{
        var d=f.upload("可见性政策");long id=d.path("id").asLong();
        var disabled=f.app.request("PATCH","/api/v1/knowledge/documents/"+id,Map.of("enabled",false,"version",1),f.admin);assertThat(disabled.statusCode()).isEqualTo(200);assertThat(f.app.body(disabled).path("version").asInt()).isEqualTo(2);
        assertThat(f.app.request("PATCH","/api/v1/knowledge/documents/"+id,Map.of("enabled",true,"version",1),f.admin).statusCode()).isEqualTo(409);
        var removed=f.app.request("DELETE","/api/v1/knowledge/documents/"+id,null,f.admin);assertThat(removed.statusCode()).isEqualTo(204);assertThat(removed.body()).isEmpty();
        assertThat(f.app.request("GET","/api/v1/knowledge/documents/"+id,null,f.admin).statusCode()).isEqualTo(404);
    }
    @Test void staffCanDownloadOriginalButCustomerNeedsReadyEnabledVersion() throws Exception{
        var d=f.upload("私有正文内容");long id=d.path("id").asLong();String path="/api/v1/knowledge/documents/"+id+"/download";
        var staff=f.app.request("GET",path,null,f.agent);assertThat(staff.statusCode()).isEqualTo(200);assertThat(staff.body()).isEqualTo("私有正文内容");
        assertThat(staff.headers().firstValue("Content-Disposition").orElse("")).contains("attachment","synthetic.txt");
        assertThat(f.app.request("GET",path,null,f.customer).statusCode()).isEqualTo(404);
        assertThat(f.app.request("GET",path,null,f.other).statusCode()).isEqualTo(404);
    }
    @Test void unsupportedAndEmptyUploadsHaveSpecificErrors() throws Exception{
        var unsupported=f.upload("/api/v1/knowledge/documents","file.exe","application/octet-stream",new byte[]{1},"",f.admin);
        assertThat(unsupported.statusCode()).isEqualTo(415);assertThat(f.app.body(unsupported).path("error").path("code").asString()).isEqualTo("unsupported_document");
        var empty=f.upload("/api/v1/knowledge/documents","empty.txt","text/plain",new byte[0],"",f.admin);
        assertThat(empty.statusCode()).isEqualTo(422);assertThat(f.app.body(empty).path("error").path("code").asString()).isEqualTo("empty_upload");
    }

    @Test void multipartLimitAndMissingPartKeepTheLegacyHttpErrorContract() throws Exception {
        var big=f.upload("/api/v1/knowledge/documents","oversize.txt","text/plain",new byte[10*1024*1024+1],"",f.admin);
        assertThat(big.statusCode()).as(big.body()).isEqualTo(413);
        assertThat(f.app.body(big).path("error").path("code").asString()).isEqualTo("upload_too_large");
    }
    @Test void missingMultipartFileIsAValidationError() throws Exception {
        String boundary="MissingPartBoundary";
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+f.app.context.getEnvironment().getProperty("local.server.port")+"/api/v1/knowledge/documents"))
            .header("Cookie",f.admin.cookie()).header("x-csrf-token",f.admin.csrf()).header("Origin","http://localhost:8081")
            .header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("--"+boundary+"\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nMissing file\r\n--"+boundary+"--\r\n")).build();
        var missing=f.app.http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(422);
        assertThat(f.app.body(missing).path("error").path("code").asString()).isEqualTo("validation_error");
        assertThat(f.app.body(missing).path("error").path("details").get(0).path("field").asString()).isEqualTo("body.file");
        assertThat(f.app.body(missing).path("error").path("details").get(0).path("type").asString()).isEqualTo("missing");
    }

    @Test void exactlyTenMiBIsAcceptedAtTheHttpBoundary() throws Exception {
        byte[] content=new byte[10*1024*1024];java.util.Arrays.fill(content,(byte)'x');
        var response=f.upload("/api/v1/knowledge/documents","at-limit.txt","text/plain",content,"Limit",f.admin);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(f.app.body(response).path("latest_version").path("size_bytes").asLong()).isEqualTo(10485760L);
    }
}
