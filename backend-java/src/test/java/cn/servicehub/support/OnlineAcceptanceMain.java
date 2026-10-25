package cn.servicehub.support;
import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
/** Explicit opt-in executable. It never runs in the offline Maven test lifecycle or CI. */
public final class OnlineAcceptanceMain {
 final MultiProcessFixture f;final VerificationReport report;final List<Map<String,Object>> questions=new ArrayList<>();
 OnlineAcceptanceMain(MultiProcessFixture f,VerificationReport report){this.f=f;this.report=report;}
 public static void main(String[] args)throws Exception{
  var report=new VerificationReport(args);
  try(var fixture=new MultiProcessFixture(true)){
   report.step("initialize-real-provider-system");fixture.initialize();new OnlineAcceptanceMain(fixture,report).execute();report.finish();
  }catch(Throwable error){report.failed(error);System.err.println("Online acceptance failed at "+report.step+" ("+error.getClass().getSimpleName()+")");System.exit(1);}
 }
 void execute()throws Exception{
  var p=f.app.context.getBean(ServiceHubProperties.class).model();assertThat(p.name()).isEqualTo("qwen-plus");assertThat(p.embeddingName()).isEqualTo("text-embedding-v4");assertThat(p.embeddingDimensions()).isEqualTo(1024);
  if(p.apiKey()==null||p.apiKey().isBlank()||p.embeddingApiKey()==null||p.embeddingApiKey().isBlank())throw new IllegalStateException("Both provider credentials must be configured locally");
  report.data.put("provider","bailian");report.data.put("configured_chat_model",p.name());report.data.put("configured_embedding_model",p.embeddingName());
  report.step("real-provider-contract-probes");probe(p);
  f.startWorker();var c=f.fresh(1);report.step("text_pdf_upload");
  byte[] pdf=policyPdf();var upload=f.upload(c,"Return and exchange policy","return-policy.pdf","application/pdf",pdf);f.require(upload,201);
  long document=upload.body().path("id").asLong(),version=upload.body().path("latest_version").path("id").asLong(),job=upload.body().path("latest_version").path("job_id").asLong();
  report.data.put("text_pdf_upload",Map.of("document_id",document,"version_id",version,"size_bytes",pdf.length));
  report.step("real_embedding_activation");completed(job,100);
  assertThat(f.count("SELECT embedding_dimensions FROM document_versions WHERE id=?",version)).isEqualTo(1024);
  var redis=f.app.context.getBean(StringRedisTemplate.class);var keys=redis.keys("shj:vector:*");assertThat(keys).isNotEmpty();int vectors=0;
  for(String key:keys)if(String.valueOf(redis.opsForHash().get(key,"tenant_id")).equals(String.valueOf(c.tenant()))){
   byte[] bytes=redis.execute((org.springframework.data.redis.core.RedisCallback<byte[]>)connection->connection.hashCommands().hGet(key.getBytes(StandardCharsets.UTF_8),"embedding".getBytes(StandardCharsets.UTF_8)));
   assertThat(bytes).hasSize(4096);var values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();double norm=0;
   while(values.hasRemaining()){float value=values.get();assertThat(Float.isFinite(value)).isTrue();norm+=value*value;}assertThat(norm).isPositive();vectors++;
  }
  assertThat(vectors).isPositive();report.data.put("real_embedding_activation",Map.of("vectors_inspected",vectors,"actual_dimensions",1024,"finite_float32",true));
  report.step("chinese_answer_with_current_citations");
  var rag=question(c,"根据知识库，收货后几天内可以申请退货？请说明出处。");
  assertThat(rag.path("body").asString()).containsAnyOf("七","7");assertThat(rag.path("body").asString().chars().anyMatch(ch->ch>=0x4e00&&ch<=0x9fff)).isTrue();assertThat(rag.path("citations").isEmpty()).isFalse();
  boolean citedCurrent=false;for(var cite:rag.path("citations"))if(cite.path("document_id").asLong()==document&&cite.path("version_id").asLong()==version&&cite.path("page").asInt()==1)citedCurrent=true;assertThat(citedCurrent).isTrue();
  report.data.put("chinese_answer_with_current_citations",Map.of("citations",rag.path("citations"),"answer",rag.path("body").asString()));
  report.step("lookup_order_tool");var order=question(c,"请查询订单 "+c.orderNumber()+" 当前的物流状态，必须以订单查询工具的结果为准。");
  assertThat(f.count("SELECT COUNT(*) FROM tool_calls WHERE ai_run_id=? AND tool_name='lookup_order' AND outcome='success'",order.path("ai_run_id").asLong())).isPositive();
  report.data.put("lookup_order_tool",Map.of("run_id",order.path("ai_run_id").asLong(),"answer",order.path("body").asString()));
  report.step("prepare_after_sale_tool");var draftAnswer=question(c,"订单 "+c.orderNumber()+" 的商品尺码偏小，请使用工具帮我准备换货申请，原因是尺码不合适。请创建待我确认的草稿。");
  long draft=draftAnswer.path("draft_id").asLong();assertThat(draft).isPositive();assertThat(f.count("SELECT COUNT(*) FROM tool_calls WHERE ai_run_id=? AND tool_name='prepare_after_sale' AND outcome='success'",draftAnswer.path("ai_run_id").asLong())).isPositive();
  assertThat(f.count("SELECT COUNT(*) FROM after_sale_tickets WHERE tenant_id=?",c.tenant())).isZero();report.data.put("prepare_after_sale_tool",Map.of("draft_id",draft,"formal_tickets_before_confirmation",0));
  report.step("explicit_customer_confirmation");var confirmed=f.request(0,"POST","/api/v1/after-sales/drafts/"+draft+"/confirm",Map.of("version",1,"idempotency_key","online-explicit-"+c.tenant()),c.customer());f.require(confirmed,200);
  assertThat(f.count("SELECT COUNT(*) FROM after_sale_tickets WHERE tenant_id=?",c.tenant())).isEqualTo(1);report.data.put("explicit_customer_confirmation",Map.of("ticket_id",confirmed.body().path("id").asLong()));
  report.step("human_handoff_claim_reply");var handed=f.request(0,"POST",f.room(c)+"/handoff",null,c.customer());f.require(handed,200);
  var claimed=f.request(1,"POST",f.room(c)+"/claim",Map.of("version",handed.body().path("version").asLong()),c.agents().getFirst());f.require(claimed,200);
  var reply=f.request(1,"POST",f.room(c)+"/messages",Map.of("client_message_id","online-human-reply","body","人工客服已接手，正在核对换货申请。"),c.agents().getFirst());f.require(reply,200);assertThat(reply.body().path("author_role").asString()).isEqualTo("agent");report.data.put("human_handoff_claim_reply",Map.of("message_id",reply.body().path("id").asLong()));
  for(String mode:List.of("new_message_interrupts_stream","explicit_stop_interrupts_stream","topic_a_b_a_discards_old_output")){report.step(mode);report.data.put(mode,interruption(mode));report.save();}
  report.step("fifteen_routing_samples");f.stopWorker();f.retireOutstanding();report.data.put("routing_samples",new OnlineRouting(f).run());
  report.data.put("requests",questions);report.data.put("api_pids",List.of(f.first.pid(),f.second.pid()));report.data.put("supplier_cancellation","Local HTTP/subscription cancellation and stale-result rejection verified; supplier-side computation or billing cancellation is not asserted.");
 }
 void probe(ServiceHubProperties.Model p)throws Exception{
  var embedding=post(p.embeddingBaseUrl()+"/embeddings",p.embeddingApiKey(),Map.of("model",p.embeddingName(),"input",List.of("售后退换货政策"),"dimensions",1024,"encoding_format","float"));
  assertThat(embedding.path("data").get(0).path("embedding").size()).isEqualTo(1024);
  for(var value:embedding.path("data").get(0).path("embedding"))assertThat(Double.isFinite(value.asDouble())).isTrue();
  var chat=post(p.baseUrl()+"/chat/completions",p.apiKey(),Map.of("model",p.name(),"messages",List.of(Map.of("role","user","content","请简短确认模型服务连接正常。")),"max_tokens",128,"stream",false));
  assertThat(chat.path("choices").get(0).path("message").path("content").asString()).isNotBlank();
  report.data.put("provider_response_model",Map.of("chat",chat.path("model").asString(),"embedding",embedding.path("model").asString()));
  var usage=new LinkedHashMap<String,Object>();usage.put("chat",chat.hasNonNull("usage")?chat.get("usage"):null);usage.put("embedding",embedding.hasNonNull("usage")?embedding.get("usage"):null);report.data.put("probe_actual_usage_or_null",usage);
 }
 JsonNode post(String url,String key,Map<String,Object> body)throws Exception{
  var request=HttpRequest.newBuilder(URI.create(url.replace("//embeddings","/embeddings"))).timeout(Duration.ofSeconds(45)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(f.app.json.writeValueAsString(body),StandardCharsets.UTF_8)).build();
  var response=f.app.http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));assertThat(response.statusCode()).as("Actual provider probe HTTP status").isEqualTo(200);return f.app.json.readTree(response.body());
 }
 JsonNode question(MultiProcessFixture.Case c,String text)throws Exception{
  long start=System.nanoTime();var sent=f.send(c,"online-"+UUID.randomUUID(),text,0);f.require(sent,200);long job=sent.body().path("trigger_job_id").asLong(),run=sent.body().path("trigger_run_id").asLong();completed(job,130);
  var messages=f.request(1,"GET",f.room(c)+"/messages",null,c.customer());f.require(messages,200);JsonNode result=null;
  for(var message:messages.body().path("items"))if(message.path("ai_run_id").asLong()==run)result=message;assertThat(result).as("Actual persisted answer").isNotNull();
  var observation=new LinkedHashMap<String,Object>();observation.put("run_id",run);observation.put("queue_ms",f.count("SELECT TIMESTAMPDIFF(MICROSECOND,accepted_at,started_at) DIV 1000 FROM jobs WHERE id=?",job));observation.put("completion_ms",(System.nanoTime()-start)/1e6);observation.put("first_delta_ms",null);
  String usage=f.app.db.queryForObject("SELECT CAST("+Character.toString(96)+"usage"+Character.toString(96)+" AS CHAR) FROM ai_runs WHERE id=?",String.class,run);observation.put("actual_usage_or_null",usage==null?null:f.app.json.readTree(usage));questions.add(observation);report.data.put("requests",questions);report.save();return result;
 }
 void completed(long job,int seconds)throws Exception{
  f.until(()->!Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(f.state(job)),seconds,"real model task reaches durable terminal state");
  if(!f.state(job).equals("SUCCEEDED")){report.data.put("last_job_diagnostic",f.app.db.queryForMap("SELECT state,error_code,attempts FROM jobs WHERE id=?",job));report.save();throw new AssertionError("Real provider task did not succeed");}
 }
 Map<String,Object> interruption(String mode)throws Exception{
  var c=f.fresh(0);long originalTopic=f.count("SELECT active_topic_id FROM conversations WHERE id=?",c.room());
  try(var wire=new ContractSocket(f.app,c.room(),0,c.customer().cookie(),"http://localhost:8081")){
   long start=System.nanoTime();var sent=f.send(c,"online-interrupt","请面向电商售后客服详细介绍退货、换货、维修各自的适用情形和处理流程，用较完整的段落说明。",0);f.require(sent,200);long run=sent.body().path("trigger_run_id").asLong(),epoch=sent.body().path("accepted_generation_epoch").asLong();boolean delta=false;
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);
   while(System.nanoTime()<end){var event=wire.next(200);if(event!=null&&event.path("kind").asString().equals("ai.delta")&&event.path("run_id").asLong()==run&&!event.path("payload").path("text").asString().isEmpty()){delta=true;break;}if(!Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(f.state(sent.body().path("trigger_job_id").asLong())))break;}
   assertThat(delta).as("A real supplier stream must deliver a partial before interruption").isTrue();double firstDelta=(System.nanoTime()-start)/1e6;long cancelling=System.nanoTime();double cancelMs;
   if(mode.equals("new_message_interrupts_stream")){var next=f.send(c,"online-replacement","请简短说明如何联系人工客服。",1);f.require(next,200);cancelMs=(System.nanoTime()-cancelling)/1e6;completed(next.body().path("trigger_job_id").asLong(),130);}
   else if(mode.equals("explicit_stop_interrupts_stream")){f.require(f.request(1,"POST",f.room(c)+"/interrupt",Map.of("expected_generation_epoch",epoch),c.customer()),200);cancelMs=(System.nanoTime()-cancelling)/1e6;}
   else{
    var b=f.request(1,"POST",f.room(c)+"/topics/switch",Map.of("mode","NEW","expected_generation_epoch",epoch),c.customer());f.require(b,200);
    var a=f.request(0,"POST",f.room(c)+"/topics/switch",Map.of("mode","RESUME","topic_id",originalTopic,"expected_generation_epoch",b.body().path("generation_epoch").asLong()),c.customer());f.require(a,200);assertThat(a.body().path("generation_epoch").asLong()).isGreaterThan(epoch);assertThat(a.body().path("active_topic_id").asLong()).isEqualTo(originalTopic);cancelMs=(System.nanoTime()-cancelling)/1e6;
   }
   Thread.sleep(500);long stale=f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id=?",run);assertThat(stale).isZero();assertThat(f.state(sent.body().path("trigger_job_id").asLong())).isIn("CANCELLED","SUPERSEDED");
   return Map.of("first_delta_ms",firstDelta,"local_cancel_ms",cancelMs,"persisted_old_result_count",stale,"old_run_id",run,"old_epoch",epoch);
  }
 }
 byte[] policyPdf()throws Exception{
  try(var document=new PDDocument();var out=new ByteArrayOutputStream()){
   var page=new PDPage();document.addPage(page);try(var text=new PDPageContentStream(document,page)){
    text.beginText();text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);text.newLineAtOffset(50,730);
    for(String line:List.of("Return policy: request a return within seven days of delivery.","Products must be complete and undamaged.","For size problems, customers may request an exchange.","A formal after-sale ticket requires explicit customer confirmation.")){text.showText(line);text.newLineAtOffset(0,-24);}text.endText();
   }document.save(out);return out.toByteArray();
  }
 }
}
