import { expect, test } from "vitest";
import { applyEvent, initialLiveState } from "./live";

const event = {
  kind: "message.created",
  conversation_id: 1,
  seq: 1,
  generation_epoch: 1,
  payload: {
    conversation: { id: 1, status: "AI_ACTIVE", generation_epoch: 1 },
    message: { id: 10, body: "已保存", client_message_id: "client-1" },
  },
};
test("a repeated durable sequence cannot display the same message twice", () => {
  const once = applyEvent(initialLiveState, event);
  const twice = applyEvent(once, event);
  expect(twice.messages).toHaveLength(1);
  expect(twice.lastSeq).toBe(1);
});
test("late token fragments are discarded after a newer generation takes over", () => {
  const start = applyEvent(initialLiveState, event);
  const human = applyEvent(start, {
    kind: "conversation.updated",
    conversation_id: 1,
    seq: 2,
    generation_epoch: 2,
    payload: {
      conversation: { id: 1, status: "WAITING_HUMAN", generation_epoch: 2 },
    },
  });
  const late = applyEvent(human, {
    kind: "ai.delta",
    conversation_id: 1,
    seq: null,
    generation_epoch: 1,
    payload: { text: "旧片段" },
  });
  expect(late.partialText).toBe("");
  expect(late.conversation?.status).toBe("WAITING_HUMAN");
});

test("a successful retried AI reply clears the earlier failure state", () => {
  const started = applyEvent(initialLiveState, event);
  const failed = applyEvent(started, {
    kind: "ai.failed",
    conversation_id: 1,
    seq: 2,
    generation_epoch: 1,
    payload: {},
  });
  expect(failed.error).not.toBeNull();
  const completed = applyEvent(failed, {
    ...event,
    seq: 3,
    payload: { message: { id: 11, body: "已恢复", author_role: "ai" } },
  });
  expect(completed.error).toBeNull();
  expect(completed.partialText).toBe("");
});
