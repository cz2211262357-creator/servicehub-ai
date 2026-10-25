package cn.servicehub.operations;
import cn.servicehub.auth.*;
import cn.servicehub.common.*;
import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.jobs.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static cn.servicehub.operations.OperationsDtos.*;
@Service
public class OperationsService {
 private final OperationsMapper mapper;private final AuthorizationService authorization;private final JobCoordinator jobs;private final JobMapper jobRows;
 private final AuditService audit;private final JsonMapper json;private final ServiceHubProperties properties;private final DependencyStatus dependencies;
 public OperationsService(OperationsMapper mapper,AuthorizationService authorization,JobCoordinator jobs,JobMapper jobRows,AuditService audit,JsonMapper json,ServiceHubProperties properties,DependencyStatus dependencies){
  this.mapper=mapper;this.authorization=authorization;this.jobs=jobs;this.jobRows=jobRows;this.audit=audit;this.json=json;this.properties=properties;this.dependencies=dependencies;
 }
 public JobPage jobs(Actor actor,Long cursor,int limit,String state){
  admin(actor);authorization.requireCurrent(actor);
  List<String> states=state==null||state.isBlank()?null:switch(state.toLowerCase(Locale.ROOT)){case "retrying"->List.of("RETRY_WAIT");case "failed"->List.of("FAILED","TIMED_OUT","EXPIRED");case "cancelled"->List.of("CANCELLED","SUPERSEDED");default->List.of(state.toUpperCase(Locale.ROOT));};
  var rows=mapper.jobs(actor.tenantId(),cursor,limit+1,states);
  return new JobPage(rows.stream().limit(limit).map(this::dto).toList(),rows.size()>limit?rows.get(limit-1).id():null);
 }
 @Transactional public JobDto retry(Actor actor,long id){
  admin(actor);jobs.manualRetry(actor,id);var row=mapper.job(actor.tenantId(),id);
  audit.append(actor,"job.retried","job",id,Map.of("manual_retries",row.manualRetries(),"lifetime_attempts",row.lifetimeAttempts(),"budget_revision",row.budgetRevision()));return dto(row);
 }
 public AuditPage audits(Actor actor,Long cursor,int limit,String action,String outcome){
  admin(actor);authorization.requireCurrent(actor);var rows=mapper.audits(actor.tenantId(),cursor,limit+1,blank(action),blank(outcome));
  return new AuditPage(rows.stream().limit(limit).map(r->new AuditDto(r.id(),r.tenantId(),r.actorId(),r.action(),r.targetType(),r.targetId(),r.outcome(),r.requestId(),object(r.detailsText()),r.createdAt())).toList(),rows.size()>limit?rows.get(limit-1).id():null);
 }
 public RuntimeStatusDto runtime(Actor actor){
  staff(actor);authorization.requireCurrent(actor);var p=properties.model();
  return new RuntimeStatusDto("online",p.name(),configured(p.baseUrl(),p.name(),p.apiKey()),p.embeddingName(),p.embeddingDimensions(),configured(p.embeddingBaseUrl(),p.embeddingName(),p.embeddingApiKey()),
   properties.limits().totalModelSlots(),properties.limits().maxOutputTokens(),properties.limits().maxUploadBytes(),Math.max(0,properties.worker().maxAttempts()-1),dependencies.snapshot());
 }
 public MetricsSummary summary(Actor actor,String start,String end){
  staff(actor);authorization.requireCurrent(actor);Instant until=end==null?jobRows.now():time(end),since=start==null?until.minus(Duration.ofDays(7)):time(start);
  if(!since.isBefore(until)||Duration.between(since,until).compareTo(Duration.ofDays(366))>0)throw window();
  long tenant=actor.tenantId();var counts=mapper.conversationCounts(tenant,since,until);long handoff=mapper.handoffs(tenant,since,until);
  var states=new TreeMap<String,Long>();mapper.jobStates(tenant,since,until).forEach(row->states.merge(wire(row.state()),row.count(),Long::sum));
  var usage=mapper.usage(tenant,since,until);
  return new MetricsSummary(since,until,counts.total(),counts.waiting(),counts.human(),handoff,counts.total()==0?null:(double)handoff/counts.total(),mapper.firstResponse(tenant,since,until),states.getOrDefault("failed",0L),Map.copyOf(states),usage.total(),usage.known(),usage.known()==0?null:usage.tokens(),mapper.daily(tenant,since,until));
 }
 public String prometheus(Actor actor){
  admin(actor);var data=summary(actor,null,null);
  return "# HELP servicehub_conversations Tenant scoped conversations\n# TYPE servicehub_conversations gauge\nservicehub_conversations "+data.conversations()+"\n# HELP servicehub_waiting Tenant scoped waiting\n# TYPE servicehub_waiting gauge\nservicehub_waiting "+data.waiting()+"\n# HELP servicehub_failed_jobs Tenant scoped failed jobs\n# TYPE servicehub_failed_jobs gauge\nservicehub_failed_jobs "+data.failedJobs()+"\n";
 }
 private JobDto dto(OperationsMapper.JobRow row){return new JobDto(row.id(),row.tenantId(),row.kind(),wire(row.state()),legacyPayload(row),row.executionVersion(),row.attempts(),row.errorCode(),row.availableAt(),row.finishedAt(),row.manualRetries(),row.lifetimeAttempts(),row.budgetRevision(),row.deadlineAt());}
 private Map<String,Object> legacyPayload(OperationsMapper.JobRow row){
  var result=new LinkedHashMap<>(object(row.payloadText()));
  if("knowledge_index".equals(row.kind())&&result.containsKey("version_id"))result.put("document_version_id",result.get("version_id"));
  return result;
 }
 @SuppressWarnings("unchecked") private Map<String,Object> object(String text){return json.readValue(text,Map.class);}
 public static String wire(String value){return switch(value.toUpperCase(Locale.ROOT)){case "RETRY_WAIT"->"retrying";case "TIMED_OUT","EXPIRED"->"failed";case "SUPERSEDED"->"cancelled";default->value.toLowerCase(Locale.ROOT);};}
 private Instant time(String text){try{return OffsetDateTime.parse(text).toInstant();}catch(DateTimeException e){throw window();}}
 private BusinessException window(){return new BusinessException("invalid_time_window","请选择有效的带时区时间窗口，最长一年",422);}
 private String blank(String value){return value==null||value.isEmpty()?null:value;}
 private boolean configured(String...values){return Arrays.stream(values).allMatch(value->value!=null&&!value.isBlank());}
 private void admin(Actor actor){if(actor.role()!=Role.ADMIN)throw new BusinessException("forbidden","当前账号无权执行此操作",403);}
 private void staff(Actor actor){if(actor.role()==Role.CUSTOMER)throw new BusinessException("forbidden","当前账号无权执行此操作",403);}
}
