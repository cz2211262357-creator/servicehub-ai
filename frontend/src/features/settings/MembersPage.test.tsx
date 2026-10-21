import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { expect, test, vi } from "vitest";
import { MembersPage } from "./MembersPage";
vi.mock("../../app/AuthProvider", () => ({
  useAuth: () => ({ user: { id: 1, tenant_id: 1, role: "agent" } }),
}));
test("an ordinary agent has no member editing controls", () => {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MembersPage />
    </QueryClientProvider>,
  );
  expect(
    screen.queryByRole("button", { name: "添加成员" }),
  ).not.toBeInTheDocument();
  expect(screen.getByText("仅管理员可管理成员")).toBeInTheDocument();
});
