package cn.servicehub.knowledge;

import cn.servicehub.ai.ExecutionCancelledException;
import cn.servicehub.auth.*;
import cn.servicehub.common.*;
import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.jobs.*;
import java.nio.file.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeService {
    private final cn.servicehub.operations.AuditService audit;
    public record Download(Path path,String filename,String mediaType,long size){}
    private final KnowledgeMapper documents;private final PrivateFileStore files;private final AuthorizationService authorization;
    private final JobJournal journal;private final JobCoordinator coordinator;private final ServiceHubProperties properties;private final TransactionTemplate transactions;
    public KnowledgeService(KnowledgeMapper documents,PrivateFileStore files,AuthorizationService authorization,JobJournal journal,JobCoordinator coordinator,ServiceHubProperties properties,PlatformTransactionManager manager,cn.servicehub.operations.AuditService audit){this.audit=audit;
        this.documents=documents;this.files=files;this.authorization=authorization;this.journal=journal;this.coordinator=coordinator;this.properties=properties;transactions=new TransactionTemplate(manager);
    }
    public KnowledgeDtos.DocumentDto upload(Actor actor,String title,UploadInput input){return upload(actor,null,title,input);}
    public KnowledgeDtos.DocumentDto uploadVersion(Actor actor,long id,UploadInput input){return upload(actor,id,null,input);}
    private KnowledgeDtos.DocumentDto upload(Actor actor,Long id,String title,UploadInput input){
        admin(actor);authorization.requireCurrent(actor);var stored=files.save(input);
        var entered=new java.util.concurrent.atomic.AtomicBoolean();
        try{return transactions.execute(status->{
            entered.set(true);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void afterCompletion(int outcome){
                        // A lost COMMIT response is UNKNOWN, not proof of rollback.
                        // Retain uncertain files for reconciliation rather than destroy committed data.
                        if(outcome==STATUS_ROLLED_BACK)files.deleteOwned(stored.key());
                    }
                });
            authorization.requireForWrite(actor);KnowledgeMapper.DocumentRow document;int number;
            if(id==null){
                String name=title==null||title.isBlank()?stored.filename():title.strip();
                name=name.substring(0,name.offsetByCodePoints(0,Math.min(200,name.codePointCount(0,name.length()))));
                var values=new LinkedHashMap<String,Object>();values.put("tenantId",actor.tenantId());values.put("actorId",actor.userId());values.put("title",name);documents.insertDocument(values);
                document=required(documents.lockDocument(actor.tenantId(),((Number)values.get("id")).longValue()));number=1;
            }else{
                document=required(documents.lockDocument(actor.tenantId(),id));var latest=documents.latest(actor.tenantId(),id);number=latest==null?1:latest.number()+1;
                if(documents.advance(actor.tenantId(),id,document.version())!=1)throw conflict();
                document=required(documents.document(actor.tenantId(),id));
            }
            var version=new LinkedHashMap<String,Object>();version.put("tenantId",actor.tenantId());version.put("documentId",document.id());version.put("number",number);
            version.put("key",stored.key());version.put("filename",stored.filename());version.put("mediaType",stored.mediaType());version.put("size",stored.size());version.put("sha256",stored.sha256());documents.insertVersion(version);
            long versionId=((Number)version.get("id")).longValue();
            var payload=Map.<String,Object>of("tenant_id",actor.tenantId(),"actor_id",actor.userId(),"auth_version",actor.authVersion(),"document_id",document.id(),"version_id",versionId,"document_revision",document.version());
            var job=journal.enqueue(new JobCommand(actor.tenantId(),"knowledge_index",ExecutionClass.BACKGROUND,"version:"+versionId,payload));
            if(documents.bindJob(actor.tenantId(),versionId,job.id())!=1)throw new IllegalStateException("Version job already bound");
            audit(actor,document.id(),"knowledge.uploaded");return dto(document);
        });}catch(RuntimeException e){if(!entered.get())files.deleteOwned(stored.key());throw e;}
    }
    public KnowledgeDtos.DocumentPage list(Actor actor,Long cursor,int limit){
        authorization.requireCurrent(actor);var rows=documents.list(actor.tenantId(),actor.role()==Role.CUSTOMER,cursor,limit+1);
        return new KnowledgeDtos.DocumentPage(rows.stream().limit(limit).map(this::dto).toList(),rows.size()>limit?rows.get(limit-1).id():null);
    }
    public KnowledgeDtos.DocumentDto get(Actor actor,long id){authorization.requireCurrent(actor);return dto(required(documents.document(actor.tenantId(),id)));}
    @Transactional public KnowledgeDtos.DocumentDto setEnabled(Actor actor,long id,long version,boolean enabled){
        admin(actor);authorization.requireForWrite(actor);var document=required(documents.lockDocument(actor.tenantId(),id));
        if(document.version()!=version||documents.enable(actor.tenantId(),id,document.version(),enabled)!=1)throw conflict();
        audit(actor,id,"knowledge.enabled");return dto(required(documents.document(actor.tenantId(),id)));
    }
    @Transactional public void delete(Actor actor,long id){
        admin(actor);authorization.requireForWrite(actor);required(documents.lockDocument(actor.tenantId(),id));documents.remove(actor.tenantId(),id);audit(actor,id,"knowledge.deleted");
    }
    public Download download(Actor actor,long id,Long requestedVersion){
        authorization.requireCurrent(actor);var document=required(documents.document(actor.tenantId(),id));Long active=documents.active(actor.tenantId(),id);
        if(actor.role()==Role.CUSTOMER&&(!document.enabled()||active==null))throw notFound("文档不可访问");
        KnowledgeMapper.VersionRow version=requestedVersion!=null?documents.version(actor.tenantId(),id,requestedVersion):active!=null?documents.version(actor.tenantId(),id,active):documents.latest(actor.tenantId(),id);
        if(version==null)throw notFound("文档文件不存在");
        if(actor.role()==Role.CUSTOMER&&!Objects.equals(active,version.id())&&documents.cited(actor.tenantId(),actor.userId(),id,version.id())==0)throw notFound("文档版本不可访问");
        Path path=files.resolve(version.storageKey());if(!Files.isRegularFile(path))throw new BusinessException("file_unavailable","文档文件暂时不可用",503);
        authorization.requireCurrent(actor);return new Download(path,version.filename(),version.mediaType(),version.sizeBytes());
    }
    public KnowledgeMapper.VersionRow source(JobLease lease){
        var command=coordinator.load(lease);
        long doc=number(command.payload().get("document_id")),version=number(command.payload().get("version_id"));
        var result=documents.version(lease.tenantId(),doc,version);if(result==null)throw new ExecutionCancelledException("version_missing");return result;
    }
    @Transactional public List<KnowledgeMapper.ChunkRow> stage(JobLease lease,List<DocumentParser.TextChunk> chunks){
        if(chunks.isEmpty())throw new BusinessException("empty_document","文档内容为空",422);
        var version=lockSource(lease);coordinator.requireValid(lease);
        documents.recordGeneration(lease.tenantId(),version.id(),lease.executionVersion(),lease.jobId());
        documents.clearChunks(lease.tenantId(),version.id());documents.insertChunks(lease.tenantId(),version.documentId(),version.id(),lease.executionVersion(),chunks);
        documents.versionState(lease.tenantId(),version.id(),"processing",null);coordinator.requireValid(lease);
        return documents.chunks(lease.tenantId(),version.id(),lease.executionVersion());
    }
    @Transactional public void activate(JobLease lease){
        var version=lockSource(lease);var document=required(documents.document(lease.tenantId(),version.documentId()));
        var chunks=documents.chunks(lease.tenantId(),version.id(),lease.executionVersion());if(chunks.isEmpty())throw new IllegalStateException("No staged generation");
        // Finish checks the captured document revision before this transaction advances it.
        coordinator.finish(lease,"SUCCEEDED",Map.of("chunks",chunks.size(),"version_id",version.id(),"execution_version",lease.executionVersion()));
        if(documents.ready(lease.tenantId(),version.id(),lease.executionVersion(),properties.model().embeddingName(),properties.model().embeddingDimensions())!=1)throw new ExecutionCancelledException("version_missing");
        documents.activate(lease.tenantId(),version.documentId(),version.id());
        if(documents.advance(lease.tenantId(),version.documentId(),document.version())!=1)throw new ExecutionCancelledException("document_changed");
    }
    @Transactional public void markFailed(JobLease lease,String code){var version=lockSource(lease);coordinator.requireValid(lease);documents.versionState(lease.tenantId(),version.id(),"failed",code);}
    public Path privatePath(KnowledgeMapper.VersionRow version){return files.resolve(version.storageKey());}
    private KnowledgeMapper.VersionRow lockSource(JobLease lease){
        authorization.requireForWrite(lease.actor());long doc=number(lease.payload().get("document_id")),version=number(lease.payload().get("version_id"));
        required(documents.lockDocument(lease.tenantId(),doc));var source=documents.version(lease.tenantId(),doc,version);
        if(source==null||!Objects.equals(source.jobId(),lease.jobId()))throw new ExecutionCancelledException("version_changed");return source;
    }
    private KnowledgeDtos.DocumentDto dto(KnowledgeMapper.DocumentRow document){
        var latest=documents.latest(document.tenantId(),document.id());KnowledgeDtos.VersionDto version=null;
        if(latest!=null){
            String state=latest.status(),code=latest.errorCode();
            if(!"ready".equals(state)&&latest.jobId()!=null){
                var job=documents.jobState(document.tenantId(),latest.jobId());
                if(job!=null&&Set.of("FAILED","CANCELLED","TIMED_OUT").contains(job.state().toUpperCase(Locale.ROOT))){state="failed";code=job.errorCode();}
            }
            version=new KnowledgeDtos.VersionDto(latest.id(),latest.number(),latest.filename(),latest.mediaType(),latest.sizeBytes(),state,code,latest.jobId(),latest.createdAt());
        }
        return new KnowledgeDtos.DocumentDto(document.id(),document.title(),document.enabled(),document.version(),document.createdAt(),documents.active(document.tenantId(),document.id()),version);
    }
    private void admin(Actor actor){if(actor.role()!=Role.ADMIN)throw new BusinessException("forbidden","当前账号无权执行此操作",403);}
    private KnowledgeMapper.DocumentRow required(KnowledgeMapper.DocumentRow row){if(row==null)throw notFound("文档不存在或无权访问");return row;}
    private BusinessException notFound(String message){return new BusinessException("not_found",message,404);}
    private BusinessException conflict(){return new BusinessException("document_conflict","文档状态已变化，请刷新",409);}
    private long number(Object value){return Long.parseLong(String.valueOf(value));}
    private void audit(Actor actor,long id,String action){audit.append(actor,action,"document",id,Map.of());}
}
