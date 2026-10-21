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
