import { useEffect, useRef, useState } from "react";
import {
  ArrowDown,
  ArrowLeft,
  BookOpen,
  Headphones,
  Send,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api, post } from "../../api/client";
import type { Schemas } from "../../api/client";
import {
  createOutgoingMessage,
  sendCustomerMessage,
  useConversationLive,
} from "../../api/live";
import type { OutgoingMessage } from "../../api/live";
import { useAuth } from "../../app/AuthProvider";
import {
  Badge,
  Empty,
  ErrorNotice,
  Loading,
  Modal,
  localTime,
} from "../../components/Feedback";
import { TopicControls } from "./TopicControls";
import { AfterSaleForm } from "./AfterSaleForm";

export function ChatPanel({
  id,
  onBack,
  staff = false,
}: {
  id: number | null;
  onBack?: () => void;
  staff?: boolean;
}) {
  const live = useConversationLive(id),
    auth = useAuth(),
    cache = useQueryClient();
  const [text, setText] = useState(""),
    [pending, setPending] = useState<
      (OutgoingMessage & { failed: boolean; error?: unknown })[]
    >([]);
  const [actionError, setActionError] = useState<unknown>(null),
    [draft, setDraft] = useState<Schemas["TicketDraftDTO"] | null>(null);
  const bottom = useRef<HTMLDivElement>(null);
  const drafts = useQuery({
    queryKey: ["drafts", auth.user?.tenant_id, auth.user?.id],
    queryFn: () => api<Schemas["DraftPage"]>("/after-sales/drafts"),
    enabled: !staff && live.messages.some((m) => !!m.draft_id),
    refetchInterval: 8000,
  });
  useEffect(() => {
    setPending([]);
    setText("");
  }, [id]);
  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [live.messages.length, live.partialText]);
  async function send(message: OutgoingMessage) {
    if (!id) return;
    setPending((old) => [
      ...old.filter((item) => item.id !== message.id),
      { ...message, failed: false },
    ]);
    try {
      const saved = await sendCustomerMessage(id, message);
      live.addSaved(saved);
      void live.refresh().catch(setActionError);
      setPending((old) => old.filter((item) => item.id !== message.id));
      void cache.invalidateQueries({ queryKey: ["conversations"] });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        await live.refresh();
        setActionError(new Error("会话已更新，请确认当前话题后重试。"));
      }
      setPending((old) =>
        old.map((item) =>
          item.id === message.id ? { ...item, failed: true, error } : item,
        ),
      );
    }
  }
  function submit() {
    if (!text.trim()) return;
    const message = createOutgoingMessage(text.trim());
    setText("");
    void send(message);
  }
  async function action(name: string) {
    setActionError(null);
    try {
      live.syncConversation(
        await post<Schemas["ConversationDTO"]>(
          "/conversations/" + id + "/" + name,
        ),
      );
      void cache.invalidateQueries({ queryKey: ["conversations"] });
    } catch (e) {
      setActionError(e);
    }
  }
  if (!id)
    return (
      <div className="chat-main">
        <Empty
          title="选择一个服务会话"
          detail="查看咨询记录、接续沟通并跟进处理结果。"
        />
      </div>
    );
  if (!live.conversation)
    return (
      <div className="chat-main">
        {live.error ? <ErrorNotice error={live.error} /> : <Loading />}
      </div>
    );
  const conversation = live.conversation;
  const pendingChoice =
    conversation.ai_reply?.state === "waiting_input"
      ? [...live.messages]
          .reverse()
          .find(
            (m) =>
              m.author_role === "customer" &&
              m.accepted_generation_epoch === conversation.generation_epoch,
          )?.id
      : undefined;
  const canReply =
    conversation.status !== "CLOSED" &&
    (!staff ||
      (conversation.status === "HUMAN_ACTIVE" &&
        conversation.assigned_agent_id === auth.user?.id));
  return (
    <section className="chat-main">
      <header className="chat-header">
        <div className="row">
          <button
            className="icon-button mobile-back"
            aria-label="返回列表"
            onClick={onBack}
          >
            <ArrowLeft size={18} />
          </button>
          <div>
            <h3>{conversation.title}</h3>
            <small className="muted">
              会话 #{id} ·{" "}
              {staff
                ? "客户 #" + conversation.customer_id
                : "我们会记录每一次服务进展"}
            </small>
          </div>
        </div>
        <div className="row">
          <Badge value={conversation.status} />
          {!staff && conversation.status === "AI_ACTIVE" && (
            <button
              className="button small"
              onClick={() => void action("handoff")}
            >
              <Headphones size={14} />
              转人工
            </button>
          )}
          {conversation.status !== "CLOSED" &&
            (!staff || conversation.assigned_agent_id === auth.user?.id) && (
              <button
                className="button subtle small"
                onClick={() => void action("close")}
              >
                结束会话
              </button>
            )}
        </div>
      </header>
      {!staff && conversation.active_topic_id !== undefined && (
        <TopicControls
          key={id}
          conversation={conversation}
          pendingMessageId={pendingChoice}
          onChange={live.syncConversation}
          refresh={live.refresh}
        />
      )}
      <div className="chat-connection">
        <i
          className={
            live.connectionState === "live" ? "online-dot" : "reconnecting-dot"
          }
        />
        {live.connectionState === "live"
          ? "实时连接已建立"
          : live.connectionState === "connecting"
            ? "正在建立连接…"
            : "连接已断开，正在补齐消息…"}
        <span>消息与服务记录自动保存</span>
      </div>
      <div className="chat-scroll">
        {live.error != null && <ErrorNotice error={live.error} />}
        {actionError != null && <ErrorNotice error={actionError} />}
        {live.older && (
          <button
            className="button subtle small older"
            onClick={() => void live.loadOlder()}
          >
            加载更早消息
          </button>
        )}
        {live.messages.length === 0 && (
          <div className="chat-welcome">
            <span className="assistant-symbol">
              <Sparkles size={24} />
            </span>
            <h2>您好，有什么可以帮助您？</h2>
            <p>可以咨询售后政策、查询订单，或准备售后申请。</p>
            <div className="prompt-chips">
              {["退货需要满足什么条件？", "如何申请商品维修？"].map(
                (prompt) => (
                  <button key={prompt} onClick={() => setText(prompt)}>
                    {prompt}
                    <ArrowDown size={12} />
                  </button>
                ),
              )}
            </div>
          </div>
        )}
        {live.messages.map((message) => {
          const mine = message.sender_id === auth.user?.id;
          return (
            <article
              key={message.id}
              className={"chat-message " + (mine ? "mine" : "")}
            >
              <span
                className={
                  "message-avatar " +
                  (message.author_role === "ai" ? "ai-avatar" : "")
                }
              >
                {message.author_role === "ai" ? (
                  <Sparkles size={15} />
                ) : message.author_role === "customer" ? (
                  "客"
                ) : (
                  "服"
                )}
              </span>
              <div className="message-content">
                <div className="message-meta">
                  <strong>
                    {mine
                      ? "我"
                      : message.author_role === "ai"
                        ? "ServiceHub AI"
                        : message.author_role === "customer"
                          ? "客户"
                          : "人工客服"}
                  </strong>
                  {message.topic_id != null && (
                    <span>话题 #{message.topic_id}</span>
                  )}
                  <time>{localTime(message.created_at)}</time>
                </div>
                <div className="bubble">{message.body}</div>
                {(message.citations ?? []).length > 0 && (
                  <div className="citation-list">
                    {(message.citations ?? []).map((citation, index) => (
                      <a
                        key={index}
                        href={
                          "/api/v1/knowledge/documents/" +
                          citation.document_id +
                          "/download?version_id=" +
                          citation.version_id
                        }
                        target="_blank"
                        rel="noreferrer"
                        title={String(citation.text)}
                      >
                        <BookOpen size={12} />
                        {String(citation.title || "知识来源")}
                        <span>第 {String(citation.page)} 页</span>
                      </a>
                    ))}
                  </div>
                )}
                {message.draft_id &&
                  !staff &&
                  drafts.data?.items.find(
                    (item) => item.id === message.draft_id,
                  ) && (
                    <div className="draft-card">
                      <ShieldCheck size={18} />
                      <div>
                        <strong>
                          {message.topic_id != null &&
                          message.topic_id !== conversation.active_topic_id
                            ? "历史话题申请"
                            : "售后申请已准备"}
                        </strong>
                        <small>核对并确认后才会提交</small>
                      </div>
                      <button
                        className="button small"
                        onClick={() =>
                          setDraft(
                            drafts.data!.items.find(
                              (item) => item.id === message.draft_id,
                            )!,
                          )
                        }
                      >
                        核对申请
                      </button>
                    </div>
                  )}
              </div>
            </article>
          );
        })}
        {conversation.status === "AI_ACTIVE" &&
          (conversation.active_topic_id === undefined ||
            ["queued", "running", "retrying"].includes(
              conversation.ai_reply?.state ?? "",
            )) &&
          !live.error &&
          !live.partialText &&
          live.messages.at(-1)?.author_role === "customer" && (
            <div className="chat-waiting" role="status">
              <Sparkles size={15} />
              <span>正在检索资料并准备回复…</span>
            </div>
          )}
        {live.partialText && (
          <article className="chat-message">
            <span className="message-avatar ai-avatar">
              <Sparkles size={15} />
            </span>
            <div className="message-content">
              <div className="message-meta">
                <strong>ServiceHub AI</strong>
                <span>正在生成…</span>
              </div>
              <div className="bubble streaming">
                {live.partialText}
                <i />
              </div>
            </div>
          </article>
        )}
        {pending.map((message) => (
          <article
            key={message.id}
            className="chat-message mine pending-message"
          >
            <span className="message-avatar">我</span>
            <div className="message-content">
              <div className="bubble">{message.text}</div>
              {message.failed ? (
                <div className="send-failed">
                  <span>
                    {message.error instanceof Error
                      ? message.error.message
                      : "发送失败"}
                  </span>
                  <button onClick={() => void send(message)}>重发</button>
                </div>
              ) : (
                <small className="muted">正在保存…</small>
              )}
            </div>
          </article>
        ))}
        <div ref={bottom} />
      </div>
      <footer className="chat-composer">
        <textarea
          placeholder={
            canReply
              ? "输入您的问题，Enter 发送，Shift + Enter 换行"
              : conversation.status === "CLOSED"
                ? "会话已结束，请创建新会话"
                : "领取会话后即可回复"
          }
          disabled={!canReply}
          value={text}
          maxLength={8000}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (
              e.key === "Enter" &&
              !e.shiftKey &&
              !e.nativeEvent.isComposing
            ) {
              e.preventDefault();
              submit();
            }
          }}
        />
        <div>
          <span>
            {conversation.status === "AI_ACTIVE"
              ? "AI 回复基于知识与工具结果，请核对重要信息。"
              : "服务进展会保存在当前会话中。"}
          </span>
          <button
            className="button primary"
            disabled={!canReply || !text.trim()}
            onClick={submit}
          >
            <Send size={14} />
            发送
          </button>
        </div>
      </footer>
      {draft && (
        <Modal title="核对售后申请" onClose={() => setDraft(null)}>
          <AfterSaleForm
            orderId={draft.order_id}
            initialDraft={draft}
            onDone={() => {
              setDraft(null);
              void cache.invalidateQueries({
                queryKey: ["drafts", auth.user?.tenant_id, auth.user?.id],
              });
            }}
          />
        </Modal>
      )}
    </section>
  );
}
