package cn.servicehub.ai;
import cn.servicehub.conversations.ExecutionFence;
import cn.servicehub.jobs.*;
import cn.servicehub.knowledge.Evidence;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AICommitFenceIT {
    final AgentFixture f=new AgentFixture();final AtomicLong ids=new AtomicLong(800000);
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    Object result(List<Evidence> evidence){
        try{return Class.forName("cn.servicehub.ai.AgentResult").getConstructor(String.class,List.class,Long.class,List.class,ModelGateway.ActualUsage.class).newInstance("受控结果",evidence,null,List.of(),null);}
        catch(ReflectiveOperationException e){return fail("Missing AgentResult",e);}
    }
    boolean commit(JobLease lease,ExecutionFence fence,Object result){return (Boolean)AgentFixture.call(f.app.requiredBean("cn.servicehub.ai.AIService"),"commit",new Class<?>[]{JobLease.class,ExecutionFence.class,result.getClass()},lease,fence,result);}
    int answers(){return f.app.db.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND author_role='ai'",Integer.class,f.room);}
    @Test void validResultCommitsOnceWithJobAndDurableEvent(){
        var result=result(List.of());assertThat(commit(f.lease,f.fence,result)).isTrue();assertThat(commit(f.lease,f.fence,result)).isFalse();
        assertThat(answers()).isEqualTo(1);assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.lease.jobId())).isEqualTo("SUCCEEDED");
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM conversation_events WHERE conversation_id=? AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.message.author_role'))='ai'",Integer.class,f.room)).isEqualTo(1);
        assertThat(f.app.db.queryForObject("SELECT JSON_TYPE(`usage`) FROM ai_runs WHERE id=?",String.class,f.fence.runId())).isNull();
    }
    @Test void handoffAndRevocationRejectSubmission() throws Exception{
        assertThat(f.app.request("POST","/api/v1/conversations/"+f.room+"/handoff",Map.of(),f.customer).statusCode()).isEqualTo(200);
        assertThat(commit(f.lease,f.fence,result(List.of()))).isFalse();assertThat(answers()).isZero();
        f.reset();f.app.db.update("UPDATE users SET is_active=0,session_version=session_version+1 WHERE id=2003");
        assertThat(commit(f.lease,f.fence,result(List.of()))).isFalse();assertThat(answers()).isZero();
    }
    @Test void wrongTopicEpochRunAndExecutionVersionCannotInsert(){
        var x=f.fence;var result=result(List.of());
        for(var bad:List.of(new ExecutionFence(x.tenantId(),x.conversationId(),x.topicId()+1,x.runId(),x.generationEpoch(),x.executionVersion()),
            new ExecutionFence(x.tenantId(),x.conversationId(),x.topicId(),x.runId()+1,x.generationEpoch(),x.executionVersion()),
            new ExecutionFence(x.tenantId(),x.conversationId(),x.topicId(),x.runId(),x.generationEpoch()+1,x.executionVersion()),
            new ExecutionFence(x.tenantId(),x.conversationId(),x.topicId(),x.runId(),x.generationEpoch(),x.executionVersion()+1)))assertThat(commit(f.lease,bad,result)).isFalse();
        assertThat(answers()).isZero();
    }
    @Test void expiredWorkerCannotSubmitAfterReplacementClaim(){
        var old=f.lease;var fence=f.fence;f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",old.jobId());
        assertThat(commit(old,fence,result(List.of()))).isFalse();
        f.app.context.getBean(JobRecoveryScheduler.class).recoverOnce();f.app.db.update("UPDATE jobs SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",old.jobId());f.claim(old.jobId());
        assertThat(commit(old,fence,result(List.of()))).isFalse();
        var current=new ExecutionFence(fence.tenantId(),fence.conversationId(),fence.topicId(),fence.runId(),fence.generationEpoch(),f.lease.executionVersion());
        assertThat(commit(f.lease,current,result(List.of()))).isTrue();assertThat(answers()).isEqualTo(1);
    }
    @Test void disabledCitationMakesWholeSubmissionRollback() throws Exception{
        long doc=ids.incrementAndGet(),version=ids.incrementAndGet(),chunk=ids.incrementAndGet();
        f.app.db.update("INSERT INTO knowledge_documents(id,tenant_id,title,uploaded_by) VALUES(?,1001,'policy',2001)",doc);
        f.app.db.update("INSERT INTO document_versions(id,tenant_id,document_id,number,storage_key,filename,media_type,size_bytes,sha256,status,embedding_model,embedding_dimensions,index_execution_version) VALUES(?,1001,?,1,?,'policy.txt','text/plain',12,?,'ready','text-embedding-v4',1024,1)",version,doc,"a".repeat(32)+".txt","b".repeat(64));
        f.app.db.update("INSERT INTO knowledge_chunks(id,tenant_id,document_id,version_id,execution_version,ordinal,page,text) VALUES(?,1001,?,?,1,0,1,'退货政策')",chunk,doc,version);
        f.app.db.update("INSERT INTO knowledge_active_versions(tenant_id,document_id,version_id) VALUES(1001,?,?)",doc,version);
        var result=result(List.of(new Evidence(doc,version,chunk,"policy.txt",1,"退货政策",0.1)));
        assertThat(f.app.request("PATCH","/api/v1/knowledge/documents/"+doc,Map.of("version",1,"enabled",false),f.admin).statusCode()).isEqualTo(200);
        assertThat(commit(f.lease,f.fence,result)).isFalse();assertThat(answers()).isZero();
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.lease.jobId())).isEqualTo("RUNNING");
    }
}
