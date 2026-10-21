import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, test, vi } from "vitest";
import { OverviewPage } from "./OverviewPage";
import { MemoryRouter } from "react-router-dom";
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 1, tenant_id: 1, role: "admin" } }),
}));
afterEach(() => vi.unstubAllGlobals());
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
