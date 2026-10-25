package cn.servicehub.support;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
/** Runs the real wrapper from three cwd values and inspects a real Docker-built image. */
final class BuildScenarios {
 final Path workspace,root,source=PythonReference.worktree();final JsonMapper json=JsonMapper.builder().build();
 final List<List<String>> mutations=new ArrayList<>();final Set<String> ownedContainers=new HashSet<>();
 BuildScenarios(Path workspace,Path root){this.workspace=workspace;this.root=root;}
 Map<String,Object> run(String scenario)throws Exception{Files.createDirectories(root);return scenario.equals("maven-boundary")?maven():docker();}
 Map<String,Object> maven()throws Exception{
  String before=command(List.of(git(),"status","--porcelain"),source,30);String branch=command(List.of(git(),"rev-parse","HEAD"),source,30);
  var rows=new ArrayList<Map<String,Object>>();int external=0;int number=0;
  for(Path cwd:List.of(workspace,source,source.resolve("backend-java"))){
   Path output=root.resolve("maven-probe-"+number++ +".json");List<String> args;
   if(windows()){
    Path script=root.resolve("wrapper-probe.ps1");if(!Files.exists(script))Files.writeString(script,windowsProbe(),StandardCharsets.UTF_8);
    args=List.of(System.getenv().getOrDefault("SERVICEHUB_POWERSHELL","pwsh.exe"),"-NoProfile","-ExecutionPolicy","Bypass","-File",script.toString(),"-WorkspaceRoot",workspace.toString(),"-SourceRoot",source.toString(),"-Report",output.toString());
   }else args=List.of("bash",source.resolve("scripts/java-wrapper-probe.sh").toString(),workspace.toString(),source.toString(),output.toString());
   command(args,cwd,90);var data=json.readTree(Files.readString(output));
   for(String key:List.of("maven_user_home","repository","temporary","java_home","maven_home")){
    Path value=Path.of(data.path(key).asString()).toAbsolutePath().normalize();if(!value.startsWith(workspace))external++;
   }
   rows.add(Map.of("cwd",cwd.toString(),"paths",data));
  }
  String after=command(List.of(git(),"status","--porcelain"),source,30);String nextBranch=command(List.of(git(),"rev-parse","HEAD"),source,30);
  boolean preserved=before.equals(after)&&branch.equals(nextBranch);assertThat(preserved).as("Wrapper probes must not reset or overwrite checkout").isTrue();
  var result=Map.<String,Object>of("verified_cwds",rows.size(),"external_managed_paths",external,"overwritten_worktrees",preserved?0:1,"paths",rows);
  Files.writeString(root.resolve("maven-boundary.json"),json.writeValueAsString(result),StandardCharsets.UTF_8);return result;
 }
 String windowsProbe(){return """
param([string]$WorkspaceRoot,[string]$SourceRoot,[string]$Report)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$OutputEncoding=[Console]::OutputEncoding
. (Join-Path $SourceRoot 'scripts/java-env.ps1') -WorkspaceRoot $WorkspaceRoot
$wrapper=Join-Path $SourceRoot 'backend-java/mvnw.cmd'
$pom=Join-Path $SourceRoot 'backend-java/pom.xml'
$repository=(& $wrapper -q -f $pom 'org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate' '-Dexpression=settings.localRepository' '-DforceStdout' | Out-String).Trim()
if($LASTEXITCODE -ne 0){throw 'Actual Maven repository probe failed'}
$version=(& $wrapper -v | Out-String)
if($LASTEXITCODE -ne 0){throw 'Actual Maven distribution probe failed'}
$match=[regex]::Match($version,'Maven home: ([^\r\n]+)')
if(-not $match.Success){throw 'Maven home missing from real wrapper output'}
$value=@{maven_user_home=$env:MAVEN_USER_HOME;repository=$repository;temporary=$env:TMP;java_home=$env:JAVA_HOME;maven_home=$match.Groups[1].Value.Trim()}
[IO.File]::WriteAllText($Report,($value|ConvertTo-Json -Depth 6),(New-Object Text.UTF8Encoding($false)))
""";}
 Map<String,Object> docker()throws Exception{
  Path context=root.resolve("docker-context");Files.createDirectories(context);
  Files.copy(source.resolve(".dockerignore"),context.resolve(".dockerignore"),StandardCopyOption.REPLACE_EXISTING);
  var sentinels=List.of(".env",".env.java",".cache/private-token",".tools/private-tool",".data/private-record",".superpowers/private-ledger","backend-java/target/private-class","frontend/node_modules/private-package");
  for(String name:sentinels){Path path=context.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,"PRIVATE-SENTINEL-"+UUID.randomUUID());}
  Files.writeString(context.resolve("public.txt"),"Public build input");
  String image="servicehub-boundary-"+UUID.randomUUID().toString().replace("-","");
  Files.writeString(context.resolve("Dockerfile"),"FROM mirror.gcr.io/library/nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236\nCOPY . /probe\nCMD [\"sh\",\"-c\",\"find /probe -type f -print\"]\n");
  String docker=dockerExecutable();command(List.of(docker,"build","--label","servicehub.fixture="+root.getFileName(),"-t",image,context.toString()),source,180);
  String id=command(List.of(docker,"create","--label","servicehub.fixture="+root.getFileName(),image),source,30).strip();assertThat(id).matches("[a-f0-9]{64}");ownedContainers.add(id);mutations.add(List.of("create",id));
  String files;
  try{mutations.add(List.of("start",id));files=command(List.of(docker,"start","-a",id),source,30);}finally{mutations.add(List.of("rm",id));command(List.of(docker,"rm",id),source,30);command(List.of(docker,"image","rm",image),source,30);}
  assertThat(files).contains("/probe/public.txt");long found=sentinels.stream().filter(name->files.contains("/probe/"+name)).count();
  Path config=root.resolve("compose.resolved.private.json");
  var compose=new ProcessBuilder(docker,"compose","--env-file",workspace.resolve(".env.java").toString(),"-f",source.resolve("compose.java.yaml").toString(),"--profile","api","--profile","worker","--profile","web","config","--format","json").directory(source.toFile()).redirectError(root.resolve("compose-config.stderr").toFile()).redirectOutput(config.toFile()).start();
  if(!compose.waitFor(30,TimeUnit.SECONDS)||compose.exitValue()!=0)throw new AssertionError("Actual Compose configuration failed; see private fixture diagnostics");
  var document=json.readTree(Files.readString(config));int nonLoopback=0;var bindings=new ArrayList<String>();
  for(var service:document.path("services"))for(var port:service.path("ports")){String address=port.path("host_ip").asString();bindings.add(address+":"+port.path("published").asString());if(!address.equals("127.0.0.1"))nonLoopback++;}
  assertThat(bindings).hasSize(6);long foreign=mutations.stream().filter(change->!ownedContainers.contains(change.get(1))).count();
  var result=Map.<String,Object>of("sentinels_tested",sentinels.size(),"sentinels_in_image",found,"non_loopback_ports",nonLoopback,"foreign_containers_modified",foreign,"loopback_bindings",bindings,"owned_mutations",mutations);
  Files.writeString(root.resolve("docker-boundary.json"),json.writeValueAsString(result),StandardCharsets.UTF_8);return result;
 }
 String command(List<String> args,Path cwd,int seconds)throws Exception{
  Path out=root.resolve("command-"+UUID.randomUUID()+".log");var process=new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(out.toFile()).start();
  if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Owned command timed out; diagnostics: "+out);}
  if(process.exitValue()!=0)throw new AssertionError("Owned command failed; diagnostics: "+out);return Files.readString(out,StandardCharsets.UTF_8);
 }
 boolean windows(){return System.getProperty("os.name").startsWith("Windows");}
 String dockerExecutable(){return windows()?workspace.resolve(".tools/docker/resources/bin/docker.exe").toString():"docker";}
 String git(){return windows()?"C:/Program Files/Git/bin/git.exe":"git";}
}
