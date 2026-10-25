package cn.servicehub.knowledge;
public record Evidence(long documentId,long versionId,long chunkId,String filename,Integer page,String excerpt,double score) { }
