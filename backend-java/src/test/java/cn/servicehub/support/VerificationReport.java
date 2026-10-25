package cn.servicehub.support;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;
final class VerificationReport {
 final Path path;final Map<String,Object> data=new LinkedHashMap<>();final JsonMapper json=JsonMapper.builder().build();String step="initialize";
 VerificationReport(String[] args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("One private report path is required");
  Path workspace=Path.of(Objects.requireNonNull(System.getenv("SERVICEHUB_WORKSPACE_ROOT"))).toAbsolutePath().normalize();
  path=Path.of(args[0]).toAbsolutePath().normalize();if(!path.startsWith(workspace.resolve(".cache/java-verification")))throw new IllegalArgumentException("Report must stay in project verification directory");
  Files.createDirectories(path.getParent());data.put("started_at",Instant.now().toString());data.put("passed",false);data.put("java",System.getProperty("java.version"));data.put("os",System.getProperty("os.name"));data.put("cpu_count",Runtime.getRuntime().availableProcessors());
  var git=new ProcessBuilder(System.getProperty("os.name").startsWith("Windows")?"C:/Program Files/Git/bin/git.exe":"git","rev-parse","HEAD").directory(PythonReference.worktree().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  if(!git.waitFor(5,TimeUnit.SECONDS)||git.exitValue()!=0)throw new IllegalStateException("Source identity unavailable");data.put("source_commit",new String(git.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip());
  data.putAll(SourceIdentity.current());
 }
 void step(String value)throws Exception{step=value;data.put("current_step",value);save();}
 void save()throws Exception{Files.writeString(path,json.writeValueAsString(data),StandardCharsets.UTF_8);}
 void finish()throws Exception{data.put("passed",true);data.put("finished_at",Instant.now().toString());save();}
 void failed(Throwable error)throws Exception{data.put("passed",false);data.put("failed_step",step);data.put("failure_type",error.getClass().getName());data.put("finished_at",Instant.now().toString());save();}
}
