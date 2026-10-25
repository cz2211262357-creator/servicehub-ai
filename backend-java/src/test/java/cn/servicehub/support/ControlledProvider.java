package cn.servicehub.support;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
/** Offline failure injection only: every gate records actual inbound network requests. */
public final class ControlledProvider implements AutoCloseable {
 public static final class Gate {
  public final String tag;public final boolean embedding;public final CountDownLatch entered,release=new CountDownLatch(1);public final AtomicInteger hits=new AtomicInteger();
  public final AtomicLong firstHitNanos=new AtomicLong();public final List<Long> hitTimes=new CopyOnWriteArrayList<>();public volatile boolean background;public volatile String tool,arguments;public volatile int status=200,blockSeconds=40;
  Gate(String tag,boolean embedding,int count){this.tag=tag;this.embedding=embedding;this.background=embedding;entered=new CountDownLatch(count);}
  public void await()throws InterruptedException{if(!entered.await(15,TimeUnit.SECONDS))throw new AssertionError("Actual provider gate was not reached: "+tag);}
  public void open(){release.countDown();}
 }
 private final JsonMapper json=JsonMapper.builder().build();private final HttpServer server;private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
 private final List<Gate> gates=new CopyOnWriteArrayList<>();public final AtomicInteger requests=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger(),backgroundActive=new AtomicInteger(),backgroundPeak=new AtomicInteger();
 public final Map<String,AtomicInteger> attempts=new ConcurrentHashMap<>();public volatile int defaultChatStatus=200;
 public ControlledProvider(){
  try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/v1/embeddings",e->handle(e,true));server.createContext("/v1/chat/completions",e->handle(e,false));server.start();}catch(IOException e){throw new UncheckedIOException(e);}
 }
 public void openAllGates(){gates.forEach(Gate::open);}
 public String base(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
 public Gate gate(String tag,boolean embedding,int count){var gate=new Gate(tag,embedding,count);gates.add(gate);return gate;}
 private void handle(HttpExchange exchange,boolean embedding)throws IOException{
  int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);boolean background=false;
  try{
   var request=json.readTree(exchange.getRequestBody().readAllBytes());String text=request.toString();
   if(!embedding)for(var message:request.path("messages"))if(message.path("role").asString().equals("user"))text=message.path("content").asString();
   String selectedText=text;requests.incrementAndGet();Gate gate=gates.stream().filter(g->g.embedding==embedding&&selectedText.contains(g.tag)).findFirst().orElse(null);
   int gateHit=0;if(gate!=null){gateHit=gate.hits.incrementAndGet();gate.firstHitNanos.compareAndSet(0,System.nanoTime());gate.hitTimes.add(System.nanoTime());attempts.computeIfAbsent(gate.tag,k->new AtomicInteger()).incrementAndGet();background=embedding&&gate.background;if(background)backgroundPeak.accumulateAndGet(backgroundActive.incrementAndGet(),Math::max);gate.entered.countDown();if(!gate.release.await(gate.blockSeconds,TimeUnit.SECONDS))throw new IOException("Synthetic gate timed out");}
   int status=gate==null?(embedding?200:defaultChatStatus):gate.status;
   if(status!=200){send(exchange,status,Map.of("error",Map.of("type","server_error","code","synthetic_failure","message","Synthetic provider failure")));return;}
   if(embedding){
    int size=request.path("input").isArray()?request.path("input").size():1;var data=new ArrayList<Map<String,Object>>();
    var vector=new ArrayList<Double>();for(int i=0;i<1024;i++)vector.add(i==0?1.0:0.0);
    for(int i=0;i<size;i++)data.add(Map.of("object","embedding","index",i,"embedding",vector));
    send(exchange,200,Map.of("object","list","model","text-embedding-v4","data",data,"usage",Map.of("total_tokens",7)));return;
   }
   exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
   var output=exchange.getResponseBody();
   boolean toolResponse=false;
   if(gate!=null&&gate.tool!=null)for(var message:request.path("messages"))
    if("tool".equals(message.path("role").asString())&&("call_"+gate.tag).equals(message.path("tool_call_id").asString()))toolResponse=true;
   boolean requestTool=gate!=null&&gate.tool!=null&&!toolResponse;
   Map<String,Object> delta;
   if(requestTool)delta=Map.of("tool_calls",List.of(Map.of("index",0,"id","call_"+gate.tag,"type","function","function",Map.of("name",gate.tool,"arguments",gate.arguments))));
   else delta=Map.of("content","系统测试回答"+(gate==null?"":" ["+gate.tag+" #"+gateHit+"]"));
   frame(output,Map.of("id","chatcmpl-"+UUID.randomUUID(),"object","chat.completion.chunk","created",1,"model","qwen-plus","choices",List.of(Map.of("index",0,"delta",delta,"finish_reason",requestTool?"tool_calls":"stop"))));
   frame(output,Map.of("id","usage","object","chat.completion.chunk","created",1,"model","qwen-plus","choices",List.of(),"usage",Map.of("prompt_tokens",11,"completion_tokens",5,"total_tokens",16)));
   output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));output.flush();
  }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException ignored){}finally{if(background)backgroundActive.decrementAndGet();active.decrementAndGet();exchange.close();}
 }
 private void frame(OutputStream output,Object value)throws IOException{output.write(("data: "+json.writeValueAsString(value)+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();}
 private void send(HttpExchange exchange,int code,Object value)throws IOException{byte[] data=json.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,data.length);exchange.getResponseBody().write(data);}
 @Override public void close(){gates.forEach(Gate::open);server.stop(0);executor.shutdownNow();}
}
