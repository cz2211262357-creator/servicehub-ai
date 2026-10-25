import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, test, vi } from "vitest";
import { ChatPanel } from "./ChatPanel";

const state = vi.hoisted(() => ({
  status: "AI_ACTIVE",
  stopped: false,
}));
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 4, tenant_id: 1, role: "customer" } }),
}));
vi.mock("../../api/topics", () => ({
  listConversationTopics: vi
    .fn()
    .mockResolvedValue({ items: [], active_topic_id: 10 }),
  interruptConversation: vi.fn(),
  switchConversationTopic: vi.fn(),
}));
beforeEach(() => {
  state.status = "AI_ACTIVE";
  state.stopped = false;
});
vi.mock("../../api/live", async (original) => ({
  ...(await original<Record<string, unknown>>()),
  useConversationLive: () => ({
    conversation: {
      id: 1,
      title: "售后咨询",
      customer_id: 4,
      status: state.status,
      ...(state.stopped
        ? { generation_epoch: 8, active_topic_id: 10, ai_reply: null }
        : {}),
    },
    messages: [
      {
        id: 1,
        sender_id: 4,
        author_role: "customer",
        body: "退货政策",
        created_at: "2026-10-21T00:00:00Z",
      },
    ],
    partialText: "",
    error: null,
    connectionState: "live",
    older: null,
    loadOlder: vi.fn(),
    addSaved: vi.fn(),
  }),
}));

test("waiting for the first model token is visible and stops after human handoff", () => {
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
    configurable: true,
    value: vi.fn(),
  });
  const cache = new QueryClient();
  const view = () => (
    <QueryClientProvider client={cache}>
      <ChatPanel id={1} />
    </QueryClientProvider>
  );
  const { rerender } = render(view());
  expect(screen.getByRole("status")).toHaveTextContent(
    "正在检索资料并准备回复",
  );
  state.status = "WAITING_HUMAN";
  rerender(view());
  expect(screen.queryByText("正在检索资料并准备回复…")).not.toBeInTheDocument();
});

test("a persisted stop survives refresh without showing a phantom running answer", () => {
  state.stopped = true;
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
    configurable: true,
    value: vi.fn(),
  });
  const cache = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={cache}>
      <ChatPanel id={1} />
    </QueryClientProvider>,
  );
  expect(screen.queryByText("正在检索资料并准备回复…")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "停止回答" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "新话题" })).toBeEnabled();
});
