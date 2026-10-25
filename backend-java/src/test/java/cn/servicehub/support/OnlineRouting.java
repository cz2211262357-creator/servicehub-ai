package cn.servicehub.support;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** Fifteen explicit development samples; successful real classifier responses are counted separately. */
final class OnlineRouting {
 final MultiProcessFixture f;
 OnlineRouting(MultiProcessFixture fixture){f=fixture;}
 List<Map<String,Object>> run()throws Exception{
  var sample=f.app.json.readTree(Files.readString(PythonReference.worktree().resolve("backend-java/src/test/resources/scenarios/topic-routing.json"),StandardCharsets.UTF_8));
  var results=new ArrayList<Map<String,Object>>();
  for(var row:sample.path("cases")){
   var c=f.fresh(0);long target=f.count("SELECT active_topic_id FROM conversations WHERE id=?",c.room());
   var newTopic=f.request(0,"POST",f.room(c)+"/topics/switch",Map.of("mode","NEW","expected_generation_epoch",0),c.customer());f.require(newTopic,200);
   f.app.db.update("UPDATE conversation_topics SET title='订单退换货',summary='订单 ORD-ONLINE-001 的退货申请' WHERE id=?",target);
   f.app.db.update("UPDATE conversation_topics SET title='维修咨询',summary='商品维修条件和材料' WHERE id=?",newTopic.body().path("active_topic_id").asLong());
   String text=row.path("text").asString().replace("{resume_topic_id}",String.valueOf(target));var sent=f.send(c,"route-"+row.path("id").asString(),text,1);f.require(sent,200);
   var lease=f.claim(sent);long before=f.count("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",lease.jobId());double completedBefore=completedProviderResponses();
   var actual=f.app.context.getBean(TopicRouter.class).routeForJob(lease);long after=f.count("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",lease.jobId());
   var cached=f.app.context.getBean(TopicRouter.class).routeForJob(lease);assertThat(cached).isEqualTo(actual);assertThat(f.count("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",lease.jobId())).isEqualTo(after);
   long expectedCalls=row.path("classification_expected").asBoolean()?1:0;assertThat(after-before).isEqualTo(expectedCalls);assertThat(completedProviderResponses()-completedBefore).as("Classifier must complete real provider requests, without accepting failure fallback as a successful model sample").isEqualTo(expectedCalls);
   var result=new LinkedHashMap<String,Object>();result.put("id",row.path("id").asString());result.put("text",text);result.put("routing_expected",row.path("expected").asString());result.put("routing_actual",actual.kind().name());result.put("routing_requires_clarification",actual.requiresClarification());result.put("classification_request_count",after-before);
   boolean passed=actual.kind().name().equals(row.path("expected").asString())&&actual.requiresClarification()==row.path("requires_clarification").asBoolean()&&(actual.kind()!=TopicDecision.Kind.RESUME||Objects.equals(actual.topicId(),target));result.put("passed",passed);results.add(result);
   f.app.context.getBean(JobCoordinator.class).finish(lease,"CANCELLED",Map.of("reason","routing_sample_only"));f.permits.forEach(ExecutionPermit::close);f.permits.clear();
   Files.writeString(f.root.resolve("routing-samples.partial.json"),f.app.json.writeValueAsString(results),StandardCharsets.UTF_8);
  }
  assertThat(results).hasSize(15);assertThat(results.stream().allMatch(row->Boolean.TRUE.equals(row.get("passed")))).as("All measured development routing samples must match their stated outcome; no general intent-accuracy claim").isTrue();return results;
 }
 double completedProviderResponses(){
  var registry=f.app.context.getBean(MeterRegistry.class);double total=0;
  for(String status:List.of("known","unknown")){var counter=registry.find("servicehub.model.usage").tags("kind","chat","status",status).counter();if(counter!=null)total+=counter.count();}
  return total;
 }
}
