"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError, apiFetch } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type Billing = { total_micro?: number; gift_micro?: number; recharge_micro?: number; reserved_gift_micro?: number; reserved_recharge_micro?: number; currency?: string; status?: string; statements?: Array<{ id: string; period: string; total_amount_minor: number; currency: string; status: string }> };

/** Billing is a read-only view of BOSS finance. Recruitment has no local balance or recharge API. */
export default function BillingPage() {
  const { workspaceId, workspace, loading } = useWorkspace(); const [data, setData] = useState<Billing | null>(null); const [error, setError] = useState("");
  useEffect(() => { if (!workspaceId || !workspace) return; const endpoint = workspace.type === "PERSONAL" ? `/tenants/${workspaceId}/billing` : `/companies/${workspaceId}/billing`; apiFetch<Billing>(endpoint).then(setData).catch(e => setError(e instanceof ApiError ? e.message : "账单加载失败")); }, [workspaceId, workspace]);
  return <AppShell activeItem="额度与账单" pageHeader={<section><h1 className="m-0 text-[25px] font-bold text-[#09245d]">额度与账单</h1><p className="mt-1 text-sm text-[#55709d]">账单、余额和审计流水由 BOSS 统一管理。</p></section>}>
    {loading ? <Hint text="正在加载 BOSS 额度…"/> : !workspaceId ? <Hint text="暂无可用的 BOSS 工作空间"/> : error ? <Hint text={error}/> : <div className="mt-5 space-y-5"><section className="grid gap-4 sm:grid-cols-3"><Metric title="总钱包余额" value={`¥${((data?.total_micro ?? 0) / 1_000_000).toFixed(2)}`}/><Metric title="赠送余额" value={`¥${((data?.gift_micro ?? 0) / 1_000_000).toFixed(2)}`}/><Metric title="充值余额" value={`¥${((data?.recharge_micro ?? 0) / 1_000_000).toFixed(2)}`}/></section><section className="rounded-xl border border-[#d6e5f5] bg-white p-5"><div className="flex items-center justify-between"><h2 className="m-0 text-base">BOSS 账单</h2><Link href="/billing/recharge" className="outline-button !h-9">充值说明</Link></div>{!data?.statements?.length ? <p className="mt-5 text-sm text-[#7187a8]">暂无已出具账单。AI 使用量结算和账单由 BOSS 记录。</p> : <div className="mt-4 space-y-2">{data.statements.map(item => <div key={item.id} className="flex items-center justify-between rounded-lg border border-[#e4edf7] p-3 text-sm"><span>{item.period} · {item.status}</span><strong>¥{(item.total_amount_minor / 100).toFixed(2)}</strong></div>)}</div>}</section></div>}
  </AppShell>;
}
function Metric({ title, value }: { title: string; value: string }) { return <article className="rounded-xl border border-[#d6e5f5] bg-white p-5"><p className="m-0 text-sm text-[#7187a8]">{title}</p><strong className="mt-3 block text-2xl text-[#09245d]">{value}</strong></article>; }
function Hint({ text }: { text: string }) { return <p className="mt-5 rounded-xl border border-[#d6e5f5] bg-white p-6 text-sm text-[#7187a8]">{text}</p>; }
