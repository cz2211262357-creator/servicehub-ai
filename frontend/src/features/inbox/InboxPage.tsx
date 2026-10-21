import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Headphones, Inbox, Search } from "lucide-react";
import { api } from "../../api/client";
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
import { ConversationWorkspace } from "./ConversationWorkspace";

export function InboxPage() {
  const { user } = useAuth();
  const [status, setStatus] = useState("WAITING_HUMAN"),
    [selected, setSelected] = useState<number | null>(null);
  const [filter, setFilter] = useState(""),
    [search, setSearch] = useState(""),
    [cursor, setCursor] = useState<number | null>(null);
  const query = useQuery({
    queryKey: [
      "conversations",
      user?.tenant_id,
      user?.id,
      "staff",
      status,
      search,
      cursor,
    ],
    queryFn: () =>
      api<Schemas["ConversationPage"]>(
        "/conversations?limit=20" +
          (status ? "&status=" + status : "") +
          (search ? "&search=" + encodeURIComponent(search) : "") +
          (cursor ? "&cursor=" + cursor : ""),
      ),
    refetchInterval: 5000,
  });
  return (
    <>
      <PageTitle
        eyebrow="SERVICE INBOX"
        title="会话工作台"
        description="集中接待客户咨询，与 AI 协同处理每一步服务。"
        action={
          <span className="pill row">
            <Headphones size={13} />
            客服在线
          </span>
        }
      />
      <div className="inbox-toolbar">
        <div className="segmented">
          {[
            ["WAITING_HUMAN", "待接入"],
            ["HUMAN_ACTIVE", "接待中"],
            ["AI_ACTIVE", "智能接待"],
            ["", "全部会话"],
          ].map(([value, label]) => (
            <button
              key={value}
              className={status === value ? "active" : ""}
              onClick={() => {
                setStatus(value);
                setCursor(null);
              }}
            >
              {label}
            </button>
          ))}
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setSearch(filter.trim());
            setCursor(null);
          }}
          className="search-input"
        >
          <Search size={15} />
          <input
            aria-label="搜索会话"
            placeholder="搜索会话标题"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </form>
      </div>
      <div className={"inbox-layout " + (selected ? "has-selection" : "")}>
        <aside className="conversation-list">
          <div className="conversation-list-title">
            <h3>服务队列</h3>
            <span>当前页 {query.data?.items.length ?? 0}</span>
          </div>
          {query.isLoading ? (
            <Loading />
          ) : query.error ? (
            <ErrorNotice
              error={query.error}
              retry={() => void query.refetch()}
            />
          ) : !query.data?.items.length ? (
            <Empty
              title="当前没有待处理会话"
              detail="客户转人工后，会显示在待接入队列。"
            />
          ) : (
            query.data.items.map((conversation) => (
              <button
                key={conversation.id}
                className={
                  "conversation-item " +
                  (selected === conversation.id ? "selected" : "")
                }
                onClick={() => setSelected(conversation.id)}
              >
                <div className="row spread">
                  <span className="conversation-icon">
                    <Inbox size={16} />
                  </span>
                  <time>{localTime(conversation.updated_at)}</time>
                </div>
                <strong>{conversation.title}</strong>
                <div className="row spread">
                  <small>客户 #{conversation.customer_id}</small>
                  <Badge value={conversation.status} />
                </div>
              </button>
            ))
          )}
          {(cursor || query.data?.next_cursor) && (
            <div className="queue-pagination">
              {cursor && (
                <button
                  className="button small"
                  onClick={() => setCursor(null)}
                >
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
        </aside>
        <ConversationWorkspace id={selected} onBack={() => setSelected(null)} />
      </div>
    </>
  );
}
