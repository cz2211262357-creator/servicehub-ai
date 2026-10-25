import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { TopicControls } from "./TopicControls";
import {
  interruptConversation,
  listConversationTopics,
  switchConversationTopic,
} from "../../api/topics";
import { ApiError } from "../../api/client";
import type { Schemas } from "../../api/client";
vi.mock("../../api/topics", () => ({
  interruptConversation: vi.fn(),
  listConversationTopics: vi.fn(),
  switchConversationTopic: vi.fn(),
}));
const snapshot = {
  id: 1,
  status: "AI_ACTIVE",
  generation_epoch: 7,
  active_topic_id: 10,
  ai_reply: { run_id: 30, state: "running" },
} as Schemas["ConversationDTO"];
const onChange = vi.fn(),
  refresh = vi.fn();
beforeEach(() => {
  vi.mocked(listConversationTopics).mockResolvedValue({
    items: [
      { id: 10, title: "当前订单", status: "ACTIVE" },
      { id: 20, title: "维修问题", status: "SUSPENDED" },
    ],
    active_topic_id: 10,
  } as never);
  vi.mocked(interruptConversation).mockResolvedValue({
    ...snapshot,
    generation_epoch: 8,
    ai_reply: null,
  });
  vi.mocked(switchConversationTopic).mockResolvedValue({
    ...snapshot,
    generation_epoch: 8,
    active_topic_id: 20,
  });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});
function show(pending?: number) {
  const cache = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={cache}>
      <TopicControls
        conversation={snapshot}
        pendingMessageId={pending}
        onChange={onChange}
        refresh={refresh}
      />
    </QueryClientProvider>,
  );
}
test("stop sends current epoch and publishes returned durable snapshot", async () => {
  show();
  fireEvent.click(screen.getByRole("button", { name: "停止回答" }));
  await waitFor(() => expect(interruptConversation).toHaveBeenCalledWith(1, 7));
  expect(onChange).toHaveBeenCalledWith(
    expect.objectContaining({ generation_epoch: 8 }),
  );
});
test("new topic resolves the exact pending question after an ambiguous route", async () => {
  show(99);
  fireEvent.click(screen.getByRole("button", { name: "新话题" }));
  await waitFor(() =>
    expect(switchConversationTopic).toHaveBeenCalledWith(1, {
      mode: "NEW",
      expected_generation_epoch: 7,
      pending_message_id: 99,
    }),
  );
});
test("history topic is resumed explicitly with current CAS version", async () => {
  show();
  await screen.findByRole("option", { name: "维修问题" });
  fireEvent.change(screen.getByLabelText("历史话题"), {
    target: { value: "20" },
  });
  fireEvent.click(screen.getByRole("button", { name: "恢复话题" }));
  await waitFor(() =>
    expect(switchConversationTopic).toHaveBeenCalledWith(1, {
      mode: "RESUME",
      topic_id: 20,
      expected_generation_epoch: 7,
    }),
  );
});
test("conflicting page refreshes current state and explains that the action was not applied", async () => {
  vi.mocked(interruptConversation).mockRejectedValue(
    new ApiError("generation_conflict", "旧版本", 409),
  );
  show();
  fireEvent.click(screen.getByRole("button", { name: "停止回答" }));
  await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "会话已在其他页面更新",
  );
  expect(onChange).not.toHaveBeenCalled();
});
