import { render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, test, vi } from "vitest";
import { SettingsPage } from "./SettingsPage";
import { api } from "../../api/client";
vi.mock("../../api/client", () => ({ api: vi.fn() }));
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 1, tenant_id: 1, role: "admin" } }),
}));
afterEach(() => vi.clearAllMocks());
test("runtime distinguishes unavailable queue from healthy database and cache", async () => {
  vi.mocked(api).mockImplementation(
    (path) =>
      Promise.resolve(
        path === "/admin/runtime"
          ? {
              model_mode: "online",
              model_name: "qwen-plus",
              model_configured: true,
              embedding_model: "text-embedding-v4",
              embedding_dimensions: 1024,
              embedding_configured: true,
              global_model_slots: 4,
              max_output_tokens: 1600,
              max_upload_bytes: 10485760,
              automatic_retries: 2,
              dependencies: {
                database: "ok",
                redis: "ok",
                rabbitmq: "unavailable",
              },
            }
          : { status: "ready" },
      ) as never,
  );
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <SettingsPage />
    </QueryClientProvider>,
  );
  const label = await screen.findByText("RabbitMQ");
  expect(
    within(label.closest("div")!).getByText("暂不可用"),
  ).toBeInTheDocument();
  expect(screen.getByText("Redis")).toBeInTheDocument();
});
