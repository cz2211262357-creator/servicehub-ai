package cn.servicehub.common;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public final class HealthController {
 private final DependencyStatus dependencies;
 public HealthController(DependencyStatus dependencies){this.dependencies=dependencies;}
 @GetMapping("/api/v1/health/live") public Map<String,String> liveness(){return Map.of("status","ok","service","servicehub-ai","version","0.1.0");}
 @GetMapping("/api/v1/health/ready") public ResponseEntity<Map<String,Object>> readiness(){
  var status=dependencies.snapshot();
  if(status.values().stream().anyMatch(value->!"ok".equals(value)))throw new BusinessException("dependencies_unavailable","Database or cache is unavailable",503,Map.of("dependencies",status));
  return ResponseEntity.ok(Map.of("status","ready","database","ok","redis","ok","rabbitmq","ok"));
 }
}
