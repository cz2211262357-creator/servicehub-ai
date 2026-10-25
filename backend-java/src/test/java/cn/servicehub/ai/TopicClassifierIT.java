package cn.servicehub.ai;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicClassifierIT {
    final ProviderFixture p=new ProviderFixture();
    @BeforeAll void start() throws Exception{p.start();}
    @BeforeEach void reset() throws Exception{p.reset();}
    @AfterAll void close(){p.close();}
    void ambiguous() throws Exception{
        var response=p.f.app.request("POST","/api/v1/conversations/"+p.f.room+"/messages",Map.of("client_message_id",UUID.randomUUID().toString(),"body","接着那个做，还是当前这个？"),p.f.customer);
        assertThat(response.statusCode()).isEqualTo(200);p.f.message=p.f.app.body(response);p.f.claim(p.f.message.path("trigger_job_id").asLong());p.f.fence=null;
    }
    TopicDecision route(){return (TopicDecision)AgentFixture.call(p.f.app.context.getBean(TopicRouter.class),"routeForJob",new Class<?>[]{JobLease.class},p.f.lease);}
    void recover(){
        p.f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",p.f.lease.jobId());p.f.app.context.getBean(JobRecoveryScheduler.class).recoverOnce();
        p.f.app.db.update("UPDATE jobs SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",p.f.lease.jobId());p.f.claim(p.f.lease.jobId());
    }
    @Test void deterministicFollowupDoesNotInvokeClassifier(){assertThat(route().kind()).isEqualTo(TopicDecision.Kind.CONTINUE);assertThat(p.count).hasValue(0);}
    @Test void oneClassifiedDecisionSurvivesWorkerReclaim() throws Exception{
        ambiguous();p.mode="classification";p.decisionText="{\"kind\":\"CONTINUE\",\"topic_id\":null,\"requires_clarification\":false}";
        var first=route();assertThat(first.kind()).isEqualTo(TopicDecision.Kind.CONTINUE);assertThat(p.count).hasValue(1);recover();assertThat(route()).isEqualTo(first);assertThat(p.count).hasValue(1);
        assertThat(p.f.app.db.queryForObject("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",Integer.class,p.f.lease.jobId())).isEqualTo(1);
        assertThat(p.f.app.context.getBeansOfType(TopicRouter.Classifier.class)).hasSize(1);
    }
    @Test void invalidClassificationSafelyWaitsForExplicitUserChoice() throws Exception{
        ambiguous();p.mode="text";var decision=route();assertThat(decision.kind()).isEqualTo(TopicDecision.Kind.AMBIGUOUS);
        assertThat(p.f.app.context.getBean(TopicService.class).resolve(p.f.lease,p.f.message.path("id").asLong(),decision)).isEmpty();
        assertThat(p.f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,p.f.lease.jobId())).isEqualTo("WAITING_INPUT");
        assertThat(p.f.app.db.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND author_role='ai'",Integer.class,p.f.room)).isZero();assertThat(p.count).hasValue(1);
    }
    @Test void modelCannotInventResumeTargetOutsideItsCandidateList() throws Exception{
        ambiguous();p.mode="classification";p.decisionText="{\"kind\":\"RESUME\",\"topic_id\":999999,\"requires_clarification\":false}";
        assertThat(route().kind()).isEqualTo(TopicDecision.Kind.AMBIGUOUS);assertThat(p.count).hasValue(1);
    }
    @Test void crashAfterClassificationClaimDoesNotAuthorizeSecondModelCall() throws Exception{
        ambiguous();AgentFixture.call(p.f.budget(),"beginClassification",new Class<?>[]{JobLease.class},p.f.lease);recover();
        assertThat(route().kind()).isEqualTo(TopicDecision.Kind.AMBIGUOUS);assertThat(p.count).hasValue(0);
    }
    @Test void providerFailureDuringClassificationRequiresChoiceInsteadOfHiddenRetry() throws Exception{
        ambiguous();p.status=503;assertThat(route().kind()).isEqualTo(TopicDecision.Kind.AMBIGUOUS);assertThat(p.count).hasValue(1);
    }

    @Test void explicitUserResolutionNeverClassifiesTheSameSourceAgain() throws Exception {
        ambiguous();long message=p.f.message.path("id").asLong();
        assertThat(p.f.app.context.getBean(TopicService.class).resolve(p.f.lease,message,TopicDecision.of("AMBIGUOUS",null,true))).isEmpty();
        var room=p.f.app.body(p.f.app.request("GET","/api/v1/conversations/"+p.f.room,null,p.f.customer));
        var choice=p.f.app.request("POST","/api/v1/conversations/"+p.f.room+"/topics/switch",Map.of("mode","NEW","pending_message_id",message,"expected_generation_epoch",room.path("generation_epoch").asLong()),p.f.customer);
        assertThat(choice.statusCode()).as(choice.body()).isEqualTo(200);
        var response=p.f.app.body(choice);long run=response.path("ai_reply").path("run_id").asLong();
        long job=p.f.app.db.queryForObject("SELECT job_id FROM ai_runs WHERE id=?",Long.class,run);p.f.claim(job);
        p.mode="classification";p.decisionText="{\"kind\":\"NEW_TOPIC\",\"topic_id\":null,\"requires_clarification\":false}";
        assertThat(route().kind()).isEqualTo(TopicDecision.Kind.CONTINUE);
        assertThat(p.count).hasValue(0);
        assertThat(p.f.app.db.queryForObject("SELECT model_requests_reserved FROM ai_runs WHERE id=?",Integer.class,run)).isZero();
    }
}
