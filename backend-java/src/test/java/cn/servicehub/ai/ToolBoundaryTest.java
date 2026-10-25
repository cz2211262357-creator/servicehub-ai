package cn.servicehub.ai;
import cn.servicehub.common.BusinessException;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ToolBoundaryTest {
    Object invoke(String name,Class<?>[] types,Object...args){
        try{return Class.forName("cn.servicehub.ai.ToolRegistry").getMethod(name,types).invoke(null,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){return fail("Missing tool boundary "+name,e);}
    }
    Object validate(String name,Map<String,Object> args){return invoke("validate",new Class<?>[]{String.class,Map.class},name,args);}
    @Test void schemasExposeOnlyThreeApprovedTools(){
        var schemas=(List<?>)invoke("schemas",new Class<?>[]{});
        assertThat(schemas).hasSize(3);assertThat(schemas.stream().map(s->String.valueOf(((Map<?,?>)s).get("name")))).containsExactly("search_policy","lookup_order","prepare_after_sale");
        assertThat(schemas).allSatisfy(s->assertThat(((Map<?,?>)((Map<?,?>)s).get("parameters")).get("additionalProperties")).isEqualTo(false));
    }
    @Test void irreversibleAndUnknownToolsAreRejected(){
        for(var name:List.of("confirm_after_sale","refund","payment","execute_sql","LOOKUP_ORDER"))assertThatThrownBy(()->validate(name,Map.of())).isInstanceOf(BusinessException.class);
    }
    @Test void callerCannotSupplyActorOrTenantThroughToolArguments(){
        for(var name:List.of("actor_id","tenant_id","customer_id","auth_version")){
            var args=new LinkedHashMap<String,Object>();args.put("order_number","ORD-A-001");args.put(name,5001);
            assertThatThrownBy(()->validate("lookup_order",args)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void requiredFieldTypesAndUnknownFieldsAreStrict(){
        assertThatThrownBy(()->validate("lookup_order",Map.of("order_number",6001))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->validate("search_policy",Map.of("query",""))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->validate("search_policy",Map.of("query","政策","extra",true))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->validate("prepare_after_sale",Map.of("order_number","ORD-A-001","kind","REFUND","reason","退货"))).isInstanceOf(BusinessException.class);
        assertThat(validate("prepare_after_sale",Map.of("order_number","ORD-A-001","kind","RETURN","reason","质量问题"))).isNotNull();
    }
}
