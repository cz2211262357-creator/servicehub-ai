package cn.servicehub.jobs;

import cn.servicehub.ai.ExecutionCancelledException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ExecutionPermit implements AutoCloseable {
    private final String owner;private final BooleanSupplier valid,renew;private final Runnable release;
    private final AtomicBoolean closed=new AtomicBoolean();private volatile boolean lost;
    private volatile ScheduledFuture<?> renewal;
    ExecutionPermit(String owner,BooleanSupplier valid,BooleanSupplier renew,Runnable release){this.owner=owner;this.valid=valid;this.renew=renew;this.release=release;}
    void schedule(ScheduledFuture<?> renewal){this.renewal=renewal;}
    public String owner(){return owner;}
    public boolean isValid(){return !closed.get()&&!lost&&valid.getAsBoolean();}
    public boolean renew(){if(closed.get()||lost)return false;boolean result=renew.getAsBoolean();if(!result)lost=true;return result;}
    public void requireValid(){if(!isValid())throw new ExecutionCancelledException("resource_lease_lost");}
    @Override public void close(){if(closed.compareAndSet(false,true)){var task=renewal;if(task!=null)task.cancel(false);release.run();}}
}
