"use client";

import { useState } from "react";
import { getAdminErrorInfo } from "@/lib/display-label";

export function AdminErrorNotice({
  error,
  fallback = "操作暂未完成，请稍后重试。",
  className = "",
}: {
  error: unknown;
  fallback?: string;
  className?: string;
}) {
  const { message, requestId } = getAdminErrorInfo(error, fallback);
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);

  async function copyRequestId() {
    if (!requestId) return;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(requestId);
      } else {
        const field = document.createElement("textarea");
        field.value = requestId;
        field.setAttribute("readonly", "");
        field.style.position = "fixed";
        field.style.opacity = "0";
        document.body.appendChild(field);
        field.select();
        const successful = document.execCommand("copy");
        document.body.removeChild(field);
        if (!successful) throw new Error("CLIPBOARD_COPY_FAILED");
      }
      setCopyFailed(false);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      setCopyFailed(true);
    }
  }

  return (
    <div role="alert" className={className}>
      <span>{message}</span>
      {requestId && (
        <details className="mt-1 text-xs">
          <summary className="cursor-pointer">查看诊断编号</summary>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <code className="select-all">{requestId}</code>
            <button type="button" onClick={() => void copyRequestId()} className="underline">
              {copied ? "已复制" : copyFailed ? "复制失败" : "复制编号"}
            </button>
            {copyFailed && <span role="status">可选中编号后手动复制。</span>}
          </div>
        </details>
      )}
    </div>
  );
}
