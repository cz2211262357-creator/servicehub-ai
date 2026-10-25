package cn.servicehub.operations;
import cn.servicehub.auth.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import static cn.servicehub.operations.OperationsDtos.*;
@RestController
public class OperationsController {
 private final OperationsService operations;
 public OperationsController(OperationsService operations){this.operations=operations;}
 @GetMapping("/api/v1/admin/jobs") public JobPage jobs(HttpServletRequest request,@RequestParam(required=false)@Min(1)Long cursor,@RequestParam(defaultValue="30")@Min(1)@Max(100)int limit,@RequestParam(required=false)String state){return operations.jobs(AuthController.actor(request),cursor,limit,state);}
 @PostMapping("/api/v1/admin/jobs/{job_id}/retry") public JobDto retry(HttpServletRequest request,@PathVariable("job_id")long id){return operations.retry(AuthController.actor(request),id);}
 @GetMapping("/api/v1/admin/audits") public AuditPage audits(HttpServletRequest request,@RequestParam(required=false)@Min(1)Long cursor,@RequestParam(defaultValue="30")@Min(1)@Max(100)int limit,@RequestParam(required=false)String action,@RequestParam(required=false)String outcome){return operations.audits(AuthController.actor(request),cursor,limit,action,outcome);}
 @GetMapping("/api/v1/admin/metrics/summary") public MetricsSummary summary(HttpServletRequest request,@RequestParam(required=false)String start,@RequestParam(required=false)String end){return operations.summary(AuthController.actor(request),start,end);}
 @GetMapping("/api/v1/admin/runtime") public RuntimeStatusDto runtime(HttpServletRequest request){return operations.runtime(AuthController.actor(request));}
 @GetMapping(value="/api/v1/metrics",produces="text/plain; version=0.0.4; charset=utf-8") public String metrics(HttpServletRequest request){return operations.prometheus(AuthController.actor(request));}
}
