package cn.servicehub.jobs;
@FunctionalInterface
public interface JobHandler {void handle(JobLease lease);}
