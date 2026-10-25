package cn.servicehub.support;
import cn.servicehub.config.ServiceHubProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/** Owns a private legacy API process and separate real dependencies; never reads a real provider key. */
public final class PythonReference implements AutoCloseable {
 private final HttpIntegrationApp app;private final DependencyContainers dependencies=new DependencyContainers();
 private final ExecutorService reading=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("python-reference-read").factory());
 private final AtomicInteger ids=new AtomicInteger();private Process process;private BufferedWriter input;private BufferedReader output;private Path config;
 public PythonReference(HttpIntegrationApp app){
  this.app=app;
  try{
   Path workspace=Path.of(Objects.requireNonNull(System.getenv("SERVICEHUB_WORKSPACE_ROOT"))).toAbsolutePath().normalize();
   Path root=workspace.resolve(".cache/java-verification/reference-"+UUID.randomUUID());Files.createDirectories(root);
   Path worktree=worktree();Path python=workspace.resolve(".venv/Scripts/python.exe");if(!Files.isRegularFile(python))python=workspace.resolve(".venv/bin/python");
   if(!Files.isRegularFile(python))throw new IllegalStateException("Legacy Python environment is required; no baseline comparison may be skipped");
   dependencies.start();awaitDatabase();
   var p=app.context.getBean(ServiceHubProperties.class);var model=p.model();
   var settings=new LinkedHashMap<String,Object>();
   settings.put("environment","test");settings.put("project_root",root.resolve("data").toString());settings.put("upload_dir",".data/uploads");
   settings.put("database_url","mysql+asyncmy://"+dependencies.username()+":"+dependencies.password()+"@127.0.0.1:"+dependencies.mysql.getMappedPort(3306)+"/servicehub_java_it");
   settings.put("redis_url","redis://:"+dependencies.password()+"@127.0.0.1:"+dependencies.redis.getMappedPort(6379)+"/0");
   settings.put("allowed_origins",p.allowedOrigins());settings.put("model_mode","online");settings.put("model_name",model.name());
   settings.put("model_base_url",present(model.baseUrl())?"http://127.0.0.1:9/v1":"");settings.put("model_api_key",present(model.apiKey())?"synthetic-configured-provider":"");
   settings.put("embedding_model",model.embeddingName());settings.put("embedding_dimensions",model.embeddingDimensions());
   settings.put("embedding_base_url",present(model.embeddingBaseUrl())?"http://127.0.0.1:9/v1":"");settings.put("embedding_api_key",present(model.embeddingApiKey())?"synthetic-configured-provider":"");
   settings.put("global_model_slots",p.limits().totalModelSlots());settings.put("max_output_tokens",p.limits().maxOutputTokens());settings.put("max_upload_bytes",p.limits().maxUploadBytes());
   String owner=UUID.randomUUID().toString();config=root.resolve("reference-settings.json");
   Files.writeString(config,app.json.writeValueAsString(Map.of("workspace",workspace.toString(),"owner",owner,"settings",settings)),StandardCharsets.UTF_8);
   var builder=new ProcessBuilder(python.toString(),"-B",worktree.resolve("scripts/java-contract-reference.py").toString(),config.toString()).directory(worktree.toFile());
   builder.environment().keySet().removeIf(key->key.startsWith("SERVICEHUB_MODEL_")||key.startsWith("SERVICEHUB_EMBEDDING_")||Set.of("OPENAI_API_KEY","DASHSCOPE_API_KEY").contains(key));
   builder.environment().put("PYTHONPATH",worktree.resolve("backend/src").toString());builder.environment().put("PYTHONUTF8","1");builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
   builder.redirectError(root.resolve("reference-stderr.log").toFile());process=builder.start();
   input=process.outputWriter(StandardCharsets.UTF_8);output=process.inputReader(StandardCharsets.UTF_8);
   String hash=new String(Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/legacy-password.txt")).readAllBytes(),StandardCharsets.UTF_8).strip();
   var initialized=control(Map.of("op","init","password_hash",hash));if(!initialized.path("initialized").asBoolean())throw new IllegalStateException("Reference schema initialization failed");
  }catch(Exception e){close();throw new IllegalStateException("Isolated Python reference initialization failed",e);}
 }
 public Path configPath(){return config;}
 public JsonNode request(String method,String path,Map<String,Object> body,Map<String,String> headers){
  var command=new LinkedHashMap<String,Object>();command.put("kind","request");command.put("method",method);command.put("path",path);command.put("headers",headers);if(body!=null)command.put("body",body);return exchange(command);
 }
 public JsonNode raw(String method,String path,String body,Map<String,String> headers){
  var command=new LinkedHashMap<String,Object>();command.put("kind","request");command.put("method",method);command.put("path",path);command.put("headers",headers);if(body!=null)command.put("raw_body",body);return exchange(command);
 }
 public JsonNode multipart(String method,String path,Map<String,String> fields,List<Map<String,Object>> parts,Map<String,String> headers){
  return exchange(Map.of("kind","request","method",method,"path",path,"headers",headers,"fields",fields,"parts",parts));
 }
 public JsonNode sql(String sql,Map<String,Object> parameters){return control(Map.of("op","sql","statements",List.of(Map.of("sql",sql,"params",parameters))));}
 public JsonNode sqlBatch(List<Map<String,Object>> statements){return control(Map.of("op","sql","statements",statements));}
 private JsonNode control(Map<String,Object> command){
  var reply=exchange(Map.of("kind","control","command",command));if(reply.path("status").asInt()!=200)throw new IllegalStateException("Reference fixture control failed");
  return body(reply);
 }
 public JsonNode body(JsonNode response){return app.json.readTree(Base64.getDecoder().decode(response.path("body_base64").asString()));}
 public synchronized JsonNode exchange(Map<String,Object> command){
  try{
   if(process==null||!process.isAlive())throw new IllegalStateException("Reference child exited");
   var envelope=new LinkedHashMap<>(command);int id=ids.incrementAndGet();envelope.put("id",id);
   input.write(app.json.writeValueAsString(envelope));input.newLine();input.flush();
   Future<String> response=reading.submit(output::readLine);String line;
   try{line=response.get(30,TimeUnit.SECONDS);}catch(Exception e){response.cancel(true);throw e;}
   if(line==null)throw new IllegalStateException("Reference child did not return a response");var parsed=app.json.readTree(line);
   if(parsed.path("id").asInt()!=id||parsed.has("bridge_error"))throw new IllegalStateException("Reference bridge protocol failed");
   return parsed;
  }catch(Exception e){throw new IllegalStateException("Reference exchange failed",e);}
 }
 private boolean present(String value){return value!=null&&!value.isBlank();}
 private void awaitDatabase() throws Exception{
  long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
  while(System.nanoTime()<deadline){
   try(var connection=DriverManager.getConnection(dependencies.jdbcUrl(),dependencies.username(),dependencies.password())){if(connection.isValid(2))return;}catch(java.sql.SQLException ignored){}
   Thread.sleep(200);
  }
  throw new IllegalStateException("Reference database readiness timed out");
 }
 public static Path worktree(){
  Path current=Path.of("").toAbsolutePath();while(current!=null){if(Files.isRegularFile(current.resolve("backend-java/pom.xml")))return current;current=current.getParent();}
  throw new IllegalStateException("Cannot locate the current worktree");
 }
 @Override public void close(){
  if(process!=null){
   if(process.isAlive())try{exchange(Map.of("kind","close"));}catch(RuntimeException ignored){}
   try{if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}}
   catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}
  }
  reading.shutdownNow();try{if(input!=null)input.close();if(output!=null)output.close();}catch(IOException ignored){}
  dependencies.close();
 }
}
