package cn.servicehub.operations;
import cn.servicehub.common.Actor;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
@Service
public class AuditService {
 private static final Set<String> FIELDS=Set.of("manual_retries","lifetime_attempts","budget_revision","role","is_active","display_name","requested_status","from","to");
 private final OperationsMapper mapper;private final JsonMapper json;
 public AuditService(OperationsMapper mapper,JsonMapper json){this.mapper=mapper;this.json=json;}
 @Transactional(propagation=Propagation.MANDATORY) public void append(Actor actor,String action,String resourceType,long resourceId,Map<String,Object> safeDetails){
  append(actor,action,resourceType,resourceId,"success",Objects.requireNonNullElse(MDC.get("request_id"),"-"),safeDetails);
 }
 @Transactional(propagation=Propagation.MANDATORY) public void append(Actor actor,String action,String resourceType,long resourceId,String outcome,String requestId,Map<String,Object> safeDetails){
  if(!FIELDS.containsAll(safeDetails.keySet())||resourceId<=0||!Set.of("success","rejected").contains(outcome))throw new IllegalArgumentException("Invalid audit summary");
  String encoded=json.writeValueAsString(safeDetails);if(encoded.length()>4096)throw new IllegalArgumentException("Audit summary too large");
  mapper.audit(Map.of("tenantId",actor.tenantId(),"actorId",actor.userId(),"action",action,"targetType",resourceType,"targetId",String.valueOf(resourceId),"outcome",outcome,"requestId",requestId,"details",encoded));
 }
}
