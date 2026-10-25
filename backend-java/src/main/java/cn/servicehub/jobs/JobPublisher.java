package cn.servicehub.jobs;
@FunctionalInterface
public interface JobPublisher {void publish(long jobId,String route);}
