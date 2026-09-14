// 管理后台 API 客户端
// 使用 X-Platform-Admin-Key 请求头进行身份认证

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

/**
 * 管理后台专用 fetch 封装
 * 自动从 localStorage 读取 adminKey 并添加到请求头
 */
export async function adminApiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  // 从 localStorage 获取管理员密钥
  const adminKey = typeof window !== "undefined" ? localStorage.getItem("admin-key") : null;

  const multipart = typeof FormData !== "undefined" && init?.body instanceof FormData;

  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(multipart ? {} : { "Content-Type": "application/json" }),
      ...(adminKey ? { "X-Platform-Admin-Key": adminKey } : {}),
      ...init?.headers,
    },
  });

  if (!response.ok) {
    const body = (await response.json().catch(() => ({
      code: "CLIENT_UNEXPECTED_RESPONSE",
      message: "服务暂时不可用，请稍后重试",
    }))) as ApiErrorBody;
    throw new ApiError(response.status, body);
  }

  if (response.status === 204) return undefined as T;
  // 空响应体不解析 JSON（如 void 返回值的 POST 接口）
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}