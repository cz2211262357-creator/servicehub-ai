package cn.servicehub.realtime;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LiveNotificationOrderTest {
 @Test void redisDispatchDoesNotReorderFragmentsWithinOneChannel() throws Exception{
  try(var executor=new LiveSocketConfiguration(null).liveNotifications()){
   var firstEntered=new CountDownLatch(1);var release=new CountDownLatch(1);var secondDone=new CountDownLatch(1);
   executor.submit(()->{firstEntered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   assertThat(firstEntered.await(2,TimeUnit.SECONDS)).isTrue();
   executor.submit(secondDone::countDown);
   try{assertThat(secondDone.await(100,TimeUnit.MILLISECONDS)).as("Later Redis frame must not overtake blocked earlier dispatch").isFalse();}
   finally{release.countDown();}
   assertThat(secondDone.await(2,TimeUnit.SECONDS)).isTrue();
  }
 }
}
