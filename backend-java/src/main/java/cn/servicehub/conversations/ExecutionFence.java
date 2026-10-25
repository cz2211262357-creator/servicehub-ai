package cn.servicehub.conversations;
public record ExecutionFence(long tenantId,long conversationId,long topicId,long runId,long generationEpoch,long executionVersion) { }
