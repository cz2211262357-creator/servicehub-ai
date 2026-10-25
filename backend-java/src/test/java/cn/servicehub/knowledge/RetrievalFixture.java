package cn.servicehub.knowledge;
import cn.servicehub.ai.*;
import cn.servicehub.common.Actor;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
final class RetrievalFixture implements AutoCloseable {
    final KnowledgeFixture k=new KnowledgeFixture();JobLease lease;ExecutionFence fence;long room;
    void start() throws Exception{k.start();}
    void reset() throws Exception{k.permits.forEach(ExecutionPermit::close);k.permits.clear();k.reset();k.app.db.update("UPDATE knowledge_documents SET enabled=0,version=version+1 WHERE enabled=1");newQuery();}
    void newQuery() throws Exception{
        var response=k.app.request("POST","/api/v1/conversations",Map.of(),k.customer);assertThat(response.statusCode()).isEqualTo(201);room=k.app.body(response).path("id").asLong();sendQuery();
    }
    void sendQuery() throws Exception{
        var response=k.app.request("POST","/api/v1/conversations/"+room+"/messages",Map.of("client_message_id",UUID.randomUUID().toString(),"body","查询退货政策"),k.customer);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);var message=k.app.body(response);String owner="query-"+UUID.randomUUID();
        k.permits.add(k.app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.INTERACTIVE,owner).orElseThrow());
        lease=k.app.context.getBean(JobCoordinator.class).claim(message.path("trigger_job_id").asLong(),owner).orElseThrow();
        fence=k.app.context.getBean(TopicService.class).resolve(lease,message.path("id").asLong(),TopicDecision.of("CONTINUE",null,false)).orElseThrow();
    }
    Actor actor(){return lease.actor();}
    CancellationGuard guard(){return k.app.context.getBean(CancellationGuardFactory.class).forRun(fence,lease);}
    JsonNode ready(String text) throws Exception{var d=k.upload(text);ingest(d);return k.detail(d.path("id").asLong());}
    void ingest(JsonNode doc){var job=k.claim(doc);try{k.ingest(job);}finally{closePermit(job.owner());}}
    void closePermit(String owner){k.permits.stream().filter(p->p.owner().equals(owner)).forEach(ExecutionPermit::close);}
    Object retriever(){return k.app.requiredBean("cn.servicehub.knowledge.HybridRetriever");}
    Object citations(){return k.app.requiredBean("cn.servicehub.knowledge.CitationAuthorizer");}
    @SuppressWarnings("unchecked") List<Object> search(String text,int limit){return (List<Object>)PrivateFileStoreTest.call(retriever(),"search",new Class<?>[]{Actor.class,String.class,int.class,CancellationGuard.class},actor(),text,limit,guard());}
    @SuppressWarnings("unchecked") List<Long> keyword(String text){return (List<Long>)PrivateFileStoreTest.call(k.app.context.getBean(KnowledgeMapper.class),"keyword",new Class<?>[]{long.class,String.class,int.class},1001L,text,30);}
    void readable(List<?> evidence){PrivateFileStoreTest.call(citations(),"requireReadable",new Class<?>[]{Actor.class,long.class,List.class},actor(),room,evidence);}
    static Object field(Object object,String field){return PrivateFileStoreTest.call(object,field,new Class<?>[]{});}
    static long number(Object object,String field){return ((Number)field(object,field)).longValue();}
    Map<String,Object> wire(Object evidence){return Map.of("document_id",number(evidence,"documentId"),"version_id",number(evidence,"versionId"),"chunk_id",number(evidence,"chunkId"),"page",field(evidence,"page"),"text",field(evidence,"excerpt"),"score",field(evidence,"score"),"title","Synthetic policy");}
    void recordAnswer(Object evidence){
        k.app.context.getBean(JobCoordinator.class).finish(lease,"SUCCEEDED",Map.of());closePermit(lease.owner());
        k.app.db.update("INSERT INTO messages(tenant_id,conversation_id,sender_id,author_role,ai_run_id,body,citations,topic_id,routing_state,context_status) VALUES(1001,?,NULL,'ai',?,'带有效来源的历史回答',?,?,'RESOLVED','ELIGIBLE')",room,fence.runId(),k.app.json.writeValueAsString(List.of(wire(evidence))),fence.topicId());
    }
    @Override public void close(){k.close();}
}
