// admin-api-client 单元测试：验证 Token 存储和请求头注入
import { describe, expect, it, beforeEach, vi } from "vitest";

vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "http://localhost:8081/api/v1");

// 手动实现 localStorage（jsdom 环境可能不提供完整实现）
const storage = new Map<string, string>();
vi.stubGlobal("localStorage", {
  getItem: (k: string) => storage.get(k) ?? null,
  setItem: (k: string, v: string) => { storage.set(k, v); },
  removeItem: (k: string) => { storage.delete(k); },
  clear: () => { storage.clear(); },
});

// 动态导入确保 localStorage mock 先生效
let setAccessToken: typeof import("./admin-api-client").setAccessToken;
let getAccessToken: typeof import("./admin-api-client").getAccessToken;
let adminApiFetch: typeof import("./admin-api-client").adminApiFetch;

beforeEach(async () => {
  storage.clear();
  vi.resetModules();
  const mod = await import("./admin-api-client");
  setAccessToken = mod.setAccessToken;
  getAccessToken = mod.getAccessToken;
  adminApiFetch = mod.adminApiFetch;
});

describe("admin-api-client", () => {
  it("setAccessToken 存储 token 到 localStorage", () => {
    setAccessToken("test-token-123");
    expect(storage.get("admin-access-token")).toBe("test-token-123");
    expect(getAccessToken()).toBe("test-token-123");
  });

  it("setAccessToken(null) 清除 localStorage 中的 token", () => {
    storage.set("admin-access-token", "old-token");
    setAccessToken(null);
    expect(storage.get("admin-access-token")).toBeUndefined();
    expect(getAccessToken()).toBeNull();
  });

  it("getAccessToken 从 localStorage 恢复 token", () => {
    storage.set("admin-access-token", "restored-token");
    expect(getAccessToken()).toBe("restored-token");
  });

  it("adminApiFetch 注入 Authorization Bearer 请求头", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve('{"success":true}'),
    });
    vi.stubGlobal("fetch", fetchMock);

    setAccessToken("my-token");
    await adminApiFetch("/test");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers.Authorization).toBe("Bearer my-token");
  });

  it("adminApiFetch 无 token 时不注入 Authorization 头", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve("{}"),
    });
    vi.stubGlobal("fetch", fetchMock);

    setAccessToken(null);
    await adminApiFetch("/test");

    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers.Authorization).toBeUndefined();
  });

  it("adminApiFetch 401 时清除 token", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({ code: "UNAUTHENTICATED", message: "过期" }),
    });
    vi.stubGlobal("fetch", fetchMock);

    setAccessToken("expired-token");
    await expect(adminApiFetch("/test")).rejects.toThrow();
    expect(storage.get("admin-access-token")).toBeUndefined();
  });
});
