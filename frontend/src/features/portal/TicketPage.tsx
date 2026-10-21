import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/client";
import type { Schemas } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import {
  Badge,
  Empty,
  ErrorNotice,
  Loading,
  Modal,
  PageTitle,
  localTime,
} from "../../components/Feedback";

const KIND: Record<string, string> = {
  RETURN: "退货",
  EXCHANGE: "换货",
  REPAIR: "维修",
};
export function TicketPage() {
  const user = useAuth().user;
  const customer = user?.role === "customer",
    cache = useQueryClient();
  const [selected, setSelected] = useState<number | null>(null),
    [cursor, setCursor] = useState<number | null>(null),
    [status, setStatus] = useState(""),
    [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false),
    [note, setNote] = useState("");
  const query = useQuery({
    queryKey: ["tickets", user?.tenant_id, user?.id, cursor, status],
    queryFn: () =>
      api<Schemas["TicketPage"]>(
        "/after-sales?limit=20" +
          (cursor ? "&cursor=" + cursor : "") +
          (status ? "&status=" + status : ""),
      ),
    refetchInterval: 10000,
  });
  const detail = useQuery({
    queryKey: ["ticket", user?.tenant_id, user?.id, selected],
    queryFn: () => api<Schemas["TicketDTO"]>("/after-sales/" + selected),
    enabled: !!selected,
  });
  async function transition(next: string) {
    if (!detail.data) return;
    setBusy(true);
    setError(null);
    try {
      await api("/after-sales/" + selected + "/status", {
        method: "PATCH",
        body: JSON.stringify({
          status: next,
          version: detail.data.version,
          note,
        }),
      });
      void cache.invalidateQueries({ queryKey: ["tickets"] });
      void detail.refetch();
      setNote("");
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  const actions: Record<string, [string, string][]> = {
    SUBMITTED: [["IN_REVIEW", "开始审核"]],
    IN_REVIEW: [
      ["APPROVED", "通过申请"],
      ["REJECTED", "拒绝申请"],
    ],
    APPROVED: [["RESOLVED", "标记完成"]],
  };
  return (
    <>
      <PageTitle
        eyebrow="AFTER-SALES"
        title={customer ? "售后申请" : "售后工单"}
        description={
          customer
            ? "跟踪申请处理进度，查看每一次状态变化。"
            : "受理、审核与跟进申请，操作过程保留完整记录。"
        }
      />
      <div className="toolbar">
        <select
          aria-label="工单状态"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setCursor(null);
          }}
        >
          <option value="">全部状态</option>
          {Object.entries({
            SUBMITTED: "待受理",
            IN_REVIEW: "审核中",
            APPROVED: "已通过",
            REJECTED: "已拒绝",
            RESOLVED: "已完成",
            CANCELLED: "已取消",
          }).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <span className="muted help-text">以最近提交的申请排序</span>
      </div>
      <div className="card">
        {query.isLoading ? (
          <Loading />
        ) : query.error ? (
          <ErrorNotice error={query.error} retry={() => void query.refetch()} />
        ) : !query.data?.items.length ? (
          <Empty
            title="暂无售后申请"
            detail="客户确认申请后，工单会显示在这里。"
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>工单编号</th>
                  <th>申请类型 / 说明</th>
                  <th>订单</th>
                  <th>处理状态</th>
                  <th>提交时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((ticket) => (
                  <tr key={ticket.id}>
                    <td className="table-title">
                      SH-AF-{String(ticket.id).padStart(5, "0")}
                    </td>
                    <td>
                      {KIND[ticket.kind]}
                      <span className="table-subtitle text-truncate">
                        {ticket.reason}
                      </span>
                    </td>
                    <td>#{ticket.order_id}</td>
                    <td>
                      <Badge value={ticket.status} />
                    </td>
                    <td>{localTime(ticket.created_at)}</td>
                    <td>
                      <button
                        className="button subtle small"
                        onClick={() => {
                          setSelected(ticket.id);
                          setError(null);
                        }}
                      >
                        查看进度
                      </button>
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
                返回第一页
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
      {selected && (
        <Modal title="售后处理记录" onClose={() => setSelected(null)}>
          {detail.isLoading ? (
            <Loading />
          ) : detail.error ? (
            <ErrorNotice error={detail.error} />
          ) : (
            detail.data && (
              <>
                <div className="row spread">
                  <h3>SH-AF-{String(detail.data.id).padStart(5, "0")}</h3>
                  <Badge value={detail.data.status} />
                </div>
                <p className="review-reason">{detail.data.reason}</p>
                <div className="timeline">
                  {(detail.data.events ?? []).map((event) => (
                    <div key={event.id}>
                      <div className="row spread">
                        <Badge value={event.to_status} />
                        <small className="muted">
                          {localTime(event.created_at)}
                        </small>
                      </div>
                      <p>{event.note || "状态已更新"}</p>
                    </div>
                  ))}
                </div>
                {error != null && <ErrorNotice error={error} />}
                {customer && detail.data.status === "SUBMITTED" && (
                  <div className="form-actions">
                    <button
                      className="button danger"
                      disabled={busy}
                      onClick={() => {
                        if (window.confirm("确认撤销这份售后申请？"))
                          void transition("CANCELLED");
                      }}
                    >
                      撤销申请
                    </button>
                  </div>
                )}
                {!customer &&
                  (actions[detail.data.status]?.length ?? 0) > 0 && (
                    <div className="stack">
                      <hr className="divider" />
                      <label>
                        处理说明
                        <textarea
                          value={note}
                          maxLength={500}
                          onChange={(e) => setNote(e.target.value)}
                          placeholder="向客户说明本次处理结果"
                        />
                      </label>
                      <div className="form-actions">
                        {actions[detail.data.status].map(([value, label]) => (
                          <button
                            key={value}
                            className={
                              "button " +
                              (value === "REJECTED" ? "danger" : "primary")
                            }
                            disabled={busy}
                            onClick={() => void transition(value)}
                          >
                            {label}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
              </>
            )
          )}
        </Modal>
      )}
    </>
  );
}
