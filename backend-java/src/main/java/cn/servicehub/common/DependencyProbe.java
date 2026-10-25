package cn.servicehub.common;

public interface DependencyProbe {
    String name();
    boolean isHealthy() throws Exception;
}
