package cn.servicehub.contract;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HealthContractTest.Harness.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.profiles.active=health-contract", "servicehub.model.api-key=model-test-secret"})
@ExtendWith(OutputCaptureExtension.class)
class HealthContractTest {
    @Autowired Environment environment;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body, String requestId) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path))
            .header("Content-Type", "application/json");
        if (requestId != null) builder.header("x-request-id", requestId);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void livenessPreservesPythonContractWhenDependenciesAreAbsent() throws Exception {
        var response = send("GET", "/api/v1/health/live", null, "health-request-123");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("ok");
        assertThat(JsonPath.<String>read(response.body(), "$.service")).isEqualTo("servicehub-ai");
        assertThat(JsonPath.<String>read(response.body(), "$.version")).isEqualTo("0.1.0");
        assertThat(response.headers().firstValue("x-request-id")).contains("health-request-123");
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
    }

    @Test void readinessFailsClosedWithoutRegisteredHealthyDependencies() throws Exception {
        var response = send("GET", "/api/v1/health/ready", null, "ready-request-123");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("dependencies_unavailable");
        assertThat(JsonPath.<String>read(response.body(), "$.error.message")).isEqualTo("Database or cache is unavailable");
        assertThat(JsonPath.<String>read(response.body(), "$.error.request_id")).isEqualTo("ready-request-123");
    }

    @Test void validationUsesBaselineEnvelope() throws Exception {
        var response = send("POST", "/__test__/validate", "{\"name\":\"\"}", "validation-request-123");
        assertThat(response.statusCode()).isEqualTo(422);
        Map<String, Object> error = JsonPath.read(response.body(), "$.error");
        assertThat(error).containsKeys("code", "message", "request_id");
        assertThat(error.get("code")).isEqualTo("validation_error");
        assertThat(error.get("request_id")).isEqualTo("validation-request-123");
    }

    @Test void unexpectedErrorsDoNotExposeRequestOrProviderSecrets(CapturedOutput output) throws Exception {
        var response = send("GET", "/__test__/failure", null, "failure-request-123");
        assertThat(response.statusCode()).isEqualTo(500);
        Object error = JsonPath.read(response.body(), "$.error");
        assertThat(error).isInstanceOf(Map.class);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("internal_error");
        assertThat(response.body()).doesNotContain("sensitive-probe-value", "model-test-secret");
        assertThat(output.getAll()).doesNotContain("sensitive-probe-value", "model-test-secret");
    }

    @Test void invalidRequestIdIsReplacedWithSafeIdentifier() throws Exception {
        var response = send("GET", "/api/v1/health/live", null, "bad");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("x-request-id").orElseThrow()).matches("[A-Za-z0-9_-]{8,96}").isNotEqualTo("bad");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @ComponentScan(basePackages = {"cn.servicehub.common", "cn.servicehub.config"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "cn\\.servicehub\\.config\\.(PersistenceConfig|RedisConfiguration)"))
    @Import(ProbeController.class)
    @Profile("health-contract")
    static class Harness {
        @org.springframework.context.annotation.Bean
        org.springframework.security.web.SecurityFilterChain probeSecurity(org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(c -> c.anyRequest().permitAll()).build();
        }
    }

    @RestController
    @Profile("health-contract")
    static class ProbeController {
        record Input(@NotBlank String name) { }
        @PostMapping("/__test__/validate") Map<String, String> validate(@Valid @RequestBody Input input) {
            return Map.of("name", input.name());
        }
        @GetMapping("/__test__/failure") Map<String, String> fail() {
            throw new IllegalStateException("sensitive-probe-value");
        }
    }
}
