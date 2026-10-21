import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Clock3, Package, UserRound } from "lucide-react";
import { api, post } from "../../api/client";
import type { Schemas } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import {
  Badge,
  Empty,
  ErrorNotice,
  localTime,
  money,
} from "../../components/Feedback";
import { ChatPanel } from "../portal/ChatPanel";

export function ConversationWorkspace({
  id,
  onBack,
}: {
  id: number | null;
  onBack: () => void;
}) {
  const { user } = useAuth(),
    cache = useQueryClient();
  const [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false);
  const detail = useQuery({
    queryKey: ["conversation", user?.tenant_id, user?.id, id],
    queryFn: () => api<Schemas["ConversationDTO"]>("/conversations/" + id),
    enabled: !!id,
    refetchInterval: 3000,
  });
  const context = useQuery({
    queryKey: ["context", user?.tenant_id, user?.id, id],
    queryFn: () =>
      api<Schemas["ConversationContextDTO"]>(
        "/conversations/" + id + "/context",
      ),
    enabled: !!id,
  });
  async function claim() {
    if (!detail.data) return;
    setError(null);
    setBusy(true);
    try {
      await post("/conversations/" + id + "/claim", {
        version: detail.data.version,
      });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
      void detail.refetch();
      void cache.invalidateQueries({ queryKey: ["conversations"] });
    }
  }
  if (!id)
    return (
      <div className="workspace-empty">
        <Empty
          title="开始一次有温度的服务"
          detail="从左侧选择会话，查看咨询内容与客户上下文。"
        />
      </div>
    );
  return (
    <div className="workspace-columns">
      <div className="staff-chat-column">
        {detail.data?.status === "WAITING_HUMAN" && (
          <div className="claim-banner">
            <Clock3 size={16} />
            <span>客户正在等待人工接待</span>
            <button
              className="button primary small"
              disabled={busy}
              onClick={() => void claim()}
            >
              {busy ? "正在领取…" : "领取会话"}
            </button>
          </div>
        )}
        {detail.data?.status === "HUMAN_ACTIVE" && (
          <div className="claim-banner assigned">
            <CheckCircle2 size={15} />
            <span>
              {detail.data.assigned_agent_id === user?.id
                ? "当前由您接待"
                : "当前负责人：客服 #" + detail.data.assigned_agent_id}
            </span>
          </div>
        )}
        {error != null && <ErrorNotice error={error} />}
        <ChatPanel id={id} onBack={onBack} staff />
      </div>
      <aside className="customer-context">
        <div className="context-heading">
          <UserRound size={15} />
          <h3>客户上下文</h3>
        </div>
        {context.error ? (
          <ErrorNotice error={context.error} />
        ) : (
          context.data && (
            <>
              <div className="customer-profile">
                <span className="avatar">
                  {context.data.display_name.slice(0, 1)}
                </span>
                <h3>{context.data.display_name}</h3>
                <p>{context.data.masked_email}</p>
                <span className="pill">客户 #{context.data.customer_id}</span>
              </div>
              <div className="context-section">
                <h4>当前服务</h4>
                <dl>
                  <dt>会话状态</dt>
                  <dd>{detail.data && <Badge value={detail.data.status} />}</dd>
                  <dt>会话编号</dt>
                  <dd>#{id}</dd>
                  <dt>创建时间</dt>
                  <dd>{detail.data && localTime(detail.data.created_at)}</dd>
                </dl>
              </div>
              <div className="context-section">
                <h4>最近订单</h4>
                {context.data.orders.length ? (
                  context.data.orders.map((order) => (
                    <div className="context-order" key={order.id}>
                      <div className="row">
                        <Package size={14} />
                        <strong>{order.order_number}</strong>
                      </div>
                      <div className="row spread">
                        <span>{money(order.total_amount)}</span>
                        <Badge value={order.status} />
                      </div>
                      <p>{order.delivery_summary || "暂无物流进度"}</p>
                    </div>
                  ))
                ) : (
                  <p className="muted help-text">暂无订单记录</p>
                )}
              </div>
              <div className="context-tip">
                处理订单和售后时，请以系统记录与有效知识依据为准。
              </div>
            </>
          )
        )}
      </aside>
    </div>
  );
}
