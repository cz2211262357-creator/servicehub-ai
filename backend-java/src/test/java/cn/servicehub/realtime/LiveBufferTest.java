package cn.servicehub.realtime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LiveBufferTest {
    @Test void blockedClientHasExactly128PendingEventsAndCloses1013() throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var code=new AtomicInteger();
        Consumer<String> send=ignored->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        Object sender;
        try{sender=Class.forName("cn.servicehub.realtime.LiveEventHub$BufferedSender").getConstructor(Consumer.class,IntConsumer.class).newInstance(send,(IntConsumer)code::set);}
        catch(ReflectiveOperationException e){fail("Bounded live sender is missing",e);return;}
        var offer=sender.getClass().getMethod("offer",String.class);
        try{
            assertThat(offer.invoke(sender,"in-flight")).isEqualTo(true);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<128;i++)assertThat(offer.invoke(sender,"event-"+i)).isEqualTo(true);
            assertThat(offer.invoke(sender,"overflow")).isEqualTo(false);assertThat(code.get()).isEqualTo(1013);
        }finally{release.countDown();((AutoCloseable)sender).close();}
    }

    @Test void closingConnectionDoesNotInterruptInFlightDatabasePoll() throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        Consumer<String> poll=ignored->{entered.countDown();try{release.await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}};
        var sender=new LiveEventHub.BufferedSender(poll,ignored->{});
        try{
            sender.offer("database poll");assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();sender.close();
            assertThat(interrupted.await(100,TimeUnit.MILLISECONDS)).as("Connection cleanup must not interrupt a pooled JDBC operation").isFalse();
        }finally{release.countDown();sender.close();}
    }
}
