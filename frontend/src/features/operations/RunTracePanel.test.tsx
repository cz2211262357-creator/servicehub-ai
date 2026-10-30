import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, test, vi } from "vitest";
import { RunTracePanel } from "./RunTracePanel";

const auth = vi.hoisted(() => ({ role: "admin" }));
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 1, tenant_id: 1, role: auth.role } }),
}));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  auth.role = "admin";
});
function show() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RunTracePanel />
    </QueryClientProvider>,
  );
}
const trace = {
  run_id: 12,
  conversation_id: 4,
  state: "failed",
  error_code: "model_timeout",
  started_at: null,
  finished_at: null,
  spans: [
    {
      id: 1,
      execution_version: 2,
      kind: "tool",
      name: "lookup_order",
      outcome: "failed",
      error_code: "unexpected_error",
      started_at: "2026-10-29T00:00:00Z",
      finished_at: "2026-10-29T00:00:01Z",
      latency_ms: 1000,
      arguments_summary: {
        order_number: { length: 12, sha256: "a".repeat(64) },
        query: "PRIVATE-RAW-QUERY",
        api_key: "SECRET-KEY",
      },
      usage: null,
      model: null,
      estimated_cost: null,
      cost_currency: null,
      price_version: null,
    },
  ],
};

test("admin trace lookup shows execution, timing and only allowlisted sanitized values", async () => {
  const fetcher = vi.fn(
    async (_input: RequestInfo | URL) => new Response(JSON.stringify(trace)),
  );
  vi.stubGlobal("fetch", fetcher);
  show();
  fireEvent.change(screen.getByLabelText("AI 运行编号"), {
    target: { value: "12" },
  });
  fireEvent.click(screen.getByRole("button", { name: "查询轨迹" }));
  expect(await screen.findByText("lookup_order")).toBeInTheDocument();
  expect(fetcher.mock.calls[0][0]).toBe("/api/v1/admin/ai-runs/12/trace");
  expect(screen.getByText(/执行版本 2/)).toBeInTheDocument();
  expect(screen.getByText(/order_number：长度 12/)).toBeInTheDocument();
  expect(screen.getByText("unexpected_error")).toBeInTheDocument();
  expect(document.body.textContent).not.toMatch(
    /PRIVATE-RAW-QUERY|SECRET-KEY|api_key/,
  );
});

test("staff cannot open or request the administrator trace", () => {
  auth.role = "agent";
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  show();
  expect(
    screen.queryByRole("button", { name: "查询轨迹" }),
  ).not.toBeInTheDocument();
  expect(fetcher).not.toHaveBeenCalled();
});

test("a missing or unsupported trace is an explicit empty result", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({ error: { code: "not_found", message: "任务不存在" } }),
        { status: 404 },
      ),
  );
  show();
  fireEvent.change(screen.getByLabelText("AI 运行编号"), {
    target: { value: "99" },
  });
  fireEvent.click(screen.getByRole("button", { name: "查询轨迹" }));
  expect(
    await screen.findByText(
      "未找到本租户的运行记录，或当前服务尚未提供调用轨迹。",
    ),
  ).toBeInTheDocument();
});
