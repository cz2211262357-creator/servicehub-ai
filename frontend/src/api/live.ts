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
export function applyEvent(state: LiveState, event: Event): LiveState {
  if (state.conversation && event.conversation_id !== state.conversation.id)
    return state;
  if (event.kind === "heartbeat") return state;
  if (event.seq != null && event.seq <= state.lastSeq) return state;
  if (event.seq == null && event.generation_epoch !== state.epoch) return state;
  const conversation =
    (event.payload.conversation as Conversation | undefined) ??
    state.conversation;
  const next = {
    ...state,
    conversation,
    epoch: Math.max(state.epoch, event.generation_epoch),
    lastSeq: event.seq ?? state.lastSeq,
  };
  if (next.epoch !== state.epoch) {
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
    if ((event.payload.message as Message).author_role === "ai") {
      next.partialText = "";
      next.error = null;
    }
  }
  if (event.kind === "ai.failed") {
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
      socket = new WebSocket(
        scheme +
          "://" +
          location.host +
          "/api/v1/conversations/" +
          cid +
          "/live?after_seq=" +
          current.current.lastSeq,
      );
      socket.onopen = () => {
        if (alive) {
          failures = 0;
          update({ ...current.current, connectionState: "live" });
        }
      };
      socket.onmessage = (message) => {
        if (!alive) return;
        try {
          const event = JSON.parse(message.data) as Event;
          if (event.seq != null && event.seq > current.current.lastSeq + 1) {
            socket?.close();
            return;
          }
          update(applyEvent(current.current, event));
        } catch {
          socket?.close();
        }
      };
      socket.onclose = (event) => {
        if (!alive) return;
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
  };
}
