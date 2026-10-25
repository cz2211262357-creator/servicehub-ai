package cn.servicehub.common;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
@Service
public class DependencyStatus {
 private static final List<String> REQUIRED=List.of("database","redis","rabbitmq");
 private final List<DependencyProbe> probes;private final ExecutorService executor;
 public DependencyStatus(List<DependencyProbe> probes,@Qualifier("healthExecutor")ExecutorService executor){this.probes=List.copyOf(probes);this.executor=executor;}
 public Map<String,String> snapshot(){
  var states=new LinkedHashMap<String,String>();REQUIRED.forEach(name->states.put(name,"unavailable"));var pending=new LinkedHashMap<String,Future<Boolean>>();
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
  try{
   for(var probe:probes)if(REQUIRED.contains(probe.name()))pending.put(probe.name(),executor.submit(probe::isHealthy));
   for(var entry:pending.entrySet()){
    long remaining=deadline-System.nanoTime();if(remaining<=0)break;
    try{if(Boolean.TRUE.equals(entry.getValue().get(remaining,TimeUnit.NANOSECONDS)))states.put(entry.getKey(),"ok");}
    catch(InterruptedException e){Thread.currentThread().interrupt();break;}catch(Exception ignored){}
   }
  }finally{pending.values().forEach(f->f.cancel(true));}
  return Collections.unmodifiableMap(states);
 }
}
