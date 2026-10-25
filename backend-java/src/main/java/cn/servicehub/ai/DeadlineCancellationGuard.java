package cn.servicehub.ai;
import java.time.Instant;
/** A guard bound to the persisted whole-execution deadline, never a fresh per-call budget. */
public interface DeadlineCancellationGuard extends CancellationGuard { Instant deadlineAt(); cn.servicehub.jobs.ExecutionClass executionClass(); }
