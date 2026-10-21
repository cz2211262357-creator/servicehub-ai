import { afterEach, expect, test, vi } from "vitest";
import { api, ApiError, setCsrf } from "./client";

afterEach(() => vi.unstubAllGlobals());
test("writes carry cookie credentials and the authenticated csrf token", async () => {
  let sent: RequestInit | undefined;
  vi.stubGlobal("fetch", async (_url: string, options: RequestInit) => {
    sent = options;
    return new Response(JSON.stringify({ id: 7 }), { status: 200 });
  });
  setCsrf("verified-csrf");
  expect(
    await api("/conversations", {
      method: "POST",
      body: JSON.stringify({ title: "售后" }),
    }),
  ).toEqual({ id: 7 });
  expect(sent?.credentials).toBe("include");
  expect(new Headers(sent?.headers).get("x-csrf-token")).toBe("verified-csrf");
});
test("conflict and overload preserve status request id and retry hints", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({
          error: {
            code: "claim_conflict",
            message: "已被领取",
            request_id: "request-123",
          },
        }),
        { status: 409, headers: { "retry-after": "4" } },
      ),
  );
  try {
    await api("/conversations/1/claim", { method: "POST" });
    throw new Error("expected error");
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).requestId).toBe("request-123");
  }
});
test("expired sessions notify the auth boundary", async () => {
  const changed = vi.fn();
  window.addEventListener("session-expired", changed);
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({ error: { code: "unauthorized", message: "请登录" } }),
        { status: 401 },
      ),
  );
  await expect(api("/auth/me")).rejects.toBeInstanceOf(ApiError);
  expect(changed).toHaveBeenCalledOnce();
  window.removeEventListener("session-expired", changed);
});
