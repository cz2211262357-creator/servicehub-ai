import type { Schemas, PythonSchemas } from "../../api/client";

// Python quality fields are optional so the shared UI still accepts Java metrics.
export type AIQualityMetrics = Partial<
  Pick<
    PythonSchemas["MetricsSummary"],
    | "ai_succeeded"
    | "ai_failed"
    | "ai_cancelled"
    | "ai_failure_rate"
    | "ai_terminal_samples"
    | "ai_p95_latency_ms"
    | "ai_latency_samples"
    | "model_calls"
    | "tool_calls"
    | "retrieval_calls"
    | "prompt_tokens"
    | "completion_tokens"
    | "usage_coverage"
    | "usage_complete_records"
    | "usage_unknown_records"
    | "estimated_cost"
    | "cost_currency"
    | "price_version"
    | "cost_known_records"
  >
>;
export type CompatibleMetrics = Omit<
  Schemas["MetricsSummary"],
  keyof AIQualityMetrics
> &
  AIQualityMetrics;
export type TraceSpan = PythonSchemas["TraceSpanDTO"];
export type RunTrace = PythonSchemas["RunTraceDTO"];
export const observed = (value: number | null | undefined) =>
  value == null ? "未知" : value.toLocaleString();
export const rate = (value: number | null | undefined) =>
  value == null ? "未知" : (value * 100).toFixed(1) + "%";
export function configuredCost(
  data: Pick<
    AIQualityMetrics,
    "estimated_cost" | "cost_currency" | "price_version"
  >,
) {
  return data.estimated_cost != null && data.cost_currency && data.price_version
    ? `${data.cost_currency} ${data.estimated_cost}`
    : null;
}
