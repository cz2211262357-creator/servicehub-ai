package cn.servicehub.knowledge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retain generation tombstones so even very late orphan writes are collected next pass. */
@Service
public class InactiveIndexCleanup {
    private final KnowledgeMapper documents;private final VectorIndex vectors;private final TransactionTemplate transactions;
    public InactiveIndexCleanup(KnowledgeMapper documents,VectorIndex vectors,PlatformTransactionManager manager){this.documents=documents;this.vectors=vectors;transactions=new TransactionTemplate(manager);}
    public void cleanupOnce(){
        for(var generation:documents.collectibleGenerations()){
            // Eligibility is monotonic: an older execution or a terminal failed execution cannot activate.
            // Network deletion intentionally precedes the short SQL transaction.
            vectors.deleteGeneration(generation.tenantId(),generation.versionId(),generation.executionVersion());
            transactions.executeWithoutResult(status->{documents.removeGenerationChunks(generation);documents.cleaned(generation);});
        }
    }
}
