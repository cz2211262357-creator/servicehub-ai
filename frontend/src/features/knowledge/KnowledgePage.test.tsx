import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, test, vi } from "vitest";
import { KnowledgePage } from "./KnowledgePage";
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 1, tenant_id: 1, role: "admin" } }),
}));
afterEach(() => vi.unstubAllGlobals());
test("disable sends the server version and refreshes the document state", async () => {
  let enabled = true;
  const writes: unknown[] = [];
  vi.stubGlobal("fetch", async (_url: string, options: RequestInit) => {
    if (options.method === "PATCH") {
      writes.push(JSON.parse(options.body as string));
      enabled = false;
    }
    return new Response(
      JSON.stringify(
        options.method === "PATCH"
          ? {}
          : {
              items: [
                {
                  id: 1,
                  title: "售后政策",
                  enabled,
                  version: 3,
                  created_at: "2026-10-21T00:00:00Z",
                  active_version_id: 2,
                  latest_version: {
                    id: 2,
                    number: 1,
                    status: "ready",
                    size_bytes: 100,
                    filename: "p.txt",
                  },
                },
              ],
            },
      ),
    );
  });
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <KnowledgePage />
    </QueryClientProvider>,
  );
  fireEvent.click(await screen.findByRole("switch", { name: "启用 售后政策" }));
  expect(
    await screen.findByRole("switch", { name: "停用 售后政策" }),
  ).toHaveAttribute("aria-checked", "false");
  expect(writes).toEqual([{ enabled: false, version: 3 }]);
});
