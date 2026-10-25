package cn.servicehub.common;
import cn.servicehub.ai.ExecutionCancelledException;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
/** Technical process metrics use finite labels; tenant business totals remain in scoped SQL. */
@Service
public class RuntimeMetrics {
 private static final Set<String> STAGES=Set.of("queue","execution","model","model_wait","tool","cancel");
 private static final Set<String> KINDS=Set.of("ai_reply","knowledge_index","chat","embedding","search_policy","lookup_order","prepare_after_sale");
 private static final Set<String> OUTCOMES=Set.of("success","failed","cancelled","retry","waiting_input");
 private final MeterRegistry registry;
 public RuntimeMetrics(MeterRegistry registry){this.registry=registry;}
 public void record(String stage,String kind,String outcome,Duration duration){
  if(duration==null||duration.isNegative())return;
  Timer.builder("servicehub.stage.duration").tags("stage",safe(STAGES,stage,"other"),"kind",safe(KINDS,kind,"other"),"outcome",safe(OUTCOMES,outcome,"failed"))
   .publishPercentileHistogram().register(registry).record(duration);
 }
 public <T> T timed(String stage,String kind,Supplier<T> operation){
  long started=System.nanoTime();String outcome="success";
  try{return operation.get();}catch(ExecutionCancelledException e){outcome="cancelled";throw e;}catch(RuntimeException e){outcome="failed";throw e;}
  finally{record(stage,kind,outcome,Duration.ofNanos(System.nanoTime()-started));}
 }
 /** Only completed provider responses have a usage record; a missing total stays unknown. */
 public void usage(String kind,Long actualTotal){
  String label=safe(KINDS,kind,"other");boolean known=actualTotal!=null&&actualTotal>=0;
  registry.counter("servicehub.model.usage","kind",label,"status",known?"known":"unknown").increment();
  if(known)registry.counter("servicehub.model.tokens","kind",label).increment(actualTotal);
 }
 private String safe(Set<String> allowed,String value,String fallback){return value!=null&&allowed.contains(value)?value:fallback;}
}
