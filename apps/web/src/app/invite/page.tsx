"use client";

import { useSearchParams, useRouter } from "next/navigation";
import { useState } from "react";
import { apiFetch, ApiError } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

export default function InvitePage() {
  const token = useSearchParams().get("token"); const router = useRouter(); const { refresh } = useWorkspace(); const [busy, setBusy] = useState(false); const [message, setMessage] = useState<string | null>(null);
  async function claim() { if (!token) return; setBusy(true); setMessage(null); try { await apiFetch("/tenants/invitations/claim", { method: "POST", body: JSON.stringify({ invitationToken: token }) }); await refresh(); router.replace("/"); } catch (cause) { if (cause instanceof ApiError && cause.status === 401) { router.replace(`/login?next=${encodeURIComponent(`/invite?token=${token}`)}`); return; } setMessage(cause instanceof ApiError ? cause.message : "领取邀请失败"); } finally { setBusy(false); } }
  return <main className="login-canvas grid min-h-screen place-items-center p-5"><section className="w-full max-w-md rounded-2xl bg-white p-7 text-[#10285b] shadow-xl"><h1 className="m-0 text-xl font-bold">加入企业</h1><p className="mt-3 text-sm leading-6 text-[#60799f]">领取后，您将加入该企业并自动占用一个席位；没有可用席位时不会加入成功。</p>{!token ? <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">邀请链接无效。</p> : <button type="button" disabled={busy} onClick={() => void claim()} className="primary-button mt-4">{busy ? "处理中…" : "领取邀请并加入"}</button>}{message && <p className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700">{message}</p>}</section></main>;
}
