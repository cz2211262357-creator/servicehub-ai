import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { ApiError, api, post } from "../../api/client";
import type { Schemas } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import {
  Badge,
  Empty,
  ErrorNotice,
  Loading,
  PageTitle,
  localTime,
} from "../../components/Feedback";
export function JobsPage() {
  const { user } = useAuth(),
    [state, setState] = useState("failed"),
    [cursor, setCursor] = useState<number | null>(null),
    [error, setError] = useState<unknown>(null);
  const query = useQuery({
    queryKey: ["jobs", user?.tenant_id, user?.id, state, cursor],
    queryFn: () =>
      api<Schemas["JobPage"]>(
        "/admin/jobs?limit=20" +
          (state ? "&state=" + state : "") +
          (cursor ? "&cursor=" + cursor : ""),
      ),
    enabled: user?.role === "admin",
    refetchInterval: 5000,
  });
  if (user?.role !== "admin") return <Empty title="仅管理员可查看任务中心" />;
  async function retry(id: number) {
    setError(null);
    try {
      await post("/admin/jobs/" + id + "/retry");
      void query.refetch();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) await query.refetch();
      setError(e);
    }
  }
  return (
    <>
      <PageTitle
        eyebrow="BACKGROUND TASKS"
        title="任务中心"
        description="查看模型回复、知识索引与后台恢复任务。仅仍有效的失败任务可手动重试。"
        action={
          <button className="button" onClick={() => void query.refetch()}>
            <RefreshCw size={14} />
            刷新
          </button>
        }
      />
      <div className="toolbar">
        <select
          aria-label="任务状态"
          value={state}
          onChange={(e) => {
            setState(e.target.value);
            setCursor(null);
          }}
        >
          <option value="">全部状态</option>
          {[
            ["failed", "最终失败"],
            ["queued", "待处理"],
            ["running", "执行中"],
            ["retrying", "重试中"],
            ["succeeded", "成功"],
            ["cancelled", "已取消"],
            ["waiting_input", "待补充信息"],
          ].map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>
      {error != null && <ErrorNotice error={error} />}
      <div className="card">
        {query.isLoading ? (
          <Loading />
        ) : query.error ? (
          <ErrorNotice error={query.error} />
        ) : !query.data?.items.length ? (
          <Empty title="没有符合条件的任务" />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>任务</th>
                  <th>状态</th>
                  <th>执行次数</th>
                  <th>错误代码</th>
                  <th>调度时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((job) => (
                  <tr key={job.id}>
                    <td>
                      <strong className="table-title">
                        {job.kind === "ai_reply"
                          ? "AI 回复"
                          : job.kind === "knowledge_index"
                            ? "知识索引"
                            : job.kind}
                      </strong>
                      <span className="table-subtitle">任务 #{job.id}</span>
                    </td>
                    <td>
                      <Badge value={job.state} />
                    </td>
                    <td>
                      {job.attempts}
                      <span className="table-subtitle">
                        执行版本 {job.execution_version}
                      </span>
                      <span className="table-subtitle">
                        {"累计 " +
                          (job.lifetime_attempts ?? job.attempts) +
                          " 次 · 人工重试 " +
                          (job.manual_retries ?? 0) +
                          " 次"}
                      </span>
                    </td>
                    <td>{job.error_code || "—"}</td>
                    <td>{localTime(job.available_at)}</td>
                    <td>
                      {job.state === "failed" && (
                        <button
                          className="button small"
                          onClick={() => void retry(job.id)}
                        >
                          <RefreshCw size={12} />
                          重试
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {(cursor || query.data?.next_cursor) && (
          <div className="pagination">
            {cursor && (
              <button className="button small" onClick={() => setCursor(null)}>
                首页
              </button>
            )}
            {query.data?.next_cursor && (
              <button
                className="button small"
                onClick={() => setCursor(query.data!.next_cursor!)}
              >
                下一页
              </button>
            )}
          </div>
        )}
      </div>
    </>
  );
}
