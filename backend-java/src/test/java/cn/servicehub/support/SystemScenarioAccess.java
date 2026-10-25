package cn.servicehub.support;
import java.lang.reflect.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** Test-only adapter: absence is a failure, never a skip or an emulated successful report. */
public final class SystemScenarioAccess implements AutoCloseable {
 private Object fixture;
 public Map<String,Object> run(String scenario){
  try{
   if(fixture==null)fixture=Class.forName("cn.servicehub.support.MultiProcessFixture").getConstructor().newInstance();
   @SuppressWarnings("unchecked") var result=(Map<String,Object>)fixture.getClass().getMethod("run",String.class).invoke(fixture,scenario);return result;
  }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw new AssertionError(e.getCause());}
  catch(ReflectiveOperationException e){return fail("Real multi-process scenario is not implemented: "+scenario,e);}
 }
 @SuppressWarnings("unchecked") public static List<Map<String,Object>> rounds(Map<String,Object> result){
  var rows=(List<Map<String,Object>>)result.get("rounds");assertThat(rows).hasSize(20);return rows;
 }
 public static long number(Map<String,Object> result,String name){assertThat(result.get(name)).as(name).isInstanceOf(Number.class);return ((Number)result.get(name)).longValue();}
 public static void processes(Map<String,Object> result){
  var pids=(List<?>)result.get("api_pids");assertThat(pids).hasSize(2);assertThat(new HashSet<>(pids)).hasSize(2);
  for(Object pid:pids)assertThat(((Number)pid).longValue()).isNotEqualTo(ProcessHandle.current().pid());
 }
 @Override public void close(){if(fixture instanceof AutoCloseable owned)try{owned.close();}catch(Exception e){throw new AssertionError("Owned fixture cleanup failed",e);}}
}
