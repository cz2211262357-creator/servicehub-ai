import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { JobsPage } from "./JobsPage";
import { api, post, ApiError } from "../../api/client";
vi.mock("../../api/client", async (original) => ({
  ...(await original<Record<string, unknown>>()),
  api: vi.fn(),
  post: vi.fn(),
}));
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 1, tenant_id: 1, role: "admin" } }),
}));
let removed = false;
beforeEach(() => {
  removed = false;
  vi.mocked(api).mockImplementation(
    () =>
      Promise.resolve({
        items: removed
          ? []
          : [
              {
                id: 1,
                kind: "ai_reply",
                payload: { ai_run_id: 12 },
                state: "failed",
                attempts: 1,
                execution_version: 3,
                manual_retries: 2,
                lifetime_attempts: 7,
                available_at: "2026-10-24T00:00:00Z",
                error_code: "model_unavailable",
              },
            ],
        next_cursor: null,
      }) as never,
  );
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});
function show() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <JobsPage />
    </QueryClientProvider>,
  );
}
test("cancelled and waiting input states have explicit filters", async () => {
  show();
  await screen.findByText("AI 回复");
  expect(screen.getByRole("option", { name: "已取消" })).toBeInTheDocument();
  expect(
    screen.getByRole("option", { name: "待补充信息" }),
  ).toBeInTheDocument();
});
test("retry counters distinguish this batch from lifetime attempts", async () => {
  show();
  expect(
    await screen.findByText("累计 7 次 · 人工重试 2 次"),
  ).toBeInTheDocument();
});
test("a stale retry conflict refreshes the list before another attempt", async () => {
  vi.mocked(post).mockImplementation(() => {
    removed = true;
    return Promise.reject(new ApiError("job_conflict", "任务已失效", 409));
  });
  show();
  fireEvent.click(await screen.findByRole("button", { name: "重试" }));
  await waitFor(() =>
    expect(vi.mocked(api).mock.calls.length).toBeGreaterThan(1),
  );
  expect(await screen.findByText("没有符合条件的任务")).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "重试" }),
  ).not.toBeInTheDocument();
});

test("AI job exposes its run number for administrator trace lookup", async () => {
  show();
  expect(await screen.findByText("AI 运行 #12")).toBeInTheDocument();
});
