import type { components } from "./generated/schema";
export type Schemas = components["schemas"];
let csrf = "";
export function setCsrf(value: string) {
  csrf = value;
}
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status: number,
    public requestId = "",
    public retryAfter = 0,
  ) {
    super(message);
    this.name = "ApiError";
  }
}
export async function api<T = unknown>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("accept", "application/json");
  if (options.body && !(options.body instanceof FormData))
    headers.set("content-type", "application/json");
  const method = (options.method ?? "GET").toUpperCase();
  if (!["GET", "HEAD", "OPTIONS"].includes(method))
    headers.set("x-csrf-token", csrf);
  let response: Response;
  try {
    response = await fetch("/api/v1" + path, {
      ...options,
      headers,
      credentials: "include",
    });
  } catch {
    throw new ApiError(
      "network_unavailable",
      "暂时无法连接服务，请检查网络后重试",
      0,
    );
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (response.status === 401 && !path.endsWith("/login"))
      window.dispatchEvent(new Event("session-expired"));
    throw new ApiError(
      body.error?.code ?? "request_failed",
      body.error?.message ?? "操作失败，请稍后重试",
      response.status,
      body.error?.request_id ?? response.headers.get("x-request-id") ?? "",
      Number(response.headers.get("retry-after") ?? 0),
    );
  }
  return response.status === 204
    ? (undefined as T)
    : ((await response.json()) as T);
}
export function post<T>(path: string, body?: unknown) {
  return api<T>(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}
