import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, test, vi } from "vitest";
import { AuthProvider, useAuth } from "./AuthProvider";

afterEach(() => vi.unstubAllGlobals());
test("logout removes cached tenant data and authenticated identity", async () => {
  const query = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  query.setQueryData(["private-orders"], [{ id: 123 }]);
  vi.stubGlobal("fetch", async (url: string) =>
    url.endsWith("/logout")
      ? new Response(null, { status: 204 })
      : new Response(
          JSON.stringify({
            user: {
              id: 1,
              tenant_id: 1,
              email: "c@example.com",
              display_name: "客户",
              role: "customer",
            },
            csrf_token: "csrf",
          }),
        ),
  );
  function View() {
    const auth = useAuth();
    return (
      <>
        <span>{auth.user?.display_name ?? "未登录"}</span>
        <button onClick={() => auth.logout()}>退出</button>
      </>
    );
  }
  render(
    <QueryClientProvider client={query}>
      <AuthProvider>
        <View />
      </AuthProvider>
    </QueryClientProvider>,
  );
  await screen.findByText("客户");
  fireEvent.click(screen.getByText("退出"));
  await waitFor(() => expect(screen.getByText("未登录")).toBeInTheDocument());
  expect(query.getQueryData(["private-orders"])).toBeUndefined();
});
