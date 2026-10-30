import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { AfterSaleForm } from "./AfterSaleForm";

afterEach(() => vi.unstubAllGlobals());

test("a customer must choose a reason before preparing an application", () => {
  render(<AfterSaleForm orderId={42} onDone={() => {}} />);
  fireEvent.change(screen.getByLabelText("申请说明"), {
    target: { value: "仅检查过包装，想申请退货" },
  });
  expect(screen.getByRole("button", { name: "准备申请" })).toBeDisabled();
  fireEvent.change(screen.getByLabelText("申请原因"), {
    target: { value: "NO_REASON" },
  });
  expect(screen.getByRole("button", { name: "准备申请" })).toBeEnabled();
});

test.each(["NO_REASON", "QUALITY", "WRONG_ITEM", "LOGISTICS_DAMAGE"])(
  "the selected %s reason is sent without merchant facts and confirmation remains separate",
  async (reasonCode) => {
    const writes: { path: string; body: Record<string, unknown> }[] = [];
    vi.stubGlobal("fetch", async (path: string, options: RequestInit) => {
      writes.push({ path, body: JSON.parse(options.body as string) });
      return new Response(
        JSON.stringify(
          path.endsWith("/confirm")
            ? { id: 9 }
            : {
                id: 3,
                order_id: 42,
                customer_id: 7,
                version: 1,
                kind: "RETURN",
                reason: "请按我选择的原因核实申请",
                reason_code: reasonCode,
                expires_at: "2030-01-01T00:00:00Z",
                consumed: false,
              },
        ),
      );
    });
    render(<AfterSaleForm orderId={42} onDone={() => {}} />);
    fireEvent.change(screen.getByLabelText("申请原因"), {
      target: { value: reasonCode },
    });
    fireEvent.change(screen.getByLabelText("申请说明"), {
      target: { value: "请按我选择的原因核实申请" },
    });
    fireEvent.click(screen.getByRole("button", { name: "准备申请" }));
    await screen.findByRole("button", { name: "确认提交申请" });
    expect(writes).toEqual([
      {
        path: "/api/v1/after-sales/drafts",
        body: {
          order_id: 42,
          kind: "RETURN",
          reason: "请按我选择的原因核实申请",
          reason_code: reasonCode,
        },
      },
    ]);
    fireEvent.click(screen.getByRole("button", { name: "确认提交申请" }));
    await screen.findByRole("heading", { name: "申请已提交" });
    expect(writes[1]).toEqual({
      path: "/api/v1/after-sales/drafts/3/confirm",
      body: { version: 1, idempotency_key: expect.any(String) },
    });
  },
);

test("OTHER displays the backend clarification and cannot expose a confirmation button", async () => {
  const writes: Record<string, unknown>[] = [];
  vi.stubGlobal("fetch", async (_path: string, options: RequestInit) => {
    writes.push(JSON.parse(options.body as string));
    return new Response(
      JSON.stringify({
        error: {
          code: "after_sale_reason_required",
          message: "请明确申请原因或联系人工核实",
        },
      }),
      { status: 409 },
    );
  });
  render(<AfterSaleForm orderId={42} onDone={() => {}} />);
  fireEvent.change(screen.getByLabelText("申请原因"), {
    target: { value: "OTHER" },
  });
  fireEvent.change(screen.getByLabelText("申请说明"), {
    target: { value: "需要进一步说明" },
  });
  fireEvent.click(screen.getByRole("button", { name: "准备申请" }));
  await waitFor(() =>
    expect(screen.getByRole("alert")).toHaveTextContent(
      "请明确申请原因或联系人工核实",
    ),
  );
  expect(writes).toHaveLength(1);
  expect(writes[0].reason_code).toBe("OTHER");
  expect(
    screen.queryByRole("button", { name: "确认提交申请" }),
  ).not.toBeInTheDocument();
});
