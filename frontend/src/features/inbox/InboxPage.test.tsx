import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, test, vi } from "vitest";
import { InboxPage } from "./InboxPage";
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 1, tenant_id: 1, role: "agent" } }),
}));
afterEach(() => vi.unstubAllGlobals());
test("staff queue shows server errors with an explicit retry", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({
          error: { code: "dependency_unavailable", message: "队列暂不可用" },
        }),
        { status: 503 },
      ),
  );
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <InboxPage />
    </QueryClientProvider>,
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("队列暂不可用");
  expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
});
