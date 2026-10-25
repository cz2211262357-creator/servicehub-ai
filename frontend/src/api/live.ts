import { useEffect, useRef, useState } from "react";
import { api, post } from "./client";
import type { Schemas } from "./client";

type Message = Schemas["MessageDTO"];
type Conversation = Schemas["ConversationDTO"];
type Event = Schemas["LiveEvent"];
export type LiveState = {
  messages: Message[];
  conversation: Conversation | null;
  partialText: string;
  lastSeq: number;
  epoch: number;
  connectionState: string;
  older: number | null;
  error: unknown;
};
export const initialLiveState: LiveState = {
  messages: [],
  conversation: null,
  partialText: "",
  lastSeq: 0,
  epoch: 0,
  connectionState: "connecting",
  older: null,
  error: null,
};
function unique(messages: Message[]) {
  return [...new Map(messages.map((item) => [item.id, item])).values()].sort(
    (a, b) => a.id - b.id,
  );
}
function replyError(conversation: Conversation | null): Error | null {
  const reply = conversation?.ai_reply;
  if (reply?.state === "failed")
    return new Error("智能回复已失败，请转人工处理或联系管理员重试。");
  if (reply?.state === "retrying")
    return new Error("智能回复暂时失败，正在自动重试，也可以转人工处理。");
  return null;
}
function streamIdentity(value: Conversation | null) {
  return JSON.stringify([
    value?.active_topic_id,
    value?.ai_reply?.run_id,
    value?.ai_reply?.execution_version,
  ]);
}
function generating(value: Conversation | null) {
  return (
    value?.status === "AI_ACTIVE" &&
    (value.active_topic_id === undefined ||
      ["queued", "running", "retrying"].includes(value.ai_reply?.state ?? ""))
  );
}
function chooseSnapshot(
  previous: Conversation | null,
  candidate: Conversation | undefined,
  epoch: number,
) {
  if (!candidate) return previous;
  if (candidate.generation_epoch < epoch) return previous;
  // Snapshot progress is independent of the durable socket replay cursor.
  if (previous && candidate.last_event_seq < previous.last_event_seq) return previous;
  if (candidate.generation_epoch === epoch && previous) {
    if (candidate.version < previous.version) return previous;
    if (
      candidate.ai_reply?.run_id === previous.ai_reply?.run_id &&
      (candidate.ai_reply?.execution_version ?? 0) <
        (previous.ai_reply?.execution_version ?? 0)
    )
      return previous;
  }
  return candidate;
}
function acceptsDelta(state: LiveState, event: Event) {
  const conversation = state.conversation;
  if (
    !conversation ||
    conversation.status !== "AI_ACTIVE" ||
    event.generation_epoch !== state.epoch
  )
    return false;
  const reply = conversation.ai_reply;
  if (reply && !["queued", "running", "retrying"].includes(reply.state))
    return false;
  if (conversation.active_topic_id !== undefined) {
    if (
      !reply ||
      conversation.active_topic_id == null ||
      event.topic_id !== conversation.active_topic_id ||
      event.run_id !== reply.run_id ||
      event.execution_version !== reply.execution_version
    )
      return false;
  }
  return true;
}
export function applyEvent(state: LiveState, event: Event): LiveState {
  if (state.conversation && event.conversation_id !== state.conversation.id)
    return state;
  if (event.kind === "heartbeat") return state;
  if (event.seq != null && event.seq <= state.lastSeq) return state;
  if (
    event.seq == null &&
    (event.kind !== "ai.delta" || !acceptsDelta(state, event))
  )
    return state;
  const conversation = chooseSnapshot(
    state.conversation,
    event.payload.conversation as Conversation | undefined,
    state.epoch,
  );
  const next = {
    ...state,
    conversation,
    epoch: Math.max(state.epoch, event.generation_epoch),
    lastSeq: event.seq ?? state.lastSeq,
  };
  if (
    next.epoch !== state.epoch ||
    streamIdentity(conversation) !== streamIdentity(state.conversation) ||
    !generating(conversation)
  ) {
    next.partialText = "";
    next.error = null;
  }
  if (event.payload.conversation || event.kind === "ai.state")
    next.error = replyError(conversation);
  if (event.kind === "ai.delta") {
    next.partialText += String(event.payload.text ?? "");
    next.error = null;
  }
  if (event.kind === "message.created") {
    next.messages = unique([
      ...state.messages,
      event.payload.message as Message,
    ]);
    if (
      (event.payload.message as Message).author_role === "ai" &&
      event.generation_epoch === state.epoch &&
      (!conversation?.ai_reply ||
        (event.payload.message as Message).ai_run_id ===
          conversation.ai_reply.run_id)
    ) {
      next.partialText = "";
      next.error = null;
    }
  }
  if (event.kind === "ai.failed" && event.generation_epoch === state.epoch) {
    next.partialText = "";
    next.error = new Error(
      event.payload.will_retry === true
        ? "智能回复暂时失败，正在自动重试，也可以转人工处理。"
        : "智能回复已失败，请转人工处理或联系管理员重试。",
    );
  }
  return next;
}
export type OutgoingMessage = { id: string; text: string };
export function createOutgoingMessage(text: string): OutgoingMessage {
  return { id: crypto.randomUUID(), text };
}
export function sendCustomerMessage(cid: number, message: OutgoingMessage) {
  return post<Message>("/conversations/" + cid + "/messages", {
    client_message_id: message.id,
    body: message.text,
  });
}
export function useConversationLive(conversationId: number | null) {
  const [state, setState] = useState(initialLiveState);
  const current = useRef(state);
  const identity = useRef({ id: conversationId });
  if (identity.current.id !== conversationId)
    identity.current = { id: conversationId };
  const selection = identity.current;
  function update(next: LiveState) {
    if (identity.current !== selection) return;
    current.current = next;
    setState(next);
  }
  useEffect(() => {
    let alive = true,
      socket: WebSocket | undefined,
      timer: ReturnType<typeof setTimeout> | undefined,
      failures = 0;
    update({ ...initialLiveState });
    if (!conversationId) return;
    const cid = conversationId;
    function connect() {
      if (!alive) return;
      const scheme = location.protocol === "https:" ? "wss" : "ws";
      const activeSocket = new WebSocket(
        scheme +
          "://" +
          location.host +
          "/api/v1/conversations/" +
          cid +
          "/live?after_seq=" +
          current.current.lastSeq,
      );
      socket = activeSocket;
      let ended = false;
      activeSocket.onopen = () => {
        if (alive && socket === activeSocket && !ended) {
          update({ ...current.current, connectionState: "live" });
        }
      };
      activeSocket.onmessage = (message) => {
        if (!alive || ended || socket !== activeSocket) return;
        try {
          const event = JSON.parse(message.data) as Event;
          if (event.seq != null && event.seq > current.current.lastSeq + 1) {
            activeSocket.close();
            return;
          }
          update(applyEvent(current.current, event));
          failures = 0;
        } catch {
          activeSocket.close();
        }
      };
      activeSocket.onclose = (event) => {
        if (!alive || ended || socket !== activeSocket) return;
        ended = true;
        if (event.code === 4401) {
          window.dispatchEvent(new Event("session-expired"));
          return;
        }
        if (event.code === 4403) {
          update({
            ...current.current,
            connectionState: "unavailable",
            error: new Error("当前会话不可访问"),
          });
          return;
        }
        update({ ...current.current, connectionState: "reconnecting" });
        timer = setTimeout(connect, Math.min(1000 * 2 ** failures++, 15000));
      };
    }
    void (async () => {
      try {
        const conversation = await api<Conversation>("/conversations/" + cid);
        const page = await api<Schemas["MessagePage"]>(
          "/conversations/" + cid + "/messages",
        );
        if (!alive) return;
        update({
          ...initialLiveState,
          conversation,
          messages: page.items,
          older: page.next_cursor ?? null,
          epoch: conversation.generation_epoch,
          lastSeq: conversation.last_event_seq,
          error: replyError(conversation),
        });
        connect();
      } catch (error) {
        if (alive)
          update({
            ...initialLiveState,
            error,
            connectionState: "unavailable",
          });
      }
    })();
    return () => {
      alive = false;
      clearTimeout(timer);
      socket?.close();
    };
  }, [conversationId]);
  function syncConversation(conversation: Conversation) {
    if (identity.current !== selection || conversation.id !== conversationId)
      return;
    const previous = current.current;
    if (
      chooseSnapshot(previous.conversation, conversation, previous.epoch) !==
      conversation
    )
      return;
    update({
      ...previous,
      conversation,
      epoch: conversation.generation_epoch,
      partialText:
        conversation.generation_epoch !== previous.epoch ||
        streamIdentity(conversation) !==
          streamIdentity(previous.conversation) ||
        !generating(conversation)
          ? ""
          : previous.partialText,
      error: replyError(conversation),
    });
  }
  async function refresh() {
    if (!conversationId) return;
    const conversation = await api<Conversation>(
      "/conversations/" + conversationId,
    );
    if (identity.current !== selection) return;
    syncConversation(conversation);
  }
  async function loadOlder() {
    if (!conversationId || !current.current.older) return;
    const page = await api<Schemas["MessagePage"]>(
      "/conversations/" +
        conversationId +
        "/messages?cursor=" +
        current.current.older,
    );
    if (identity.current !== selection) return;
    update({
      ...current.current,
      messages: unique([...page.items, ...current.current.messages]),
      older: page.next_cursor ?? null,
    });
  }
  const visible =
    state.conversation && state.conversation.id !== conversationId
      ? initialLiveState
      : state;
  return {
    ...visible,
    status: visible.conversation?.status,
    addSaved: (message: Message) => {
      if (
        identity.current !== selection ||
        message.conversation_id !== conversationId
      )
        return;
      update({
        ...current.current,
        messages: unique([...current.current.messages, message]),
        error: null,
      });
    },
    loadOlder,
    syncConversation,
    refresh,
  };
}
