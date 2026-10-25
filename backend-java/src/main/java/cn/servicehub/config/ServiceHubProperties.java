package cn.servicehub.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("servicehub")
public record ServiceHubProperties(Path workspaceRoot, Path uploadDirectory, String environment, String sessionCookieName,
    int sessionTtlSeconds, boolean cookieSecure, List<String> allowedOrigins,
    Database database, Redis redis, Rabbitmq rabbitmq, Model model, Limits limits, Worker worker) {
    public ServiceHubProperties {
        if (workspaceRoot == null || !workspaceRoot.isAbsolute()) {
            throw new IllegalArgumentException("SERVICEHUB_WORKSPACE_ROOT must be an explicit absolute project path");
        }
        workspaceRoot = workspaceRoot.normalize();
        var dataRoot=workspaceRoot.resolve(".data/java");
        uploadDirectory=uploadDirectory==null?dataRoot.resolve("uploads"):uploadDirectory.normalize();
        if(!uploadDirectory.isAbsolute()||!uploadDirectory.startsWith(dataRoot)||uploadDirectory.equals(dataRoot))
            throw new IllegalArgumentException("Java uploads must remain inside workspace/.data/java");
        for(var ancestor=uploadDirectory;ancestor!=null&&ancestor.startsWith(workspaceRoot);ancestor=ancestor.getParent())
            if(java.nio.file.Files.isSymbolicLink(ancestor))throw new IllegalArgumentException("Symbolic Java upload paths are not supported");
        allowedOrigins = List.copyOf(allowedOrigins);
    }
    @Override public String toString() { return "ServiceHubProperties[environment=" + environment + ", secrets=REDACTED]"; }
    public record Database(String url, String username, String password) {
        @Override public String toString() { return "Database[REDACTED]"; }
    }
    public record Redis(String host, int port, String password) {
        @Override public String toString() { return "Redis[REDACTED]"; }
    }
    public record Rabbitmq(String host, int port, String username, String password) {
        @Override public String toString() { return "Rabbitmq[REDACTED]"; }
    }
    public record Model(String baseUrl, String name, String apiKey, String embeddingBaseUrl,
        String embeddingName, String embeddingApiKey, int embeddingDimensions, int timeoutSeconds) {
        @Override public String toString() { return "Model[name=" + name + ", secrets=REDACTED]"; }
    }
    public record Limits(int maxUploadBytes, int inputCharacters, int contextMessages, int contextCharacters,
        int maxOutputTokens, int modelRequests, int toolExecutions, int foregroundQueueSeconds,
        int foregroundDeadlineSeconds, int backgroundQueueSeconds, int backgroundDeadlineSeconds,
        int totalExecutionSlots, int backgroundExecutionSlots, int totalModelSlots, int backgroundModelSlots,
        int rateLimitRequests, int rateLimitWindowSeconds) { }
    public record Worker(int leaseSeconds, int renewalSeconds, int maxAttempts, List<Integer> retryDelaysSeconds) { }
}
