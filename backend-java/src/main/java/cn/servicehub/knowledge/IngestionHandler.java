package cn.servicehub.knowledge;
import cn.servicehub.ai.*;
import cn.servicehub.common.BusinessException;
import cn.servicehub.jobs.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service("knowledge_index")
public class IngestionHandler implements JobHandler {
    private final KnowledgeService documents;private final DocumentParser parser;private final EmbeddingGateway embeddings;private final VectorIndex vectors;private final CancellationGuardFactory guards;
    public IngestionHandler(KnowledgeService documents,DocumentParser parser,EmbeddingGateway embeddings,VectorIndex vectors,CancellationGuardFactory guards){this.documents=documents;this.parser=parser;this.embeddings=embeddings;this.vectors=vectors;this.guards=guards;}
    @Override public void handle(JobLease lease){
        var guard=guards.forJob(lease);
        try{
            guard.check();var source=documents.source(lease);var pages=parser.parse(documents.privatePath(source),source.mediaType());guard.check();
            var chunks=documents.stage(lease,parser.split(pages));guard.check();
            for(int from=0;from<chunks.size();from+=10){
                var batch=chunks.subList(from,Math.min(from+10,chunks.size()));guard.check();
                var outputs=embeddings.embed(batch.stream().map(KnowledgeMapper.ChunkRow::text).toList(),guard);
                for(int i=0;i<batch.size();i++){guard.check();vectors.write(lease.tenantId(),source.id(),lease.executionVersion(),batch.get(i).id(),outputs.get(i));}
            }
            guard.check();documents.activate(lease);
        }catch(RuntimeException error){
            try{documents.markFailed(lease,error instanceof BusinessException b?b.code():error instanceof ExecutionCancelledException?"execution_cancelled":"ingestion_failed");}
            catch(RuntimeException stale){/* A stale lease or revoked principal cannot modify document state. */}
            throw error;
        }
    }
}
