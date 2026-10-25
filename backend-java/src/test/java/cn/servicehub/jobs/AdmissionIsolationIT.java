package cn.servicehub.jobs;

import cn.servicehub.ai.CancellationGuard;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static cn.servicehub.jobs.JobRuntimeFixture.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdmissionIsolationIT {
    final JobRuntimeFixture f=new JobRuntimeFixture();
    @BeforeAll void start() throws Exception {f.start();}
    @BeforeEach void reset(){f.reset();}
    @AfterAll void stop(){f.close();}
    Optional<?> acquire(Object client,ExecutionClass type,String owner){return (Optional<?>)call(client,"tryAcquire",new Class<?>[]{ExecutionClass.class,String.class},type,owner);}
    @Test void threeBackgroundPermitsReserveOneInteractiveSlotAcrossClients() throws Exception {
        var first=f.admission();var constructor=first.getClass().getSuperclass()==Object.class?first.getClass().getConstructors()[0]:Class.forName("cn.servicehub.jobs.AdmissionController").getConstructors()[0];
        Object[] args=Arrays.stream(constructor.getParameterTypes()).map(t->f.app.context.getBean(t)).toArray();Object second=constructor.newInstance(args);
        var held=new ArrayList<AutoCloseable>();
        try{
            for(int i=0;i<3;i++){var permit=acquire(i%2==0?first:second,ExecutionClass.BACKGROUND,"bg-"+i);assertThat(permit).isPresent();held.add((AutoCloseable)permit.orElseThrow());}
            assertThat(acquire(second,ExecutionClass.BACKGROUND,"fourth-bg")).isEmpty();
            long start=System.nanoTime();var interactive=acquire(second,ExecutionClass.INTERACTIVE,"reserved-front");assertThat(interactive).isPresent();
            held.add((AutoCloseable)interactive.orElseThrow());assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
            assertThat(acquire(first,ExecutionClass.INTERACTIVE,"fifth-total")).isEmpty();
        }finally{for(var permit:held)permit.close();((AutoCloseable)second).close();}
    }
    @Test void ownerCannotDoubleAcquireAndStaleReleaseCannotRemoveAnotherOwner() throws Exception {
        Object admission=f.admission();var a=acquire(admission,ExecutionClass.BACKGROUND,"owner-a").orElseThrow();
        assertThat(acquire(admission,ExecutionClass.BACKGROUND,"owner-a")).isEmpty();
        ((AutoCloseable)a).close();var b=acquire(admission,ExecutionClass.BACKGROUND,"owner-b").orElseThrow();
        ((AutoCloseable)a).close();assertThat((Boolean)get(b,"isValid")).isTrue();((AutoCloseable)b).close();
    }
    @Test void modelSlotsAlsoReserveInteractiveCapacityWhileLongActionsBlock() throws Exception {
        Object slots=f.app.requiredBean("cn.servicehub.ai.GlobalModelSlots");var entered=new CountDownLatch(3);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var pending=new ArrayList<Future<?>>();
            for(int i=0;i<3;i++)pending.add(pool.submit(()->model(slots,ExecutionClass.BACKGROUND,Duration.ofSeconds(2),()->{
                entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Model gate timed out");return "background";
            })));
            try{
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->model(slots,ExecutionClass.BACKGROUND,Duration.ofMillis(150),()->"must-not-start")).isInstanceOf(RuntimeException.class);
                long start=System.nanoTime();assertThat(model(slots,ExecutionClass.INTERACTIVE,Duration.ofSeconds(1),()->"foreground")).isEqualTo("foreground");
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
            }finally{release.countDown();}
            for(var future:pending)future.get(3,TimeUnit.SECONDS);
        }
    }
    static Object model(Object slots,ExecutionClass type,Duration wait,Callable<?> action){
        return call(slots,"withPermit",new Class<?>[]{ExecutionClass.class,Duration.class,Callable.class,CancellationGuard.class},type,wait,action,(CancellationGuard)()->{});
    }
}
