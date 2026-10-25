package cn.servicehub.support;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Reproducibility metadata only; hashes source files, never environment files or uploads. */
final class SourceIdentity {
 static Map<String,Object> current()throws Exception{
  Path root=PythonReference.worktree();var files=new ArrayList<Path>();
  for(String name:List.of("backend-java/src","scripts"))try(var walk=Files.walk(root.resolve(name))){walk.filter(Files::isRegularFile).filter(path->!path.toString().contains("__pycache__")).forEach(files::add);}
  for(String name:List.of("backend-java/pom.xml","backend-java/Dockerfile","compose.java.yaml","deploy/java-web.Dockerfile","deploy/java-nginx.conf"))if(Files.isRegularFile(root.resolve(name)))files.add(root.resolve(name));
  files.sort(Comparator.comparing(path->root.relativize(path).toString()));var digest=MessageDigest.getInstance("SHA-256");
  for(var file:files){digest.update(root.relativize(file).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(Files.readAllBytes(file));digest.update((byte)0);}
  Path jar=root.resolve("backend-java/target/servicehub-backend-0.1.0.jar");var result=new LinkedHashMap<String,Object>();result.put("source_commit",git(root,"rev-parse","HEAD").strip());result.put("working_tree_dirty",!git(root,"status","--porcelain").isBlank());result.put("source_snapshot_sha256",HexFormat.of().formatHex(digest.digest()));result.put("source_files",files.size());
  if(Files.isRegularFile(jar))result.put("product_jar_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));return result;
 }
 static String git(Path root,String...arguments)throws Exception{
  var command=new ArrayList<String>();command.add(System.getProperty("os.name").startsWith("Windows")?"C:/Program Files/Git/bin/git.exe":"git");command.addAll(List.of(arguments));
  var process=new ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!process.waitFor(8,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Source identity command failed");return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
 }
}
