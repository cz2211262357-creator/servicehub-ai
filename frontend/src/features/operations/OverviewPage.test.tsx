import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, test, vi } from "vitest";
import { OverviewPage } from "./OverviewPage";
import { MemoryRouter } from "react-router-dom";
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 1, tenant_id: 1, role: "admin" } }),
}));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
test("unknown provider usage is shown as unavailable rather than invented cost", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({
          conversations: 12,
          waiting: 2,
          handoff_count: 3,
          handoff_rate: 0.25,
          failed_jobs: 1,
          first_response_ms: null,
          total_tokens: null,
          usage_records: 0,
          ai_runs: 0,
          daily: [],
          job_states: {},
        }),
      ),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  expect(await screen.findByText("12")).toBeInTheDocument();
  expect(screen.getByText("暂无模型用量记录")).toBeInTheDocument();
});

test("partial provider usage explicitly counts runs without a recorded total", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({
          conversations: 1,
          waiting: 0,
          handoff_count: 0,
          handoff_rate: 0,
          failed_jobs: 0,
          first_response_ms: null,
          total_tokens: 12,
          usage_records: 1,
          ai_runs: 3,
          daily: [],
          job_states: {},
        }),
      ),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  expect(await screen.findByText("2 次运行未记录用量")).toBeInTheDocument();
});

test("AI quality uses terminal samples and per-attempt usage rather than run counts", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({
          conversations: 1,
          waiting: 0,
          handoff_count: 0,
          handoff_rate: 0,
          failed_jobs: 1,
          first_response_ms: null,
          total_tokens: 42,
          usage_records: 3,
          ai_runs: 2,
          daily: [],
          job_states: {},
          ai_succeeded: 3,
          ai_failed: 1,
          ai_cancelled: 2,
          ai_failure_rate: 0.25,
          ai_terminal_samples: 4,
          ai_p95_latency_ms: 750,
          ai_latency_samples: 4,
          model_calls: 5,
          tool_calls: 2,
          usage_coverage: 0.6,
          usage_unknown_records: 2,
          estimated_cost: "0.0006000000",
          cost_currency: "CNY",
          price_version: "2026-10",
          start: "2026-10-22T00:00:00Z",
          end: "2026-10-29T00:00:00Z",
        }),
      ),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  expect(await screen.findByText("25.0%")).toBeInTheDocument();
  expect(screen.getByText("750 ms")).toBeInTheDocument();
  expect(screen.getByText(/1 \/ 4 个成功或失败的运行/)).toBeInTheDocument();
  expect(screen.getByText(/3 \/ 5 次模型调用返回总 Token/)).toBeInTheDocument();
  expect(screen.getByText(/总 Token 用量覆盖率 60.0%/)).toBeInTheDocument();
  expect(screen.getByText("CNY 0.0006000000")).toBeInTheDocument();
  expect(screen.getByText(/价格版本 2026-10/)).toBeInTheDocument();
  expect(screen.getByText(/AI 终态与 P95 按完成时间/)).toBeInTheDocument();
});

test("legacy metrics missing Python fields show unknown quality without crashing", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({
          conversations: 1,
          waiting: 0,
          handoff_count: 0,
          handoff_rate: null,
          failed_jobs: 0,
          first_response_ms: null,
          total_tokens: null,
          usage_records: 0,
          ai_runs: 0,
          daily: [],
          job_states: {},
        }),
      ),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  expect(
    await screen.findByRole("heading", { name: "AI 运行质量" }),
  ).toBeInTheDocument();
  expect(screen.getByText("费用未知")).toBeInTheDocument();
  expect(document.body.textContent).not.toMatch(/NaN|undefined/);
});

test("partial input usage is visible even when the total is unknown", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({
          conversations: 1,
          waiting: 0,
          handoff_count: 0,
          handoff_rate: null,
          failed_jobs: 1,
          first_response_ms: null,
          total_tokens: null,
          usage_records: 0,
          ai_runs: 1,
          model_calls: 1,
          prompt_tokens: 13,
          completion_tokens: null,
          usage_coverage: 0,
          usage_unknown_records: 1,
          daily: [],
          job_states: {},
        }),
      ),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  expect(
    await screen.findByText("总 Token 未知，已收到部分用量"),
  ).toBeInTheDocument();
  expect(screen.queryByText("暂无模型用量记录")).not.toBeInTheDocument();
});
