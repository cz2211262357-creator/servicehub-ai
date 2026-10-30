import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search, ShieldCheck } from "lucide-react";
import { ApiError, api } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import {
  Badge,
  Empty,
  ErrorNotice,
  Loading,
  localTime,
} from "../../components/Feedback";
import { configuredCost, observed } from "./quality";
import type { RunTrace, TraceSpan } from "./quality";

function safeParameters(argumentsSummary: TraceSpan["arguments_summary"]) {
  const values: string[] = [];
  for (const key of ["order_number", "query", "reason"]) {
    const value = argumentsSummary?.[key];
    if (
      value &&
      typeof value === "object" &&
      "sha256" in value &&
      "length" in value &&
      typeof value.sha256 === "string" &&
      /^[a-f0-9]{64}$/i.test(value.sha256) &&
      typeof value.length === "number" &&
      Number.isSafeInteger(value.length) &&
      value.length >= 0
    ) {
      values.push(
        `${key}：长度 ${value.length} · SHA-256 ${value.sha256.slice(0, 12)}…`,
      );
    }
  }
  for (const [key, allowed] of [
    ["kind", ["RETURN", "EXCHANGE", "REPAIR"]],
    [
      "reason_code",
      ["NO_REASON", "QUALITY", "WRONG_ITEM", "LOGISTICS_DAMAGE", "OTHER"],
    ],
  ] as const) {
    const value = argumentsSummary?.[key];
    if (
      typeof value === "string" &&
      (allowed as readonly string[]).includes(value)
    )
      values.push(`${key}：${value}`);
  }
  return values;
}
const kindLabels: Record<string, string> = {
  model: "模型",
  tool: "工具",
  retrieval: "检索",
};
const outcomeLabels: Record<string, string> = {
  success: "成功",
  failed: "失败",
  rejected: "拒绝",
  cancelled: "已取消",
  running: "执行中",
};

export function RunTracePanel() {
  const { user } = useAuth();
  const [input, setInput] = useState("");
  const [runId, setRunId] = useState<number | null>(null);
  const [validation, setValidation] = useState("");
  const query = useQuery({
    queryKey: ["ai-run-trace", user?.tenant_id, user?.id, runId],
    queryFn: () => api<RunTrace>(`/admin/ai-runs/${runId}/trace`),
    enabled: user?.role === "admin" && runId !== null,
    retry: false,
  });
  if (user?.role !== "admin") return null;
  return (
    <section className="card trace-panel" aria-label="AI 调用轨迹">
      <div className="card-header">
        <div>
          <h3>AI 调用轨迹</h3>
          <p>管理员可查询本租户的运行、执行版本与每次调用。仅显示脱敏参数。</p>
        </div>
        <ShieldCheck size={18} />
      </div>
      <form
        className="trace-search"
        onSubmit={(event) => {
          event.preventDefault();
          const id = Number(input);
          if (!/^[1-9]\d*$/.test(input) || !Number.isSafeInteger(id)) {
            setValidation("请输入有效的正整数运行编号");
            return;
          }
          setValidation("");
          if (runId === id) void query.refetch();
          else setRunId(id);
        }}
      >
        <label htmlFor="ai-run-number">AI 运行编号</label>
        <input
          id="ai-run-number"
          inputMode="numeric"
          value={input}
          onChange={(event) => setInput(event.target.value.trim())}
          placeholder="例如 12"
        />
        <button className="button" type="submit">
          <Search size={14} />
          查询轨迹
        </button>
      </form>
      {validation && (
        <p className="error-notice" role="alert">
          {validation}
        </p>
      )}
      {query.isFetching ? (
        <Loading />
      ) : query.error ? (
        query.error instanceof ApiError && query.error.status === 404 ? (
          <Empty title="未找到本租户的运行记录，或当前服务尚未提供调用轨迹。" />
        ) : (
          <ErrorNotice error={query.error} />
        )
      ) : query.data ? (
        <>
          <div className="trace-run-heading">
            <strong>
              运行 #{query.data.run_id} · 会话 #{query.data.conversation_id}
            </strong>
            <Badge value={query.data.state} />
            {query.data.error_code && <code>{query.data.error_code}</code>}
          </div>
          {!query.data.spans.length ? (
            <Empty title="此运行尚无调用记录" />
          ) : (
            <ol className="trace-list">
              {query.data.spans.map((span) => (
                <li key={span.id}>
                  <div className="row spread">
                    <div>
                      <span className="table-subtitle">
                        {kindLabels[span.kind] ?? "调用"} · 执行版本{" "}
                        {span.execution_version}
                      </span>
                      <strong>{span.name}</strong>
                    </div>
                    <span>
                      {outcomeLabels[span.outcome] ?? "未知"} ·{" "}
                      {observed(span.latency_ms)} ms
                    </span>
                  </div>
                  <p className="trace-times">
                    开始 {localTime(span.started_at)} · 结束{" "}
                    {span.finished_at
                      ? localTime(span.finished_at)
                      : "尚未结束"}
                  </p>
                  {span.error_code && (
                    <p>
                      <code>{span.error_code}</code>
                    </p>
                  )}
                  {span.model && <p>模型：{span.model}</p>}
                  {span.kind === "model" && (
                    <p>
                      Token：输入 {observed(span.usage?.prompt_tokens)} / 输出{" "}
                      {observed(span.usage?.completion_tokens)} / 总计{" "}
                      {observed(span.usage?.total_tokens)} · 费用{" "}
                      {configuredCost(span) ?? "未知"}
                    </p>
                  )}
                  {safeParameters(span.arguments_summary).map((value) => (
                    <p className="trace-parameter" key={value}>
                      {value}
                    </p>
                  ))}
                </li>
              ))}
            </ol>
          )}
        </>
      ) : (
        <p className="trace-hint">
          输入运行编号以查看调用轨迹；可在任务中心的 AI
          回复任务中找到对应运行编号。
        </p>
      )}
    </section>
  );
}
