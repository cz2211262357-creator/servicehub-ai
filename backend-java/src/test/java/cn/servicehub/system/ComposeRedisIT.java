package cn.servicehub.system;
import cn.servicehub.support.*;
import io.lettuce.core.*;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Executes the actual Compose command, not the different default test-container command. */
class ComposeRedisIT {
 enum SearchCommand implements ProtocolKeyword {
  LIST;
  @Override public byte[] getBytes(){return "FT._LIST".getBytes(StandardCharsets.US_ASCII);}
 }
 @Test void actualComposeRedisStartupRetainsSearchAndHnswModules() throws Exception {
  Path root=Path.of(Objects.requireNonNull(System.getenv("SERVICEHUB_WORKSPACE_ROOT")));
  var process=new ProcessBuilder("docker","compose","--env-file",root.resolve(".env.java").toString(),"-f",PythonReference.worktree().resolve("compose.java.yaml").toString(),"config","--format","json")
   .directory(PythonReference.worktree().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  // Config output includes credentials: parse in memory, never log or put it in assertion messages.
  byte[] output=process.getInputStream().readAllBytes();assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
  var redis=JsonMapper.builder().build().readTree(output).path("services").path("redis");
  var command=new ArrayList<String>();redis.path("command").forEach(value->command.add(value.asString().replace("$$","$")));
  String password=UUID.randomUUID().toString();
  try(var container=new GenericContainer<>(redis.path("image").asString())
    .withEnv("SERVICEHUB_JAVA_REDIS_PASSWORD",password).withCommand(command.toArray(String[]::new)).withExposedPorts(6379)
    .withCreateContainerCmdModifier(value->{var ports=new com.github.dockerjava.api.model.Ports();ports.bind(new com.github.dockerjava.api.model.ExposedPort(6379),com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0));value.getHostConfig().withPortBindings(ports);})
    .withStartupTimeout(Duration.ofSeconds(30))){
   container.start();var uri=RedisURI.Builder.redis(container.getHost(),container.getMappedPort(6379)).withPassword(password.toCharArray()).build();
   var client=RedisClient.create(uri);
   try(var connection=client.connect(StringCodec.UTF8)){
    assertThat(connection.sync().ping()).isEqualTo("PONG");
    assertThat(connection.sync().dispatch(SearchCommand.LIST,new NestedMultiOutput<String,String>(StringCodec.UTF8),new CommandArgs<String,String>(StringCodec.UTF8))).isNotNull();
   }finally{client.shutdown();}
  }
 }
}
