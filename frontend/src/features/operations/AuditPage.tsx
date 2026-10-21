import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import type { Schemas } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import {
  Empty,
  ErrorNotice,
  Loading,
  PageTitle,
  localTime,
} from "../../components/Feedback";
export function AuditPage() {
  const { user } = useAuth(),
    [outcome, setOutcome] = useState(""),
    [cursor, setCursor] = useState<number | null>(null);
  const query = useQuery({
    queryKey: ["audits", user?.tenant_id, user?.id, outcome, cursor],
    queryFn: () =>
      api<Schemas["AuditPage"]>(
        "/admin/audits?limit=20" +
          (outcome ? "&outcome=" + outcome : "") +
          (cursor ? "&cursor=" + cursor : ""),
      ),
    enabled: user?.role === "admin",
  });
  if (user?.role !== "admin") return <Empty title="仅管理员可查看操作审计" />;
  return (
    <>
      <PageTitle
        eyebrow="AUDIT TRAIL"
        title="操作审计"
        description="追踪敏感操作的执行主体、对象、结果及关联请求。"
      />
      <div className="toolbar">
        <select
          aria-label="审计结果"
          value={outcome}
          onChange={(e) => {
            setOutcome(e.target.value);
            setCursor(null);
          }}
        >
          <option value="">全部结果</option>
          <option value="success">成功</option>
          <option value="rejected">已拒绝</option>
        </select>
      </div>
      <div className="card">
        {query.isLoading ? (
          <Loading />
        ) : query.error ? (
          <ErrorNotice error={query.error} />
        ) : !query.data?.items.length ? (
          <Empty title="暂无操作记录" />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>操作</th>
                  <th>执行主体</th>
                  <th>目标</th>
                  <th>结果</th>
                  <th>关联请求</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((record) => (
                  <tr key={record.id}>
                    <td className="table-title">{record.action}</td>
                    <td>
                      {record.actor_id ? "成员 #" + record.actor_id : "系统"}
                    </td>
                    <td>
                      {record.target_type} #{record.target_id}
                    </td>
                    <td>
                      <span
                        className={
                          "badge " +
                          (record.outcome === "success"
                            ? "status-succeeded"
                            : "status-rejected")
                        }
                      >
                        <i />
                        {record.outcome === "success" ? "成功" : "已拒绝"}
                      </span>
                    </td>
                    <td>
                      <code className="audit-request">{record.request_id}</code>
                    </td>
                    <td>{localTime(record.created_at)}</td>
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
