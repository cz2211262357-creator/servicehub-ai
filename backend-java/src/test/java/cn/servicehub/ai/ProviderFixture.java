package cn.servicehub.ai;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

final class ProviderFixture implements AutoCloseable {
    final AgentFixture f=new AgentFixture();HttpServer server;ExecutorService threads;
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();final AtomicInteger count=new AtomicInteger(),embeddingCount=new AtomicInteger();
    volatile String mode="text",usageMode="missing",toolName="lookup_order",arguments="{\"order_number\":\"ORD-A-001\"}";
    volatile List<JsonNode> replayFrames=List.of();volatile String decisionText="";volatile int status=200;volatile CountDownLatch entered,release;
    void start() throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);threads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(threads);
        server.createContext("/v1/chat/completions",this::respond);
        server.createContext("/v1/embeddings",exchange->{
            try{
                var request=f.app.json.readTree(exchange.getRequestBody().readAllBytes());embeddingCount.incrementAndGet();var data=new ArrayList<Map<String,Object>>();
                for(int i=0;i<request.path("input").size();i++){var vector=new ArrayList<Double>();for(int j=0;j<1024;j++)vector.add(j==0?1.0:0.0);data.add(Map.of("object","embedding","index",i,"embedding",vector));}
                byte[] body=f.app.json.writeValueAsBytes(Map.of("object","list","model","text-embedding-v4","data",data,"usage",Map.of("total_tokens",23)));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }finally{exchange.close();}
        });server.start();
        String root=Path.of(System.getenv("SERVICEHUB_WORKSPACE_ROOT")).resolve(".cache/java-fixtures/agent-"+UUID.randomUUID()).toString();
        f.start(Map.of("servicehub.workspace-root",root,"servicehub.model.base-url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","servicehub.model.api-key","synthetic-provider-key","servicehub.model.name","qwen-plus","servicehub.model.embedding-base-url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","servicehub.model.embedding-api-key","synthetic-provider-key"));
    }
    void reset() throws Exception{f.reset();mode="text";usageMode="missing";toolName="lookup_order";arguments="{\"order_number\":\"ORD-A-001\"}";status=200;decisionText="";replayFrames=List.of();entered=null;release=null;requests.clear();count.set(0);embeddingCount.set(0);}
    private void respond(HttpExchange exchange) throws IOException{
        try{
            var request=f.app.json.readTree(exchange.getRequestBody().readAllBytes());requests.add(request);count.incrementAndGet();
            if(status!=200){byte[] body=f.app.json.writeValueAsBytes(Map.of("error",Map.of("message","Synthetic error","type","server_error","code","synthetic")));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);return;}
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            if(mode.equals("replay")){
                for(var captured:replayFrames)send(exchange,captured);
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();return;
            }
            boolean hasTool=false;for(var message:request.path("messages"))if(message.path("role").asString().equals("tool"))hasTool=true;
            if(mode.equals("tool")&&!hasTool){
                int split=Math.max(1,arguments.length()/2);
                frame(exchange,Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","call-1","type","function","function",Map.of("name",toolName,"arguments",arguments.substring(0,split))))),null);
                pause();
                frame(exchange,Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("arguments",arguments.substring(split))))),"tool_calls");
            }else{
                String output=mode.equals("classification")?decisionText:"Synthetic provider text";int midpoint=Math.max(1,output.length()/2);
                frame(exchange,Map.of("role","assistant","content",mode.equals("classification")?output.substring(0,midpoint):"Synthetic " ),null);pause();
                if(mode.equals("incomplete"))return;
                frame(exchange,Map.of("content",mode.equals("classification")?output.substring(midpoint):"provider text"),"stop");
            }
            if(!usageMode.equals("missing")){
                Map<String,Object> usage=usageMode.equals("total_only")?Map.of("total_tokens",23):usageMode.equals("zero")?Map.of("prompt_tokens",0,"completion_tokens",0,"total_tokens",0):Map.of("prompt_tokens",13,"completion_tokens",10,"total_tokens",23);
                send(exchange,Map.of("id","synthetic","object","chat.completion.chunk","created",1,"model","qwen-plus","choices",List.of(),"usage",usage));
            }
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
        }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
    }
    private void pause() throws InterruptedException{var ready=entered;var wait=release;if(ready!=null)ready.countDown();if(wait!=null&&!wait.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Provider test gate timeout");}
    private void frame(HttpExchange exchange,Map<String,Object> delta,String finish) throws IOException{
        var choice=new LinkedHashMap<String,Object>();choice.put("index",0);choice.put("delta",delta);choice.put("finish_reason",finish);
        send(exchange,Map.of("id","synthetic","object","chat.completion.chunk","created",1,"model","qwen-plus","choices",List.of(choice)));
    }
    private void send(HttpExchange exchange,Object value) throws IOException{exchange.getResponseBody().write(("data: "+f.app.json.writeValueAsString(value)+"\n\n").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();}
    Object modelRequest(List<Map<String,Object>> schemas){
        var snapshot=f.snapshot();
        try{return Class.forName("cn.servicehub.ai.ModelGateway$ModelRequest").getConstructor(List.class,List.class,snapshot.getClass()).newInstance(List.of(Map.of("role","user","content","查询订单 ORD-A-001")),schemas,snapshot);}
        catch(ReflectiveOperationException e){throw new AssertionError("Missing model request",e);}
    }
    Object stream(java.util.function.Consumer<Object> sink){
        Object request=modelRequest(List.of());
        return AgentFixture.call(f.app.requiredBean("cn.servicehub.ai.SpringAiModelGateway"),"stream",new Class<?>[]{request.getClass(),java.util.function.Consumer.class,CancellationGuard.class},request,sink,f.guard());
    }
    @Override public void close(){if(release!=null)release.countDown();f.close();if(server!=null)server.stop(0);if(threads!=null)threads.shutdownNow();}
}
