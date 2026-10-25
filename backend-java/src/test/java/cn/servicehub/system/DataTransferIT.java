package cn.servicehub.system;

import cn.servicehub.support.DependencyContainers;
import cn.servicehub.support.PythonReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Executes the delivery CLI against two private MySQL databases and real private files. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataTransferIT {
 final JsonMapper json=JsonMapper.builder().build();
 final String password=UUID.randomUUID().toString();
 GenericContainer<?> source,target;
 Path r,w,root,python,driver,manifest,sourceEnv,targetEnv,uploads;
 String sourceUrl,targetUrl;
 @BeforeAll void start() throws Exception {
  r=Path.of(Objects.requireNonNull(System.getenv("SERVICEHUB_WORKSPACE_ROOT"))).toAbsolutePath().normalize();
  w=PythonReference.worktree();driver=w.resolve("scripts/java-data-transfer.py");
  assertThat(driver).as("Required production data-transfer CLI").isRegularFile();
  root=r.resolve(".data/java/transfer/test-"+UUID.randomUUID());Files.createDirectories(root);
  python=r.resolve(".venv/Scripts/python.exe");if(!Files.exists(python))python=r.resolve(".venv/bin/python");
  source=mysql();target=mysql();CompletableFuture.allOf(
   CompletableFuture.runAsync(source::start),CompletableFuture.runAsync(target::start)).join();
  sourceUrl=jdbc(source);targetUrl=jdbc(target);await(sourceUrl);await(targetUrl);
  Flyway.configure().dataSource(targetUrl,"servicehub_test",password).locations("classpath:db/migration").load().migrate();
  uploads=root.resolve("target-uploads");
  sourceEnv=root.resolve("source.env");targetEnv=root.resolve("target.env");
  Files.writeString(sourceEnv,"SERVICEHUB_WORKSPACE_ROOT="+r+"\nDATABASE_URL="+asyncUrl(source)+"\nUPLOAD_DIR="+root.resolve("source-uploads")+"\n",StandardCharsets.UTF_8);
  Files.writeString(targetEnv,"SERVICEHUB_WORKSPACE_ROOT="+r+"\nSERVICEHUB_JAVA_JDBC_URL="+targetUrl+"\nSERVICEHUB_JAVA_DB_USER=servicehub_test\nSERVICEHUB_JAVA_DB_PASSWORD="+password+"\nSERVICEHUB_JAVA_UPLOAD_DIR="+uploads+"\n",StandardCharsets.UTF_8);
  Path setup=root.resolve("setup.json"),fixture=root.resolve("synthetic-v1.json"),helper=root.resolve("setup-source.py");
  Files.writeString(setup,json.writeValueAsString(Map.of("url",asyncUrl(source),"uploads",root.resolve("source-uploads").toString())));
  Files.copy(getClass().getResourceAsStream("/transfer/synthetic-v1.json"),fixture);
  Files.copy(getClass().getResourceAsStream("/transfer/setup-source.py"),helper);
  runPython(helper,List.of(setup.toString(),fixture.toString()),0);
  manifest=root.resolve("snapshot/manifest.json");
 }
 GenericContainer<?> mysql(){
  return new GenericContainer<>(DependencyContainers.MYSQL_IMAGE)
   .withEnv("MYSQL_DATABASE","servicehub_java_it").withEnv("MYSQL_USER","servicehub_test")
   .withEnv("MYSQL_PASSWORD",password).withEnv("MYSQL_ROOT_PASSWORD",UUID.randomUUID().toString())
   .withCommand("--default-time-zone=+00:00","--ngram_token_size=2","--log-bin-trust-function-creators=1")
   .withExposedPorts(3306).withCreateContainerCmdModifier(command->{
    var ports=new com.github.dockerjava.api.model.Ports();
    ports.bind(new com.github.dockerjava.api.model.ExposedPort(3306),com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0));
    command.getHostConfig().withPortBindings(ports);
   }).withStartupTimeout(Duration.ofMinutes(3));
 }
 String jdbc(GenericContainer<?> container){return "jdbc:mysql://127.0.0.1:"+container.getMappedPort(3306)+"/servicehub_java_it?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true";}
 String asyncUrl(GenericContainer<?> container){return "mysql+asyncmy://servicehub_test:"+password+"@127.0.0.1:"+container.getMappedPort(3306)+"/servicehub_java_it";}
 Connection connection(String url) throws SQLException{return DriverManager.getConnection(url,"servicehub_test",password);}
 void await(String url) throws Exception{
  long end=System.nanoTime()+Duration.ofSeconds(45).toNanos();
  while(System.nanoTime()<end){try(var c=connection(url)){if(c.isValid(2))return;}catch(SQLException ignored){}Thread.sleep(200);}
  throw new IllegalStateException("Owned transfer database readiness timed out");
 }
 String runPython(Path script,List<String> args,int expected) throws Exception {return runPython(script,args,expected,null);}
 String runPython(Path script,List<String> args,int expected,String stdin) throws Exception {
  var command=new ArrayList<String>(List.of(python.toString(),"-B",script.toString()));command.addAll(args);
  Path log=root.resolve("cli-"+UUID.randomUUID()+".log");var builder=new ProcessBuilder(command).directory(w.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
  builder.environment().put("PYTHONPATH",w.resolve("backend/src").toString());builder.environment().put("PYTHONUTF8","1");builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
  builder.environment().keySet().removeIf(key->key.startsWith("SERVICEHUB_MODEL_")||key.startsWith("SERVICEHUB_EMBEDDING_")||Set.of("OPENAI_API_KEY","DASHSCOPE_API_KEY").contains(key));
  var child=builder.start();if(stdin!=null)try(var input=child.outputWriter(StandardCharsets.UTF_8)){input.write(stdin);input.newLine();}
  try{assertThat(child.waitFor(90,TimeUnit.SECONDS)).as("Bounded transfer CLI").isTrue();assertThat(child.exitValue()).as(Files.readString(log)).isEqualTo(expected);}finally{if(child.isAlive())child.destroyForcibly();}
  String text=Files.readString(log);assertThat(text).doesNotContain(password,"SOURCE_ONLY_HASH_NEVER_TRANSFER");return text;
 }
 String cli(int expected,String... args) throws Exception{return runPython(driver,List.of(args),expected);}
 long count(String table) throws Exception{try(var c=connection(targetUrl);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+table)){rs.next();return rs.getLong(1);}}
 long privateFiles() throws Exception{if(!Files.exists(uploads))return 0;try(var paths=Files.walk(uploads)){return paths.filter(Files::isRegularFile).count();}}
 void targetEmpty() throws Exception {for(String table:List.of("tenants","users","orders","messages","jobs","outbox_events","document_versions"))assertThat(count(table)).as(table).isZero();assertThat(privateFiles()).isZero();}

 @Test @Order(1) void exportCopiesOnlyBusinessDataAndVerifiesRealFileHashes() throws Exception {
  cli(0,"export","--source-env",sourceEnv.toString(),"--output",manifest.toString());
  String data=Files.readString(manifest);assertThat(data).doesNotContain("SOURCE_ONLY_HASH_NEVER_TRANSFER","old-worker","legacy-never-restart","password_hash");
  JsonNode value=json.readTree(data);assertThat(value.path("tables").path("tenants").size()).isEqualTo(2);
  assertThat(value.path("tables").path("jobs").isMissingNode()).isTrue();
  assertThat(value.path("tables").path("ai_runs").isMissingNode()).isTrue();
  assertThat(value.path("tables").path("users").get(0).has("session_version")).isFalse();
  cli(0,"validate","--input",manifest.toString());targetEmpty();
 }
 @Test @Order(2) void rejectsIdentityRelationsDuplicatesPathsAndCorruptFilesBeforeAnyTargetWrite() throws Exception {
  Path same=root.resolve("same.env");Files.writeString(same,"SERVICEHUB_WORKSPACE_ROOT="+r+"\nSERVICEHUB_JAVA_JDBC_URL="+sourceUrl.replace("127.0.0.1","localhost")+"\nSERVICEHUB_JAVA_DB_USER=servicehub_test\nSERVICEHUB_JAVA_DB_PASSWORD="+password+"\nSERVICEHUB_JAVA_UPLOAD_DIR="+uploads+"\n");
  assertThat(cli(2,"import","--target-env",same.toString(),"--input",manifest.toString(),"--allow-empty-target")).contains("same_source");
  var original=json.readTree(Files.readString(manifest));
  for(String mutation:List.of("duplicate","cross_tenant","path","amount","utc","unknown_field","old_job")){
   var edited=(tools.jackson.databind.node.ObjectNode)original.deepCopy();
   var rows=(tools.jackson.databind.node.ArrayNode)edited.path("tables").path("orders");
   switch(mutation){
    case "duplicate"->rows.add(rows.get(0).deepCopy());
    case "cross_tenant"->((tools.jackson.databind.node.ObjectNode)rows.get(0)).put("customer_id",204);
    case "path"->((tools.jackson.databind.node.ObjectNode)edited.path("tables").path("document_versions").get(0)).put("storage_key","../../outside.txt");
    case "amount"->((tools.jackson.databind.node.ObjectNode)rows.get(0)).put("total_amount","12.301");
    case "unknown_field"->((tools.jackson.databind.node.ObjectNode)rows.get(0)).put("injected_column","refuse");
    case "old_job"->((tools.jackson.databind.node.ObjectNode)edited.path("tables")).putArray("jobs").addObject().put("id",1101);
    case "utc"->((tools.jackson.databind.node.ObjectNode)rows.get(0)).put("created_at","2026-01-02T03:04:05");
   }
   Path bad=manifest.resolveSibling("bad-"+mutation+".json");Files.writeString(bad,json.writeValueAsString(edited));
   cli(2,"validate","--input",bad.toString());cli(2,"import","--target-env",targetEnv.toString(),"--input",bad.toString(),"--allow-empty-target");targetEmpty();
  }
  JsonNode file=original.path("files").get(0);Path path=manifest.getParent().resolve(file.path("path").asString());byte[] good=Files.readAllBytes(path);
  Files.writeString(path,"corrupt synthetic content");try{cli(2,"validate","--input",manifest.toString());targetEmpty();}finally{Files.write(path,good);}
 }
 @Test @Order(3) void databaseFailureRollsBackRowsAndOnlyRemovesOwnedPromotedFiles() throws Exception {
  assertThat(manifest).isRegularFile();
  try(var c=connection(targetUrl);var st=c.createStatement()){
   st.execute("CREATE TABLE fixture_transfer_fault_hits(id BIGINT PRIMARY KEY AUTO_INCREMENT) ENGINE=MEMORY");
   st.execute("CREATE TRIGGER fixture_reject_transfer BEFORE INSERT ON orders FOR EACH ROW BEGIN INSERT INTO fixture_transfer_fault_hits VALUES(NULL); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic transfer failure'; END");
  }
  try{cli(2,"import","--target-env",targetEnv.toString(),"--input",manifest.toString(),"--allow-empty-target");assertThat(count("fixture_transfer_fault_hits")).isEqualTo(1);targetEmpty();}
  finally{try(var c=connection(targetUrl);var st=c.createStatement()){st.execute("DROP TRIGGER fixture_reject_transfer");st.execute("DROP TABLE fixture_transfer_fault_hits");}}
 }

 @Test @Order(4) void lostCommitAcknowledgementNeverDeletesFilesBeforeTheServerCommitCompletes() throws Exception {
  var uncertain=mysql();try{
   uncertain.start();String url=jdbc(uncertain);await(url);
   Flyway.configure().dataSource(url,"servicehub_test",password).locations("classpath:db/migration").load().migrate();
   Path imported=root.resolve("uncertain-uploads"),env=root.resolve("uncertain.env");
   try(var proxy=new MysqlCommitProxy(uncertain.getHost(),uncertain.getMappedPort(3306))){
    String proxied=url.replace(":"+uncertain.getMappedPort(3306)+"/",":"+proxy.port()+"/");
    Files.writeString(env,"SERVICEHUB_WORKSPACE_ROOT="+r+"\nSERVICEHUB_JAVA_JDBC_URL="+proxied+"\nSERVICEHUB_JAVA_DB_USER=servicehub_test\nSERVICEHUB_JAVA_DB_PASSWORD="+password+"\nSERVICEHUB_JAVA_UPLOAD_DIR="+imported+"\n");
    String output=cli(2,"import","--target-env",env.toString(),"--input",manifest.toString(),"--allow-empty-target");
    // The server has not processed COMMIT yet. A nonlocking probe can still see no marker.
    proxy.awaitCommit();
    try(var c=connection(url);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM document_versions")){rs.next();assertThat(rs.getInt(1)).isEqualTo(2);}
    for(var file:json.readTree(Files.readString(manifest)).path("files")){
     Path privateFile=imported.resolve(file.path("storage_key").asString());
     assertThat(privateFile).as("Committed source must survive acknowledgement loss").isRegularFile();
     assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(privateFile)))).isEqualTo(file.path("sha256").asString());
    }
    assertThat(output).contains("commit_outcome_unknown");
   }
  }finally{uncertain.stop();}
 }

 @Test @Order(4) void taskCancellationDuringCommitCannotDeleteCommittedFiles() throws Exception {
  var cancelled=mysql();try{
   cancelled.start();String url=jdbc(cancelled);await(url);
   Flyway.configure().dataSource(url,"servicehub_test",password).locations("classpath:db/migration").load().migrate();
   Path imported=root.resolve("cancelled-uploads"),env=root.resolve("cancelled.env"),helper=root.resolve("cancel-after-commit.py");
   Files.copy(getClass().getResourceAsStream("/transfer/cancel-after-commit.py"),helper);
   Files.writeString(env,"SERVICEHUB_WORKSPACE_ROOT="+r+"\nSERVICEHUB_JAVA_JDBC_URL="+url+"\nSERVICEHUB_JAVA_DB_USER=servicehub_test\nSERVICEHUB_JAVA_DB_PASSWORD="+password+"\nSERVICEHUB_JAVA_UPLOAD_DIR="+imported+"\n");
   assertThat(runPython(helper,List.of(driver.toString(),env.toString(),manifest.toString()),0)).contains("cancelled_after_real_commit");
   try(var c=connection(url);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM document_versions")){rs.next();assertThat(rs.getInt(1)).isEqualTo(2);}
   for(var file:json.readTree(Files.readString(manifest)).path("files"))assertThat(imported.resolve(file.path("storage_key").asString())).isRegularFile();
  }finally{cancelled.stop();}
 }
 @Test @Order(5) void transferPreservesTenantAmountsAndRejectsNonEmptyTarget() throws Exception {
  cli(0,"import","--target-env",targetEnv.toString(),"--input",manifest.toString(),"--allow-empty-target");
  assertThat(count("tenants")).isEqualTo(2);assertThat(count("messages")).isEqualTo(3);assertThat(count("after_sale_tickets")).isEqualTo(1);
  assertThat(count("conversation_topics")).isEqualTo(2);assertThat(privateFiles()).isEqualTo(2);
  for(var file:json.readTree(Files.readString(manifest)).path("files")){
   Path imported=uploads.resolve(file.path("storage_key").asString());assertThat(Files.size(imported)).isEqualTo(file.path("size_bytes").asLong());
   assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(imported)))).isEqualTo(file.path("sha256").asString());
  }
  try(var c=connection(targetUrl);var st=c.createStatement()){
   try(var rs=st.executeQuery("SELECT total_amount,created_at FROM orders WHERE tenant_id=101 AND id=401")){assertThat(rs.next()).isTrue();assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("12.30");assertThat(rs.getObject(2,LocalDateTime.class)).isEqualTo(LocalDateTime.parse("2026-01-02T03:04:05.123456"));}
   try(var rs=st.executeQuery("SELECT COUNT(*) FROM messages m JOIN conversations c ON c.id=m.conversation_id AND c.tenant_id=m.tenant_id WHERE m.topic_id=c.active_topic_id AND m.routing_state='RESOLVED' AND m.context_status='EXCLUDED' AND m.ai_run_id IS NULL AND m.trigger_run_id IS NULL AND m.trigger_job_id IS NULL")){rs.next();assertThat(rs.getInt(1)).isEqualTo(3);}
   try(var rs=st.executeQuery("SELECT COUNT(*) FROM users WHERE is_active=0 AND password_hash<>'SOURCE_ONLY_HASH_NEVER_TRANSFER' AND session_version<>77")){rs.next();assertThat(rs.getInt(1)).isEqualTo(4);}
   try(var rs=st.executeQuery("SELECT COUNT(*) FROM ticket_drafts WHERE id=602 AND expires_at<=UTC_TIMESTAMP(6)")){rs.next();assertThat(rs.getInt(1)).isEqualTo(1);}
   try(var rs=st.executeQuery("SELECT COUNT(*) FROM document_versions WHERE job_id IS NULL AND status='failed' AND error_code='migration_reindex_required'")){rs.next();assertThat(rs.getInt(1)).isEqualTo(2);}
  }
  for(String table:List.of("jobs","ai_runs","outbox_events","tool_calls","knowledge_chunks","knowledge_active_versions"))assertThat(count(table)).as("Old execution must not revive: "+table).isZero();
  long before=count("messages"),filesBefore=privateFiles();
  assertThat(cli(2,"import","--target-env",targetEnv.toString(),"--input",manifest.toString(),"--allow-empty-target")).contains("target_not_empty");
  long rejectedImportChangedRows=count("messages")-before;assertThat(rejectedImportChangedRows).isZero();assertThat(privateFiles()).isEqualTo(filesBefore);
 }
 @Test @Order(6) void importedAccountsNeedExplicitNewCredentialsWithoutReusingSourceAuthorization() throws Exception {
  Path accounts=w.resolve("scripts/java-account-admin.py");assertThat(accounts).isRegularFile();
  String credential="Synthetic-New-Credential-"+UUID.randomUUID();
  String output=runPython(accounts,List.of("reset-password","--target-env",targetEnv.toString(),"--tenant-id","101","--user-id","201","--password-stdin"),0,credential);
  assertThat(output).doesNotContain(credential);
  try(var c=connection(targetUrl);var st=c.createStatement();var rs=st.executeQuery("SELECT password_hash,is_active,session_version FROM users WHERE tenant_id=101 AND id=201")){
   assertThat(rs.next()).isTrue();assertThat(rs.getBoolean(2)).isTrue();assertThat(rs.getLong(3)).isGreaterThan(1);
   var encoder=new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,4,65536,3);
   assertThat(encoder.matches(credential,rs.getString(1))).isTrue();
  }
  try(var c=connection(sourceUrl);var st=c.createStatement();var rs=st.executeQuery("SELECT password_hash,session_version FROM users WHERE id=201")){rs.next();assertThat(rs.getString(1)).isEqualTo("SOURCE_ONLY_HASH_NEVER_TRANSFER");assertThat(rs.getLong(2)).isEqualTo(77);}
  assertThat(count("audit_logs")).isGreaterThanOrEqualTo(1);
  runPython(accounts,List.of("bootstrap","--target-env",targetEnv.toString(),"--tenant-name","Must refuse","--email","new@example.com","--display-name","Refused","--password-stdin"),2,credential);
  assertThat(count("tenants")).isEqualTo(2);assertThat(count("users")).isEqualTo(4);
 }
 @Test @Order(7) void cleanDatabaseCanBootstrapAnAdministratorOnlyOnce() throws Exception {
  var boot=mysql();try{
   boot.start();String url=jdbc(boot);await(url);Flyway.configure().dataSource(url,"servicehub_test",password).locations("classpath:db/migration").load().migrate();
   Path env=root.resolve("bootstrap.env");Files.writeString(env,"SERVICEHUB_WORKSPACE_ROOT="+r+"\nSERVICEHUB_JAVA_JDBC_URL="+url+"\nSERVICEHUB_JAVA_DB_USER=servicehub_test\nSERVICEHUB_JAVA_DB_PASSWORD="+password+"\n");
   String credential="Synthetic-Bootstrap-"+UUID.randomUUID();Path accounts=w.resolve("scripts/java-account-admin.py");
   var args=List.of("bootstrap","--target-env",env.toString(),"--tenant-name","合成启动企业","--email","bootstrap@example.com","--display-name","合成管理员","--password-stdin");
   String output=runPython(accounts,args,0,credential);assertThat(output).doesNotContain(credential);
   try(var c=connection(url);var st=c.createStatement();var rs=st.executeQuery("SELECT u.role,u.is_active,u.password_hash,t.name FROM users u JOIN tenants t ON t.id=u.tenant_id")){
    assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("admin");assertThat(rs.getBoolean(2)).isTrue();assertThat(rs.getString(4)).isEqualTo("合成启动企业");
    assertThat(new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,4,65536,3).matches(credential,rs.getString(3))).isTrue();assertThat(rs.next()).isFalse();
   }
   runPython(accounts,args,2,credential);
   try(var c=connection(url);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM users")){rs.next();assertThat(rs.getInt(1)).isEqualTo(1);}
  }finally{boot.stop();}
 }
 @AfterAll void close(){if(source!=null)source.stop();if(target!=null)target.stop();}
}
