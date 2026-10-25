package cn.servicehub.knowledge;
import cn.servicehub.support.HttpIntegrationApp;
import cn.servicehub.jobs.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
final class KnowledgeFixture implements AutoCloseable {
    final HttpIntegrationApp app=new HttpIntegrationApp();
    final Path root=Path.of(System.getenv("SERVICEHUB_WORKSPACE_ROOT")).resolve(".cache/java-fixtures/knowledge-"+UUID.randomUUID());
    HttpServer model;ExecutorService modelThreads;HttpIntegrationApp.Login admin,agent,customer,other;
    final AtomicInteger dimension=new AtomicInteger(1024),status=new AtomicInteger(200),requests=new AtomicInteger();
    final List<JsonNode> received=new CopyOnWriteArrayList<>();final List<ExecutionPermit> permits=new ArrayList<>();
    volatile CountDownLatch entered,release;volatile boolean totalOnly=true;volatile String responseMode="valid";
    void start() throws Exception{start(Map.of());}
    void start(Map<String,String> overrides) throws Exception{
        model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);modelThreads=Executors.newVirtualThreadPerTaskExecutor();model.setExecutor(modelThreads);
        model.createContext("/v1/embeddings",exchange->{
            try{
                var input=app.json.readTree(exchange.getRequestBody().readAllBytes());received.add(input);requests.incrementAndGet();
                var wait=release;var ready=entered;if(ready!=null)ready.countDown();if(wait!=null&&!wait.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test gate timeout");
                int code=status.get();var data=new ArrayList<Map<String,Object>>();
                for(int i=0;i<input.path("input").size();i++){
                    var vector=new ArrayList<Double>();for(int j=0;j<dimension.get();j++)vector.add(j==0?(responseMode.equals("nonfinite")?Double.NaN:1.0):0.0);
                    data.add(Map.of("object","embedding","index",i,"embedding",vector));
                }
                var usage=totalOnly?Map.of("total_tokens",23):Map.of("total_tokens",23,"prompt_tokens",23);
                Object response=code==200?Map.of("object","list","model","text-embedding-v4","data",data,"usage",usage):Map.of("error",Map.of("message","Synthetic provider failure","type","server_error","code","synthetic"));
                if(code==200&&responseMode.equals("missing_data"))response=Map.of("object","list","model","text-embedding-v4","usage",usage);
                byte[] body=app.json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);
            }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
        });model.start();
        var options=new HashMap<>(Map.of("servicehub.workspace-root",root.toString(),"servicehub.model.embedding-base-url","http://127.0.0.1:"+model.getAddress().getPort()+"/v1","servicehub.model.embedding-api-key","synthetic-provider-key","servicehub.model.embedding-name","text-embedding-v4"));options.putAll(overrides);app.start(options);
    }
    void reset() throws Exception{
        app.context.getBean(VectorIndex.class).close();app.resetAuth();dimension.set(1024);status.set(200);requests.set(0);received.clear();entered=null;release=null;totalOnly=true;responseMode="valid";
        admin=app.login(2001);agent=app.login(2002);customer=app.login(2003);other=app.login(5001);
    }
    HttpResponse<String> upload(String path,String name,String media,byte[] bytes,String title,HttpIntegrationApp.Login login) throws Exception{
        String boundary="Boundary"+UUID.randomUUID().toString().replace("-","");
        var body=new ByteArrayOutputStream();
        if(title!=null)body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n"+title+"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+media+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.context.getEnvironment().getProperty("local.server.port")+path))
            .timeout(Duration.ofSeconds(15)).header("Cookie",login.cookie()).header("x-csrf-token",login.csrf()).header("Origin","http://localhost:8081")
            .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return app.http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    JsonNode upload(String text) throws Exception{
        var response=upload("/api/v1/knowledge/documents","synthetic.txt","text/plain",text.getBytes(StandardCharsets.UTF_8),"Synthetic policy",admin);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return app.body(response);
    }
    JsonNode version(long id,String text) throws Exception{
        var response=upload("/api/v1/knowledge/documents/"+id+"/versions","synthetic.md","text/markdown",text.getBytes(StandardCharsets.UTF_8),null,admin);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return app.body(response);
    }
    JobLease claim(JsonNode document){
        String owner="ingest-"+UUID.randomUUID();var permit=app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.BACKGROUND,owner).orElseThrow();permits.add(permit);
        return app.context.getBean(JobCoordinator.class).claim(document.path("latest_version").path("job_id").asLong(),owner).orElseThrow();
    }
    void ingest(JobLease lease){PrivateFileStoreTest.call(app.requiredBean("cn.servicehub.knowledge.IngestionHandler"),"handle",new Class<?>[]{JobLease.class},lease);}
    JsonNode detail(long id) throws Exception{var r=app.request("GET","/api/v1/knowledge/documents/"+id,null,admin);assertThat(r.statusCode()).isEqualTo(200);return app.body(r);}
    @Override public void close(){if(release!=null)release.countDown();permits.forEach(ExecutionPermit::close);app.close();if(model!=null)model.stop(0);if(modelThreads!=null)modelThreads.shutdownNow();}
}
