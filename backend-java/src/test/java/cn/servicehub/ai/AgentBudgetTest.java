package cn.servicehub.ai;
import cn.servicehub.common.BusinessException;
import java.lang.reflect.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AgentBudgetTest {
    Object invoke(String name,Class<?>[] types,Object...args){
        try{return Class.forName("cn.servicehub.ai.ModelBudget").getMethod(name,types).invoke(null,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){return fail("Missing budget contract "+name,e);}
    }
    @Test void sixthReservedModelRequestIsLastAllowed(){
        for(int count=0;count<6;count++)invoke("checkReservation",new Class<?>[]{String.class,int.class},"model",count);
        assertThatThrownBy(()->invoke("checkReservation",new Class<?>[]{String.class,int.class},"model",6)).isInstanceOf(BusinessException.class);
    }
    @Test void fourthReservedToolExecutionIsLastAllowed(){
        for(int count=0;count<4;count++)invoke("checkReservation",new Class<?>[]{String.class,int.class},"tool",count);
        assertThatThrownBy(()->invoke("checkReservation",new Class<?>[]{String.class,int.class},"tool",4)).isInstanceOf(BusinessException.class);
    }
    @Test void eachCallTakesSmallerOfFortyFiveSecondsAndWholeRunRemaining(){
        assertThat(invoke("callTimeout",new Class<?>[]{Duration.class},Duration.ofSeconds(120))).isEqualTo(Duration.ofSeconds(45));
        assertThat(invoke("callTimeout",new Class<?>[]{Duration.class},Duration.ofMillis(1500))).isEqualTo(Duration.ofMillis(1500));
        assertThatThrownBy(()->invoke("callTimeout",new Class<?>[]{Duration.class},Duration.ZERO)).isInstanceOf(ExecutionCancelledException.class);
    }
}
