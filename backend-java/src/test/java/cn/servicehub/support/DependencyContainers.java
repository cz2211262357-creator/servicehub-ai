package cn.servicehub.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/** Real, per-suite dependencies. Never points at the user's existing services. */
public final class DependencyContainers implements AutoCloseable {
    public static final String MYSQL_IMAGE = "mysql:8.4@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364";
    public static final String REDIS_IMAGE = "redis:8.4@sha256:43afe4a9a43080e64dfa29699eef903fc9d54991fe8b03d57237c05bed2a396c";
    public static final String RABBIT_IMAGE = "rabbitmq:4.3.6-management@sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58";
    private final String password = UUID.randomUUID().toString();
    public final GenericContainer<?> mysql = loopback(MYSQL_IMAGE, 3306)
        .withEnv("MYSQL_DATABASE", "servicehub_java_it").withEnv("MYSQL_USER", "servicehub_test")
        .withEnv("MYSQL_PASSWORD", password).withEnv("MYSQL_ROOT_PASSWORD", UUID.randomUUID().toString())
        .withCommand("--default-time-zone=+00:00", "--ngram_token_size=2")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(3));
    public final GenericContainer<?> redis = loopback(REDIS_IMAGE, 6379)
        .withCommand("redis-server", "--requirepass", password)
        .waitingFor(Wait.forListeningPort());
    public final GenericContainer<?> rabbitmq = loopback(RABBIT_IMAGE, 5672)
        .withEnv("RABBITMQ_DEFAULT_USER", "servicehub_test").withEnv("RABBITMQ_DEFAULT_PASS", password)
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1)).withStartupTimeout(Duration.ofMinutes(2));
    private static GenericContainer<?> loopback(String image, int port) {
        return new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(port)
            .withCreateContainerCmdModifier(command -> {
                var bindings = new Ports();
                bindings.bind(new ExposedPort(port), Ports.Binding.bindIpAndPort("127.0.0.1", 0));
                command.getHostConfig().withPortBindings(bindings);
            });
    }
    public void start() { Startables.deepStart(Stream.of(mysql, redis, rabbitmq)).join(); }
    public String password() { return password; }
    public String username() { return "servicehub_test"; }
    public String jdbcUrl() {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
            + "/servicehub_java_it?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true";
    }
    @Override public void close() {
        rabbitmq.stop(); redis.stop(); mysql.stop();
    }
}
