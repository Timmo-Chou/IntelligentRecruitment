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

let accessToken: string | null = null;
let refreshPromise: Promise<string | null> | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

export async function apiFetch<T>(path: string, init?: RequestInit, allowRefresh = true): Promise<T> {
  const multipart = typeof FormData !== "undefined" && init?.body instanceof FormData;
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      Accept: "application/json",
      ...(multipart ? {} : { "Content-Type": "application/json" }),
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...init?.headers,
    },
  });

  if (response.status === 401 && allowRefresh && path !== "/auth/refresh") {
    const refreshed = await refreshAccessToken();
    if (refreshed) return apiFetch<T>(path, init, false);
  }

  if (!response.ok) {
    const body = (await response.json().catch(() => ({
      code: "CLIENT_UNEXPECTED_RESPONSE",
      message: "服务暂时不可用，请稍后重试",
    }))) as ApiErrorBody;
    throw new ApiError(response.status, body);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export async function apiDownload(path: string): Promise<Blob> {
  let response = await fetch(`${apiBaseUrl}${path}`, {
    credentials: "include",
    headers: { ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
  });
  if (response.status === 401) {
    const refreshed = await refreshAccessToken();
    if (refreshed) response = await fetch(`${apiBaseUrl}${path}`, {
      credentials: "include",
      headers: { Authorization: `Bearer ${refreshed}` },
    });
  }
  if (!response.ok) throw new ApiError(response.status, {
    code: "DOWNLOAD_FAILED", message: "文件下载失败，请稍后重试",
  });
  return response.blob();
}

type RefreshResponse = { access_token: string };

async function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      const response = await fetch(`${apiBaseUrl}/auth/refresh`, {
        method: "POST",
        credentials: "include",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
      });
      if (!response.ok) {
        accessToken = null;
        return null;
      }
      const body = (await response.json()) as RefreshResponse;
      accessToken = body.access_token;
      return accessToken;
    })().finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}
