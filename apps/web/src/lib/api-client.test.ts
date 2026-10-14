import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiStream, setAccessToken } from "@/lib/api-client";

describe("apiStream", () => {
  beforeEach(() => setAccessToken(null));
  afterEach(() => vi.unstubAllGlobals());

  it("accepts SSE success and JSON authorization errors", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await apiStream("/tenants/tenant-1/events");

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/tenants/tenant-1/events"),
      expect.objectContaining({ headers: { Accept: "text/event-stream, application/json" } }),
    );
  });

  it("preserves the API error code and request id when stream authorization fails", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "BOSS_SESSION_REQUIRED",
      message: "BOSS 登录上下文缺失，请重新登录",
      request_id: "req-sse-auth",
    }), { status: 401, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiStream("/tenants/tenant-1/events", undefined, false)).rejects.toMatchObject({
      status: 401,
      body: {
        code: "BOSS_SESSION_REQUIRED",
        message: "BOSS 登录上下文缺失，请重新登录",
        request_id: "req-sse-auth",
      },
    } satisfies Partial<ApiError>);
  });
});
