"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { apiFetch, ApiError } from "@/lib/api-client";
import { useTenant } from "@/lib/tenant-context";

type Me = { userId: string; displayName?: string; maskedPhone?: string };
export default function SettingsPage() {
  const { tenant } = useTenant(); const [me, setMe] = useState<Me | null>(null); const [displayName, setDisplayName] = useState(""); const [message, setMessage] = useState(""); const [busy, setBusy] = useState(false);
  useEffect(() => { void apiFetch<Me>("/me").then(item => { setMe(item); setDisplayName(item.displayName ?? ""); }).catch(() => undefined); }, []);
  async function save(event: FormEvent) { event.preventDefault(); setBusy(true); setMessage(""); try { const updated = await apiFetch<Me>("/me", { method: "PUT", body: JSON.stringify({ displayName }) }); setMe(updated); setMessage("个人资料已保存。"); } catch (cause) { setMessage(cause instanceof ApiError ? cause.message : "保存失败"); } finally { setBusy(false); } }
  return <AppShell activeItem="设置" pageHeader={<div><h1 className="m-0 text-xl font-bold">个人设置</h1><p className="mb-0 mt-1 text-sm text-[#60799f]">账号资料属于用户本人；企业成员、席位、权限和账单在企业管理中处理。</p></div>}><div className="grid max-w-3xl gap-4"><section className="rounded-2xl border border-[#d8e6f5] bg-white p-6"><h2 className="m-0 text-base font-bold">账号资料</h2><form className="mt-4 space-y-4" onSubmit={save}><label className="block text-sm font-medium">手机号<input disabled value={me?.maskedPhone ?? ""} className="mt-2 h-10 w-full rounded-lg border border-[#d8e6f5] bg-[#f7fbff] px-3"/></label><label className="block text-sm font-medium">显示名称<input value={displayName} maxLength={80} onChange={event => setDisplayName(event.target.value)} className="mt-2 h-10 w-full rounded-lg border border-[#cddbea] px-3"/></label>{message && <p className="rounded-lg bg-[#f4f8fc] px-3 py-2 text-sm text-[#526e96]">{message}</p>}<button className="primary-button" disabled={busy} type="submit">{busy ? "保存中…" : "保存"}</button></form></section><section className="rounded-2xl border border-[#d8e6f5] bg-white p-6"><h2 className="m-0 text-base font-bold">企业租户</h2><p className="mt-2 text-sm text-[#60799f]">一个用户可加入多个企业租户；各企业数据、权限、套餐和积分互相隔离。</p><Link href="/onboarding" className="outline-button mt-3">注册或申请加入企业</Link>{tenant?.type === "ENTERPRISE" && tenant.owner && <Link href="/enterprise-management" className="ml-2 text-sm text-[#2467ca]">进入当前企业管理</Link>}</section></div></AppShell>;
}
