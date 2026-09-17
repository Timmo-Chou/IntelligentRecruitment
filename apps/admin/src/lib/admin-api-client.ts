// 管理后台 API 客户端
// 使用 Bearer Token（用户名密码登录后签发）进行身份认证

export type ApiErrorBody = {
  code: string;
  message: string;
  request_id?: string;
};

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ApiErrorBody,
  ) {
    super(body.message);
  }
}

const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1";
const TOKEN_STORAGE_KEY = "admin-access-token";

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
  if (typeof window !== "undefined") {
    if (token) localStorage.setItem(TOKEN_STORAGE_KEY, token);
    else localStorage.removeItem(TOKEN_STORAGE_KEY);
  }
}

export function getAccessToken(): string | null {
  if (typeof window !== "undefined" && !accessToken) {
    accessToken = localStorage.getItem(TOKEN_STORAGE_KEY);
  }
  return accessToken;
}

/**
 * 管理后台专用 fetch 封装
 * 自动从 localStorage 读取 access_token 并添加到 Authorization 请求头
 */
export async function adminApiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getAccessToken();
  const multipart = typeof FormData !== "undefined" && init?.body instanceof FormData;

  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(multipart ? {} : { "Content-Type": "application/json" }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });

  if (response.status === 401) {
    // Token 过期，清除并跳转登录
    setAccessToken(null);
    if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
      window.location.href = "/login";
    }
    throw new ApiError(401, { code: "UNAUTHENTICATED", message: "登录已过期，请重新登录" });
  }

  if (!response.ok) {
    const body = (await response.json().catch(() => ({
      code: "CLIENT_UNEXPECTED_RESPONSE",
      message: "服务暂时不可用，请稍后重试",
    }))) as ApiErrorBody;
    throw new ApiError(response.status, body);
  }

  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}
