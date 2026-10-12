import { getSafeErrorMessage } from "@intelligent-recruitment/display-dictionary";

export { getDisplayLabel, resolveCatalogLabel, resolveCodeLabel, getSafeErrorMessage } from "@intelligent-recruitment/display-dictionary";

export function getAdminErrorInfo(error: unknown, fallback = "操作暂未完成，请稍后重试。") {
  if (error && typeof error === "object" && "body" in error) {
    const body = (error as { body?: { code?: unknown; request_id?: unknown } }).body;
    const code = typeof body?.code === "string" ? body.code : null;
    const requestId = typeof body?.request_id === "string" ? body.request_id : null;
    return { message: getSafeErrorMessage(code, null, fallback), requestId };
  }
  return { message: fallback, requestId: null };
}

