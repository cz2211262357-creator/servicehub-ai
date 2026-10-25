package cn.servicehub.knowledge;
import cn.servicehub.common.*;
import cn.servicehub.conversations.ContextBuilder;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.Resource;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CitationAccessIT {
    final RetrievalFixture f=new RetrievalFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    Object evidence() throws Exception{f.ready("七日内退货运费政策");var found=f.search("退货",6);assertThat(found).isNotEmpty();return found.getFirst();}
    void disable(long doc) throws Exception{
        var d=f.k.detail(doc);var response=f.k.app.request("PATCH","/api/v1/knowledge/documents/"+doc,Map.of("enabled",false,"version",d.path("version").asInt()),f.k.admin);
        assertThat(response.statusCode()).isEqualTo(200);
    }
    Object historical(long conversation,long version){return PrivateFileStoreTest.call(f.citations(),"openHistorical",new Class<?>[]{Actor.class,long.class,long.class},f.actor(),conversation,version);}
    @Test void currentEvidenceIsRevalidatedAfterDisableOrReplacement() throws Exception{
        var e=evidence();f.readable(List.of(e));long doc=RetrievalFixture.number(e,"documentId");
        var next=f.k.version(doc,"版本更新后的政策");f.ingest(next);
        assertThatThrownBy(()->f.readable(List.of(e))).isInstanceOf(BusinessException.class);
        var current=f.search("退货",6);assertThat(current).isNotEmpty();disable(doc);
        assertThatThrownBy(()->f.readable(current)).isInstanceOf(BusinessException.class);
    }
    @Test void forgedExcerptCannotPassCitationValidation() throws Exception{
        var e=evidence();Class<?> type=e.getClass();
        var forged=type.getConstructor(long.class,long.class,long.class,String.class,Integer.class,String.class,double.class).newInstance(
            RetrievalFixture.number(e,"documentId"),RetrievalFixture.number(e,"versionId"),RetrievalFixture.number(e,"chunkId"),
            RetrievalFixture.field(e,"filename"),RetrievalFixture.field(e,"page"),"伪造的退款保证",1.0);
        assertThatThrownBy(()->f.readable(List.of(forged))).isInstanceOf(BusinessException.class);
    }
    @Test void originalCitationDownloadsExactOldVersionOnlyForOwner() throws Exception{
        var e=evidence();long doc=RetrievalFixture.number(e,"documentId"),version=RetrievalFixture.number(e,"versionId");f.recordAnswer(e);
        var next=f.k.version(doc,"此处是新政策正文");f.ingest(next);
        var download=f.k.app.request("GET","/api/v1/knowledge/documents/"+doc+"/download?version_id="+version,null,f.k.customer);
        assertThat(download.statusCode()).isEqualTo(200);assertThat(download.body()).contains("七日内");
        assertThat(historical(f.room,version)).isInstanceOf(Resource.class);
        assertThat(f.k.app.request("GET","/api/v1/knowledge/documents/"+doc+"/download?version_id="+version,null,f.k.other).statusCode()).isEqualTo(404);
        f.k.app.db.update("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role) SELECT 2005,1001,'user2005@example.com','Other customer',password_hash,'customer' FROM users WHERE id=2003");
        var other=f.k.app.login(2005);
        assertThat(f.k.app.request("GET","/api/v1/knowledge/documents/"+doc+"/download?version_id="+version,null,other).statusCode()).isEqualTo(404);
    }
    @Test void explicitHistoricalConversationCannotBorrowAnotherOwnConversationCitation() throws Exception{
        var e=evidence();long doc=RetrievalFixture.number(e,"documentId"),version=RetrievalFixture.number(e,"versionId");f.recordAnswer(e);long citedRoom=f.room;
        f.ingest(f.k.version(doc,"新的政策"));f.newQuery();
        assertThatThrownBy(()->historical(f.room,version)).isInstanceOf(BusinessException.class);
        assertThat(historical(citedRoom,version)).isInstanceOf(Resource.class);
        // Legacy public endpoint deliberately accepts an original citation from any own conversation.
        assertThat(f.k.app.request("GET","/api/v1/knowledge/documents/"+doc+"/download?version_id="+version,null,f.k.customer).statusCode()).isEqualTo(200);
    }
    @Test void disabledHistoricalDocumentCannotBeReopened() throws Exception{
        var e=evidence();long doc=RetrievalFixture.number(e,"documentId"),version=RetrievalFixture.number(e,"versionId");f.recordAnswer(e);f.ingest(f.k.version(doc,"新正文"));disable(doc);
        assertThatThrownBy(()->historical(f.room,version)).isInstanceOf(BusinessException.class);
        assertThat(f.k.app.request("GET","/api/v1/knowledge/documents/"+doc+"/download?version_id="+version,null,f.k.customer).statusCode()).isEqualTo(404);
    }
    @Test void revokedActorCannotReusePreviouslyValidatedEvidence() throws Exception{
        var e=evidence();f.readable(List.of(e));f.k.app.db.update("UPDATE users SET session_version=session_version+1,is_active=0 WHERE id=2003");
        assertThatThrownBy(()->f.readable(List.of(e))).isInstanceOf(BusinessException.class);
    }
    @Test void contextDropsWholeOldAnswerWhenItsSourceIsNoLongerReadable() throws Exception{
        var e=evidence();f.recordAnswer(e);disable(RetrievalFixture.number(e,"documentId"));f.sendQuery();
        var context=f.k.app.context.getBean(ContextBuilder.class).build(f.actor(),f.fence);
        assertThat(context.stream().map(ContextBuilder.ContextMessage::body)).doesNotContain("带有效来源的历史回答");
    }
}
