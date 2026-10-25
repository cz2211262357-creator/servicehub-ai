package cn.servicehub.ai;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;
import okhttp3.Call;

/** One model request owns one transport cancellation scope, including body consumption. */
public final class HttpCallCancellation {
    private static final ThreadLocal<HttpCallCancellation> CURRENT=new ThreadLocal<>();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final AtomicReference<Call> active=new AtomicReference<>();
    public HttpCallCancellation(){}
    public void register(Call call){active.set(call);if(cancelled.get())call.cancel();}
    public void cancel(){cancelled.set(true);Call call=active.get();if(call!=null)call.cancel();}
    static void registerCurrent(Call call){var scope=CURRENT.get();if(scope!=null)scope.register(call);}
    <T> T bound(Callable<T> action) throws Exception {
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested model transport scope");
        CURRENT.set(this);
        try{return action.call();}finally{active.set(null);CURRENT.remove();}
    }
}
