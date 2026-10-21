import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { MessagesSquare, Plus, Search } from "lucide-react";
import { api, post } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import type { Schemas } from "../../api/client";
import {
  Badge,
  Empty,
  ErrorNotice,
  Loading,
  PageTitle,
  localTime,
} from "../../components/Feedback";
import { ChatPanel } from "./ChatPanel";

export function PortalPage() {
  const user = useAuth().user;
  const cache = useQueryClient(),
    [selected, setSelected] = useState<number | null>(null),
    [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false);
  const query = useQuery({
    queryKey: ["conversations", user?.tenant_id, user?.id, "mine"],
    queryFn: () => api<Schemas["ConversationPage"]>("/conversations"),
    refetchInterval: 8000,
  });
  async function create() {
    setBusy(true);
    setError(null);
    try {
      const conversation = await post<Schemas["ConversationDTO"]>(
        "/conversations",
        { title: "售后咨询" },
      );
      setSelected(conversation.id);
      void cache.invalidateQueries({ queryKey: ["conversations"] });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageTitle
        eyebrow="CUSTOMER SUPPORT"
        title="服务会话"
        description="咨询政策、查询订单，或与服务团队继续沟通。"
        action={
          <button
            className="button primary"
            disabled={busy}
            onClick={() => void create()}
          >
            <Plus size={16} />
            发起咨询
          </button>
        }
      />
      {error != null && <ErrorNotice error={error} />}
      <div className={"chat-layout " + (selected ? "has-selection" : "")}>
        <aside className="conversation-list">
          <div className="conversation-list-title">
            <h3>我的会话</h3>
            <span>{query.data?.items.length ?? 0}</span>
          </div>
          {query.isLoading ? (
            <Loading />
          ) : query.error ? (
            <ErrorNotice
              error={query.error}
              retry={() => void query.refetch()}
            />
          ) : query.data?.items.length === 0 ? (
            <Empty
              title="还没有服务会话"
              detail="发起咨询后，记录会保存在这里。"
              action={
                <button className="button small" onClick={() => void create()}>
                  发起咨询
                </button>
              }
            />
          ) : (
            query.data?.items.map((conversation) => (
              <button
                className={
                  "conversation-item " +
                  (conversation.id === selected ? "selected" : "")
                }
                key={conversation.id}
                onClick={() => setSelected(conversation.id)}
              >
                <div className="row spread">
                  <span className="conversation-icon">
                    <MessagesSquare size={16} />
                  </span>
                  <time>{localTime(conversation.updated_at)}</time>
                </div>
                <strong>{conversation.title}</strong>
                <div className="row spread">
                  <small>会话 #{conversation.id}</small>
                  <Badge value={conversation.status} />
                </div>
              </button>
            ))
          )}
          {query.data?.next_cursor && (
            <button
              className="button subtle small"
              onClick={() => void query.refetch()}
            >
              <Search size={12} />
              刷新会话列表
            </button>
          )}
        </aside>
        <ChatPanel id={selected} onBack={() => setSelected(null)} />
      </div>
    </>
  );
}
