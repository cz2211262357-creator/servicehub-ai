package cn.servicehub.knowledge;
import cn.servicehub.auth.AuthorizationService;
import cn.servicehub.common.*;
import cn.servicehub.conversations.ConversationService;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CitationAuthorizer {
    private final KnowledgeMapper documents;private final KnowledgeService service;private final AuthorizationService authorization;private final ConversationService conversations;
    public CitationAuthorizer(KnowledgeMapper documents,KnowledgeService service,AuthorizationService authorization,ConversationService conversations){this.documents=documents;this.service=service;this.authorization=authorization;this.conversations=conversations;}
    /** Joins the caller's final-write transaction; lock before run/job so disable cannot race commit. */
    @Transactional public void requireReadable(Actor actor,long conversationId,List<Evidence> evidence){
        authorization.requireForWrite(actor);conversations.lock(actor,conversationId);
        if(evidence==null||evidence.size()>30)throw unavailable();
        evidence.stream().map(Evidence::documentId).distinct().sorted().forEach(id->{
            var row=documents.lockDocument(actor.tenantId(),id);if(row==null||!row.enabled())throw unavailable();
        });
        if(evidence.isEmpty())return;
        var rows=documents.currentEvidence(actor.tenantId(),evidence.stream().map(Evidence::chunkId).distinct().toList());
        var byId=new HashMap<Long,KnowledgeMapper.EvidenceRow>();rows.forEach(row->byId.put(row.chunkId(),row));
        for(var item:evidence){
            var row=byId.get(item.chunkId());
            if(row==null||row.documentId()!=item.documentId()||row.versionId()!=item.versionId()||!Objects.equals(row.filename(),item.filename())
                ||!Objects.equals(row.page(),item.page())||!Objects.equals(row.text(),item.excerpt())||!Double.isFinite(item.score()))throw unavailable();
        }
    }
    /** A historical resource is bound to this exact authorized conversation, even if another own one cited it. */
    public Resource openHistorical(Actor actor,long conversationId,long versionId){
        authorization.requireCurrent(actor);conversations.get(actor,conversationId);var version=documents.versionById(actor.tenantId(),versionId);
        if(version==null||documents.citedInConversation(actor.tenantId(),conversationId,versionId)==0)throw missing();
        var grant=service.download(actor,version.documentId(),versionId);authorization.requireCurrent(actor);return new FileSystemResource(grant.path());
    }
    /** Old assistant bodies carrying stale/disabled citations are excluded as a whole from new prompts. */
    public boolean historyReadable(Actor actor,long conversationId,List<Map<String,Object>> citations){
        authorization.requireCurrent(actor);conversations.get(actor,conversationId);if(citations.isEmpty())return true;if(citations.size()>6)return false;
        var ids=new ArrayList<Long>();
        try{for(var citation:citations)ids.add(number(citation,"chunk_id"));}catch(IllegalArgumentException e){return false;}
        var current=documents.currentEvidence(actor.tenantId(),ids);var byId=new HashMap<Long,KnowledgeMapper.EvidenceRow>();current.forEach(row->byId.put(row.chunkId(),row));
        try{
            for(var citation:citations){
                var row=byId.get(number(citation,"chunk_id"));
                if(row==null||row.documentId()!=number(citation,"document_id")||row.versionId()!=number(citation,"version_id")
                    ||row.page()!=number(citation,"page")||!Objects.equals(row.text(),citation.get("text")))return false;
            }
        }catch(IllegalArgumentException e){return false;}
        authorization.requireCurrent(actor);return true;
    }
    private long number(Map<String,Object> value,String name){Object raw=value.get(name);if(!(raw instanceof Number n)||n.longValue()<=0)throw new IllegalArgumentException("Invalid citation");return n.longValue();}
    private BusinessException unavailable(){return new BusinessException("citation_unavailable","知识来源已变更或不可访问，请重新检索",409);}
    private BusinessException missing(){return new BusinessException("not_found","引用来源不存在或无权访问",404);}
}
