package cn.servicehub.ai;

public final class ExecutionCancelledException extends RuntimeException {
    public ExecutionCancelledException(String reason) { super(reason); }
}
