import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { AfterSaleForm } from "./AfterSaleForm";
import { createOutgoingMessage, sendCustomerMessage } from "../../api/live";

afterEach(() => vi.unstubAllGlobals());
test("a failed send and retry keep the original client message id", async () => {
  const ids: string[] = [];
  vi.stubGlobal("fetch", async (_url: string, options: RequestInit) => {
    ids.push(JSON.parse(options.body as string).client_message_id);
    return ids.length === 1
      ? new Response(JSON.stringify({ error: { message: "不可用" } }), {
          status: 503,
        })
      : new Response(JSON.stringify({ id: 1 }));
  });
  const message = createOutgoingMessage("退货咨询");
  await expect(sendCustomerMessage(1, message)).rejects.toThrow();
  await sendCustomerMessage(1, message);
  expect(ids).toEqual([message.id, message.id]);
});
test("preparing a draft does not submit a ticket until explicit confirmation", async () => {
  const writes: string[] = [];
  vi.stubGlobal("fetch", async (url: string) => {
    writes.push(url);
    return new Response(
      JSON.stringify(
        url.endsWith("/confirm")
          ? { id: 7 }
          : {
              id: 3,
              version: 1,
              kind: "RETURN",
              reason: "商品损坏",
              expires_at: "2030-01-01T00:00:00Z",
            },
      ),
    );
  });
  render(<AfterSaleForm orderId={1} onDone={() => {}} />);
  fireEvent.change(screen.getByLabelText("申请原因"), {
    target: { value: "QUALITY" },
  });
  fireEvent.change(screen.getByLabelText("申请说明"), {
    target: { value: "商品损坏" },
  });
  fireEvent.click(screen.getByRole("button", { name: "准备申请" }));
  await screen.findByRole("button", { name: "确认提交申请" });
  expect(writes).toHaveLength(1);
  fireEvent.click(screen.getByRole("button", { name: "确认提交申请" }));
  await waitFor(() =>
    expect(writes.some((path) => path.endsWith("/confirm"))).toBe(true),
  );
});
