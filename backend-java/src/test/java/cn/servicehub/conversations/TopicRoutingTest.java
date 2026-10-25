package cn.servicehub.conversations;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TopicRoutingTest {
    Object decide(String body,Function<String,Object> classifier){
        try{return Class.forName("cn.servicehub.conversations.TopicRouter").getMethod("decide",String.class,Function.class).invoke(null,body,classifier);}
        catch(InvocationTargetException e){throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError("Topic rules not implemented",e);}
    }
    String kind(Object d){return String.valueOf(TopicFixture.get(d,"kind"));}
    @Test void ordinaryQuestionsAndFollowupsDoNotCallClassifier(){
        var calls=new AtomicInteger();
        for(String text:new String[]{"查询退货政策","那运费呢","多久能处理","查询订单 ORD-A-001"}){
            assertThat(kind(decide(text,s->{calls.incrementAndGet();return null;}))).isEqualTo("CONTINUE");
        }
        assertThat(calls).hasValue(0);
    }
    @Test void changingOrderIsRevisionAndMissingNumberRequiresClarification(){
        var decision=decide("刚才那个订单不用查了，查另一个订单",s->{throw new AssertionError("No classification needed");});
        assertThat(kind(decision)).isEqualTo("REVISE");assertThat(TopicFixture.get(decision,"requiresClarification")).isEqualTo(true);
    }
    @Test void revisionWithExplicitOrderDoesNotReuseOldTarget(){
        var decision=decide("换订单 ORD-A-002",s->{throw new AssertionError("No classification needed");});
        assertThat(kind(decision)).isEqualTo("REVISE");assertThat(TopicFixture.get(decision,"requiresClarification")).isEqualTo(false);
    }
    @Test void explicitNewTopicIsDeterministic(){assertThat(kind(decide("另开话题：发票",s->{throw new AssertionError("No classification needed");}))).isEqualTo("NEW_TOPIC");}
    @Test void explicitResumeNamesTargetButDoesNotGrantAccess(){
        var decision=decide("回到话题 123",s->{throw new AssertionError("No classification needed");});assertThat(kind(decision)).isEqualTo("RESUME");assertThat(TopicFixture.get(decision,"topicId")).isEqualTo(123L);
    }
    @Test void genuinelyConflictingReferenceCallsClassifierAtMostOnceAndInvalidResultClarifies(){
        var calls=new AtomicInteger();var result=decide("接着那个做，还是当前这个？",s->{calls.incrementAndGet();return null;});
        assertThat(kind(result)).isEqualTo("AMBIGUOUS");assertThat(calls).hasValue(1);
    }
    @org.junit.jupiter.api.Test void explicitOrderLabelsAcceptMerchantOrderNumbersWithoutFixturePrefixes(){
        assertThat(TopicRouter.explicitOrders("查询订单 SH20261021001")).isEqualTo(java.util.Set.of("SH20261021001"));
        assertThat(TopicRouter.explicitOrders("请查订单号：SH20261021001")).isEqualTo(java.util.Set.of("SH20261021001"));
        assertThat(TopicRouter.explicitOrders("这个订单可以退吗")).isEmpty();
    }
}
