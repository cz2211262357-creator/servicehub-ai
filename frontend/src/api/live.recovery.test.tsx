import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { api } from "./client";
import type { Schemas } from "./client";
import { useConversationLive } from "./live";

vi.mock("./client", () => ({ api: vi.fn(), post: vi.fn() }));
const message = (cid: number, id: number) =>
  ({
    id,
    conversation_id: cid,
    sender_id: 1,
    author_role: "customer",
    client_message_id: "m-" + id,
    ai_run_id: null,
    body: "会话 " + cid,
    citations: [],
    draft_id: null,
    created_at: "2026-10-21T00:00:00Z",
  }) as Schemas["MessageDTO"];
const conversation = (id: number) => ({
  id,
  customer_id: 1,
  assigned_agent_id: null,
  title: "会话 " + id,
  status: "AI_ACTIVE",
  version: 1,
  generation_epoch: 1,
  last_event_seq: 9,
  created_at: "2026-10-21T00:00:00Z",
  updated_at: "2026-10-21T00:00:00Z",
});
function responses(path: string) {
  const cid = Number(path.split("/")[2]);
  return path.endsWith("/messages")
    ? { items: [message(cid, cid * 100)], next_cursor: 50 }
    : conversation(cid);
}
beforeEach(() => {
  vi.stubGlobal(
    "WebSocket",
    class {
      close() {}
    },
  );
  vi.mocked(api).mockImplementation(
    (path) => Promise.resolve(responses(path)) as never,
  );
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

test("reopening a failed generation restores terminal failure without replaying old events", async () => {
  vi.mocked(api).mockImplementation(
    (path) =>
      Promise.resolve(
        path.endsWith("/messages")
          ? responses(path)
          : {
              ...conversation(1),
              ai_reply: {
                run_id: 1,
                generation_epoch: 1,
                state: "failed",
                error_code: "retry_exhausted",
                will_retry: false,
                attempts: 4,
              },
            },
      ) as never,
  );
  const { result } = renderHook(() => useConversationLive(1));
  await waitFor(() => expect(result.current.conversation?.id).toBe(1));
  expect(result.current.error).toBeInstanceOf(Error);
  expect((result.current.error as Error).message).toContain("失败");
  expect((result.current.error as Error).message).not.toContain("将按策略重试");
});

test("a late send response from A cannot append to B after switching conversations", async () => {
  const { result, rerender } = renderHook(({ id }) => useConversationLive(id), {
    initialProps: { id: 1 },
  });
  await waitFor(() => expect(result.current.conversation?.id).toBe(1));
  const addToA = result.current.addSaved;
  rerender({ id: 2 });
  await waitFor(() => expect(result.current.conversation?.id).toBe(2));
  act(() => addToA(message(1, 101)));
  expect(result.current.messages.every((m) => m.conversation_id === 2)).toBe(
    true,
  );
});

test("older pages arriving after a conversation switch are discarded", async () => {
  let finish!: (page: unknown) => void;
  const pending = new Promise((resolve) => {
    finish = resolve;
  });
  vi.mocked(api).mockImplementation(
    (path) =>
      (path.includes("?cursor=")
        ? pending
        : Promise.resolve(responses(path))) as never,
  );
  const { result, rerender } = renderHook(({ id }) => useConversationLive(id), {
    initialProps: { id: 1 },
  });
  await waitFor(() => expect(result.current.conversation?.id).toBe(1));
  let request!: Promise<void>;
  act(() => {
    request = result.current.loadOlder();
  });
  rerender({ id: 2 });
  await waitFor(() => expect(result.current.conversation?.id).toBe(2));
  await act(async () => {
    finish({ items: [message(1, 10)], next_cursor: null });
    await request;
  });
  expect(result.current.messages.every((m) => m.conversation_id === 2)).toBe(
    true,
  );
  expect(result.current.older).toBe(50);
});

test("a late control HTTP receipt cannot rewind a newer topic snapshot or skip durable replay", async () => {
  const { result } = renderHook(() => useConversationLive(1));
  await waitFor(() => expect(result.current.conversation?.id).toBe(1));
  act(() =>
    result.current.syncConversation({
      ...conversation(1),
      version: 3,
      generation_epoch: 3,
      active_topic_id: 30,
    } as never),
  );
  act(() =>
    result.current.syncConversation({
      ...conversation(1),
      version: 2,
      generation_epoch: 2,
      active_topic_id: 20,
    } as never),
  );
  expect(result.current.epoch).toBe(3);
  expect(result.current.conversation?.active_topic_id).toBe(30);
  expect(result.current.lastSeq).toBe(9);
});

test("repeated service overload closes back off even if the TCP upgrade opens", async () => {
  const sockets: Array<{
    onopen?: () => void;
    onclose?: (event: { code: number }) => void;
    close: () => void;
  }> = [];
  vi.stubGlobal(
    "WebSocket",
    class {
      onopen?: () => void;
      onclose?: (event: { code: number }) => void;
      close = vi.fn();
      constructor() {
        sockets.push(this);
      }
    },
  );
  const { result } = renderHook(() => useConversationLive(1));
  await waitFor(() => expect(result.current.conversation?.id).toBe(1));
  vi.useFakeTimers();
  try {
    act(() => {
      sockets[0].onopen?.();
      sockets[0].onclose?.({ code: 1013 });
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(sockets).toHaveLength(2);
    act(() => {
      sockets[1].onopen?.();
      sockets[1].onclose?.({ code: 1013 });
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(sockets).toHaveLength(2);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(sockets).toHaveLength(3);
  } finally {
    vi.useRealTimers();
  }
});

test("late callbacks from a replaced socket cannot close the new connection", async () => {
  const sockets: Array<{
    onmessage?: (event: { data: string }) => void;
    onclose?: (event: { code: number }) => void;
    close: () => void;
  }> = [];
  vi.stubGlobal(
    "WebSocket",
    class {
      onmessage?: (event: { data: string }) => void;
      onclose?: (event: { code: number }) => void;
      close = vi.fn();
      constructor() {
        sockets.push(this);
      }
    },
  );
  const { result } = renderHook(() => useConversationLive(1));
  await waitFor(() => expect(result.current.conversation?.id).toBe(1));
  vi.useFakeTimers();
  try {
    act(() => sockets[0].onclose?.({ code: 1013 }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(sockets).toHaveLength(2);
    act(() => sockets[0].onmessage?.({ data: "truncated obsolete frame" }));
    expect(sockets[1].close).not.toHaveBeenCalled();
  } finally {
    vi.useRealTimers();
  }
});

test.each(["terminal", "resolved-topic"])(
  "a delayed same-version refresh cannot regress a newer %s snapshot",
  async (mode) => {
    const sockets: Array<{ onmessage?: (event: { data: string }) => void }> =
      [];
    vi.stubGlobal(
      "WebSocket",
      class {
        onmessage?: (event: { data: string }) => void;
        close() {}
        constructor() {
          sockets.push(this);
        }
      },
    );
    const running = {
      ...conversation(1),
      active_topic_id: 10,
      ai_reply: { run_id: 100, state: "running", execution_version: 1 },
    };
    vi.mocked(api).mockImplementation(
      (path) =>
        Promise.resolve(
          path.endsWith("/messages") ? responses(path) : running,
        ) as never,
    );
    const { result } = renderHook(() => useConversationLive(1));
    await waitFor(() => expect(result.current.conversation?.id).toBe(1));
    let finish!: (value: unknown) => void;
    vi.mocked(api).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }) as never,
    );
    let refresh!: Promise<void>;
    act(() => {
      refresh = result.current.refresh();
    });
    const newer = {
      ...running,
      last_event_seq: 10,
      active_topic_id: mode === "resolved-topic" ? 20 : 10,
      ai_reply: {
        ...running.ai_reply,
        state: mode === "terminal" ? "succeeded" : "running",
      },
    };
    act(() =>
      sockets[0].onmessage?.({
        data: JSON.stringify({
          kind: "conversation.updated",
          conversation_id: 1,
          seq: 10,
          generation_epoch: 1,
          payload: { conversation: newer },
        }),
      }),
    );
    await act(async () => {
      finish(running);
      await refresh;
    });
    expect(result.current.conversation).toEqual(newer);
    expect(result.current.lastSeq).toBe(10);
  },
);

test("a snapshot ahead of the socket never skips durable message replay", async () => {
  const sockets: Array<{ onmessage?: (event: { data: string }) => void }> = [];
  vi.stubGlobal(
    "WebSocket",
    class {
      onmessage?: (event: { data: string }) => void;
      close() {}
      constructor() {
        sockets.push(this);
      }
    },
  );
  const { result } = renderHook(() => useConversationLive(1));
  await waitFor(() => expect(result.current.conversation?.id).toBe(1));
  act(() =>
    result.current.syncConversation({
      ...conversation(1),
      last_event_seq: 11,
    } as never),
  );
  expect(result.current.lastSeq).toBe(9);
  act(() =>
    sockets[0].onmessage?.({
      data: JSON.stringify({
        kind: "message.created",
        conversation_id: 1,
        seq: 10,
        generation_epoch: 1,
        payload: {
          message: message(1, 102),
          conversation: { ...conversation(1), last_event_seq: 10 },
        },
      }),
    }),
  );
  expect(result.current.messages.some((value) => value.id === 102)).toBe(true);
  expect(result.current.conversation?.last_event_seq).toBe(11);
  expect(result.current.lastSeq).toBe(10);
});

test("reopening uses the public failure explanation supplied by the backend", async () => {
  vi.mocked(api).mockImplementation(
    (path) =>
      Promise.resolve(
        path.endsWith("/messages")
          ? responses(path)
          : {
              ...conversation(1),
              ai_reply: {
                run_id: 1,
                generation_epoch: 1,
                state: "failed",
                error_code: "knowledge_unavailable",
                error_message: "知识检索暂不可用，请稍后重试或转人工。",
                attempts: 1,
              },
            },
      ) as never,
  );
  const { result } = renderHook(() => useConversationLive(1));
  await waitFor(() => expect(result.current.conversation?.id).toBe(1));
  expect((result.current.error as Error).message).toBe(
    "知识检索暂不可用，请稍后重试或转人工。",
  );
});
