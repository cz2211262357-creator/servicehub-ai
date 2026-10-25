package cn.servicehub.knowledge;
import cn.servicehub.ai.*;
import cn.servicehub.auth.AuthorizationService;
import cn.servicehub.common.*;
import cn.servicehub.config.ServiceHubProperties;
import java.util.*;
import org.springframework.stereotype.Service;

/** Both ranked paths use current tenant/version/generation before their candidate limits. */
@Service
public class HybridRetriever {
    private final KnowledgeMapper documents;private final VectorIndex vectors;private final EmbeddingGateway embeddings;private final AuthorizationService authorization;private final ServiceHubProperties properties;
    public HybridRetriever(KnowledgeMapper documents,VectorIndex vectors,EmbeddingGateway embeddings,AuthorizationService authorization,ServiceHubProperties properties){this.documents=documents;this.vectors=vectors;this.embeddings=embeddings;this.authorization=authorization;this.properties=properties;}
    public List<Evidence> search(Actor actor,String query,int limit,CancellationGuard guard){
        authorization.requireCurrent(actor);if(query==null||query.isBlank())return List.of();
        if(limit<1||query.codePointCount(0,query.length())>8000)throw new BusinessException("invalid_search","检索参数不合法",422);
        query=query.strip();
        try{
            guard.check();var active=documents.activeGenerations(actor.tenantId());if(active.isEmpty())return List.of();
            var generations=new LinkedHashMap<Long,Long>();
            for(var version:active){
                if(!properties.model().embeddingName().equals(version.model())||properties.model().embeddingDimensions()!=version.dimensions())
                    throw new BusinessException("index_migration_required","知识库向量模型已变更，需要重新索引",503);
                generations.put(version.versionId(),version.executionVersion());
            }
            var keyword=documents.keyword(actor.tenantId(),query,30);guard.check();
            var output=embeddings.embed(List.of(query),guard);guard.check();
            var vector=vectors.search(actor.tenantId(),output.getFirst(),30,generations).stream().map(VectorIndex.Hit::chunkId).toList();
            var ranking=rank(List.of(keyword,vector));guard.check();
            if(ranking.isEmpty())return List.of();
            var current=documents.currentEvidence(actor.tenantId(),ranking.stream().map(Ranked::id).toList());var byId=new HashMap<Long,KnowledgeMapper.EvidenceRow>();
            for(var row:current)byId.put(row.chunkId(),row);
            var result=new ArrayList<Evidence>();
            for(var item:ranking){
                var row=byId.get(item.id());if(row==null||!Objects.equals(generations.get(row.versionId()),row.executionVersion()))continue;
                result.add(new Evidence(row.documentId(),row.versionId(),row.chunkId(),row.filename(),row.page(),row.text(),item.score()));
                if(result.size()>=Math.min(6,limit))break;
            }
            authorization.requireCurrent(actor);guard.check();return List.copyOf(result);
        }catch(ExecutionCancelledException|BusinessException e){throw e;}
        catch(RuntimeException e){throw new BusinessException("retrieval_unavailable","混合检索依赖暂时不可用",503);}
    }
    record Ranked(long id,double score){}
    static List<Ranked> rank(List<List<Long>> rankings){
        var scores=new HashMap<Long,Double>();
        for(var ranking:rankings){var seen=new LinkedHashSet<>(ranking);int position=0;for(long id:seen){position++;scores.merge(id,1.0/(60+position),Double::sum);}}
        return scores.entrySet().stream().map(e->new Ranked(e.getKey(),e.getValue())).sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparingLong(Ranked::id)).toList();
    }
}
