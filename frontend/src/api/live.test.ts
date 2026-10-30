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

test("A to B to A rejects old and wrong run or execution fragments", () => {
  const snapshot = (
    topic: number,
    epoch: number,
    run: number,
    execution: number,
    seq: number,
  ) =>
    ({
      kind: "conversation.updated",
      conversation_id: 1,
      seq,
      generation_epoch: epoch,
      payload: {
        conversation: {
          id: 1,
          status: "AI_ACTIVE",
          active_topic_id: topic,
          generation_epoch: epoch,
          ai_reply: {
            run_id: run,
            state: "running",
            execution_version: execution,
          },
        },
      },
    }) as never;
  let state = applyEvent(initialLiveState, snapshot(10, 1, 100, 1, 1));
  state = applyEvent(state, snapshot(20, 2, 200, 1, 2));
  state = applyEvent(state, snapshot(10, 3, 300, 2, 3));
  const fragment = (
    topic: number,
    epoch: number,
    run: number,
    execution: number,
    text: string,
  ) =>
    ({
      kind: "ai.delta",
      conversation_id: 1,
      seq: null,
      generation_epoch: epoch,
      topic_id: topic,
      run_id: run,
      execution_version: execution,
      payload: { text },
    }) as never;
  for (const stale of [
    fragment(10, 1, 100, 1, "old A"),
    fragment(20, 3, 300, 2, "wrong topic"),
    fragment(10, 3, 100, 2, "wrong run"),
    fragment(10, 3, 300, 1, "old worker"),
  ])
    state = applyEvent(state, stale);
  expect(state.partialText).toBe("");
  state = applyEvent(state, fragment(10, 3, 300, 2, "current A"));
  expect(state.partialText).toBe("current A");
});
test("a stopped run cannot append a fragment even at the same epoch", () => {
  const state = {
    ...initialLiveState,
    epoch: 3,
    conversation: {
      id: 1,
      status: "AI_ACTIVE",
      generation_epoch: 3,
      active_topic_id: 10,
      ai_reply: { run_id: 300, state: "cancelled", execution_version: 2 },
    },
  } as never;
  expect(
    applyEvent(state, {
      kind: "ai.delta",
      conversation_id: 1,
      seq: null,
      generation_epoch: 3,
      topic_id: 10,
      run_id: 300,
      execution_version: 2,
      payload: { text: "cancelled" },
    } as never).partialText,
  ).toBe("");
});
test("replayed historical snapshots cannot move the active topic backward", () => {
  const state = {
    ...initialLiveState,
    lastSeq: 4,
    epoch: 3,
    conversation: {
      id: 1,
      status: "AI_ACTIVE",
      generation_epoch: 3,
      active_topic_id: 10,
    },
  } as never;
  const next = applyEvent(state, {
    kind: "conversation.updated",
    conversation_id: 1,
    seq: 5,
    generation_epoch: 1,
    payload: {
      conversation: {
        id: 1,
        status: "AI_ACTIVE",
        generation_epoch: 1,
        active_topic_id: 20,
      },
    },
  } as never);
  expect(next.conversation?.active_topic_id).toBe(10);
  expect(next.epoch).toBe(3);
  expect(next.lastSeq).toBe(5);
});

test("worker recovery at the same epoch clears partial text from its expired lease", () => {
  const state = {
    ...initialLiveState,
    lastSeq: 1,
    epoch: 3,
    partialText: "old worker prefix",
    conversation: {
      id: 1,
      status: "AI_ACTIVE",
      generation_epoch: 3,
      active_topic_id: 10,
      ai_reply: { run_id: 300, state: "running", execution_version: 1 },
    },
  } as never;
  const next = applyEvent(state, {
    kind: "conversation.updated",
    conversation_id: 1,
    seq: 2,
    generation_epoch: 3,
    payload: {
      conversation: {
        id: 1,
        status: "AI_ACTIVE",
        generation_epoch: 3,
        active_topic_id: 10,
        ai_reply: { run_id: 300, state: "running", execution_version: 2 },
      },
    },
  } as never);
  expect(next.partialText).toBe("");
});

test("failure events retain a public explanation from their accepted snapshot", () => {
  const state = applyEvent(initialLiveState, event);
  const failed = applyEvent(state, {
    kind: "ai.failed",
    conversation_id: 1,
    seq: 2,
    generation_epoch: 1,
    payload: {
      conversation: {
        id: 1,
        status: "AI_ACTIVE",
        version: 1,
        last_event_seq: 2,
        generation_epoch: 1,
        ai_reply: {
          run_id: 1,
          state: "failed",
          error_message: "知识检索暂不可用，请稍后重试或转人工。",
        },
      },
    },
  });
  expect((failed.error as Error).message).toBe(
    "知识检索暂不可用，请稍后重试或转人工。",
  );
});
