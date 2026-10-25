import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Plus, Square, Undo2 } from "lucide-react";
import { ApiError } from "../../api/client";
import type { Schemas } from "../../api/client";
import {
  interruptConversation,
  listConversationTopics,
  switchConversationTopic,
} from "../../api/topics";
import type { TopicSwitchInput } from "../../api/topics";
import { ErrorNotice } from "../../components/Feedback";
export function TopicControls({
  conversation,
  pendingMessageId,
  onChange,
  refresh,
}: {
  conversation: Schemas["ConversationDTO"];
  pendingMessageId?: number;
  onChange: (value: Schemas["ConversationDTO"]) => void;
  refresh: () => Promise<unknown> | void;
}) {
  const [selected, setSelected] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  const topics = useQuery({
    queryKey: ["topics", conversation.id, conversation.generation_epoch],
    queryFn: () => listConversationTopics(conversation.id),
  });
  const writable = conversation.status === "AI_ACTIVE";
  async function apply(action: () => Promise<Schemas["ConversationDTO"]>) {
    setBusy(true);
    setError(null);
    try {
      onChange(await action());
      setSelected("");
      await topics.refetch();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        try {
          await refresh();
        } catch (refreshError) {
          setError(refreshError);
          return;
        }
        setError(
          new Error("会话已在其他页面更新，本次操作未应用，请按最新状态重试。"),
        );
      } else setError(e);
    } finally {
      setBusy(false);
    }
  }
  function change(mode: "NEW" | "RESUME") {
    const input: TopicSwitchInput = {
      mode,
      expected_generation_epoch: conversation.generation_epoch,
    };
    if (mode === "RESUME") input.topic_id = Number(selected);
    if (pendingMessageId != null) input.pending_message_id = pendingMessageId;
    return apply(() => switchConversationTopic(conversation.id, input));
  }
  return (
    <div className="topic-controls">
      {pendingMessageId != null && (
        <p className="topic-choice">
          请为刚才的问题选择一个新话题，或明确恢复已有话题。
        </p>
      )}
      <span className="muted">
        {conversation.active_topic_id != null
          ? "当前话题 #" + conversation.active_topic_id
          : "尚未选择话题"}
      </span>
      <button
        className="button subtle small"
        disabled={
          busy ||
          !writable ||
          !["queued", "running", "retrying", "waiting_input"].includes(
            conversation.ai_reply?.state ?? "",
          )
        }
        onClick={() =>
          void apply(() =>
            interruptConversation(
              conversation.id,
              conversation.generation_epoch,
            ),
          )
        }
      >
        <Square size={13} />
        停止回答
      </button>
      <button
        className="button subtle small"
        disabled={busy || !writable}
        onClick={() => void change("NEW")}
      >
        <Plus size={14} />
        新话题
      </button>
      <select
        aria-label="历史话题"
        value={selected}
        disabled={busy || !writable}
        onChange={(e) => setSelected(e.target.value)}
      >
        <option value="">选择历史话题</option>
        {(topics.data?.items ?? [])
          .filter(
            (t) =>
              pendingMessageId != null || t.id !== conversation.active_topic_id,
          )
          .map((t) => (
            <option key={t.id} value={t.id}>
              {t.title}
            </option>
          ))}
      </select>
      <button
        className="button subtle small"
        disabled={busy || !writable || !selected}
        onClick={() => void change("RESUME")}
      >
        <Undo2 size={14} />
        恢复话题
      </button>
      {(error ?? topics.error) != null && (
        <ErrorNotice error={error ?? topics.error} />
      )}
    </div>
  );
}
