import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { expect, test, vi } from "vitest";
import { Layout } from "./Layout";

const logout = vi.hoisted(() => vi.fn());
vi.mock("../app/AuthProvider", () => ({
  useAuth: () => ({
    user: { id: 1, tenant_id: 1, role: "customer", display_name: "客户" },
    logout,
  }),
}));

test("logout remains available in the top bar when the mobile sidebar account is hidden", () => {
  render(
    <MemoryRouter>
      <Layout />
    </MemoryRouter>,
  );
  fireEvent.click(
    within(screen.getByRole("banner")).getByRole("button", {
      name: "退出登录",
    }),
  );
  expect(logout).toHaveBeenCalledOnce();
});
