package cn.servicehub.operations;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RuntimeMetricsTest {
 Object meter(MeterRegistry registry){try{return Class.forName("cn.servicehub.common.RuntimeMetrics").getConstructor(MeterRegistry.class).newInstance(registry);}catch(ReflectiveOperationException e){return fail("Bounded runtime metrics are missing",e);}}
 void call(Object bean,String name,Class<?>[] types,Object...args){try{bean.getClass().getMethod(name,types).invoke(bean,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}catch(ReflectiveOperationException e){fail("Runtime metric contract is missing",e);}}
 @Test void privateIdsAndProviderErrorsCannotCreateUnboundedLabels(){
  var registry=new SimpleMeterRegistry();try{var bean=meter(registry);
   for(int i=0;i<100;i++)call(bean,"record",new Class<?>[]{String.class,String.class,String.class,Duration.class},"model","private-run-"+i,"url-secret-"+i,Duration.ofMillis(2));
   assertThat(registry.getMeters()).hasSize(1);var id=registry.getMeters().get(0).getId();
   assertThat(id.getTag("kind")).isEqualTo("other");assertThat(id.getTag("outcome")).isEqualTo("failed");assertThat(id.toString()).doesNotContain("private-run","url-secret");
  }finally{registry.close();}
 }
 @Test void unknownUsageNeverRegistersAnInventedZeroTokenObservation(){
  var registry=new SimpleMeterRegistry();try{var bean=meter(registry);
   call(bean,"usage",new Class<?>[]{String.class,Long.class},"chat",null);
   assertThat(registry.find("servicehub.model.tokens").counter()).isNull();
   assertThat(registry.get("servicehub.model.usage").tags("kind","chat","status","unknown").counter().count()).isEqualTo(1);
   call(bean,"usage",new Class<?>[]{String.class,Long.class},"chat",0L);
   assertThat(registry.get("servicehub.model.tokens").tags("kind","chat").counter().count()).isZero();
   assertThat(registry.get("servicehub.model.usage").tags("kind","chat","status","known").counter().count()).isEqualTo(1);
  }finally{registry.close();}
 }
}
