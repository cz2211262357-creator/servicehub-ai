package cn.servicehub.support;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
/** Deliberately excludes model latency; online model observations are in a separate report. */
public final class BenchmarkMain {
 public static void main(String[] args)throws Exception{
  var report=new VerificationReport(args);
  try(var fixture=new MultiProcessFixture()){
   report.step("initialize-owned-system");fixture.initialize();var c=fixture.fresh(0);var stages=new ArrayList<Map<String,Object>>();
   for(int concurrency:new int[]{1,10,20}){
    report.step("ordinary-http-"+concurrency);var durations=new ArrayList<Double>();var statuses=new ArrayList<Integer>();long start=System.nanoTime();
    try(var pool=Executors.newFixedThreadPool(concurrency)){
     var work=new ArrayList<Future<Map<String,Object>>>();
     for(int i=0;i<100;i++){int index=i;work.add(pool.submit(()->{
      long before=System.nanoTime();var response=fixture.request(index,"GET",(index&1)==0?"/api/v1/orders":"/api/v1/conversations",null,c.customer());fixture.require(response,200);
      assertThat(response.body().path("items")).isNotEmpty();return Map.of("ms",(System.nanoTime()-before)/1000000.0,"status",response.status());
     }));}
     for(var item:work){var value=item.get(30,TimeUnit.SECONDS);durations.add(((Number)value.get("ms")).doubleValue());statuses.add(((Number)value.get("status")).intValue());}
    }
    double seconds=(System.nanoTime()-start)/1e9;durations.sort(Double::compare);
    stages.add(Map.of("concurrency",concurrency,"requests",durations.size(),"errors",statuses.stream().filter(status->status!=200).count(),"elapsed_seconds",seconds,"requests_per_second",100/seconds,"p50_ms",percentile(durations,0.50),"p95_ms",percentile(durations,0.95),"p99_ms",percentile(durations,0.99),"maximum_ms",durations.getLast()));
    report.data.put("ordinary_http",stages);report.save();
   }
   report.data.put("api_pids",List.of(fixture.first.pid(),fixture.second.pid()));report.data.put("scope","Local synthetic-data HTTP measurements; model latency and production capacity are not inferred from these values.");report.finish();
  }catch(Throwable error){report.failed(error);System.err.println("Benchmark failed at "+report.step+" ("+error.getClass().getSimpleName()+")");System.exit(1);}
 }
 static double percentile(List<Double> sorted,double fraction){return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*fraction)-1));}
}
