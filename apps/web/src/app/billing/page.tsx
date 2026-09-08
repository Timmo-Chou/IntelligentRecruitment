"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError, apiFetch } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type Billing = { balance_minor?: number; reserved_minor?: number; currency?: string; status?: string; statements?: Array<{ id: string; period: string; total_amount_minor: number; currency: string; status: string }> };

/** Billing is a read-only view of BOSS finance. Recruitment has no local balance or recharge API. */
export default function BillingPage() {
  const { workspaceId, workspace, loading } = useWorkspace(); const [data, setData] = useState<Billing | null>(null); const [error, setError] = useState("");
  useEffect(() => { if (!workspaceId) return; apiFetch<Billing>(`/companies/${workspaceId}/billing`).then(setData).catch(e => setError(e instanceof ApiError ? e.message : "账单加载失败")); }, [workspaceId]);
  return <AppShell activeItem="额度与账单" pageHeader={<section><h1 className="m-0 text-[25px] font-bold text-[#09245d]">额度与账单</h1><p className="mt-1 text-sm text-[#55709d]">账单、余额和审计流水由 BOSS 统一管理。</p></section>}>
    {loading ? <Hint text="正在加载 BOSS Company…"/> : !workspaceId ? <Hint text="暂无可用的 BOSS Company"/> : error ? <Hint text={error}/> : <div className="mt-5 space-y-5"><section className="grid gap-4 sm:grid-cols-3"><Metric title="可用余额" value={`¥${((data?.balance_minor ?? 0) / 100).toFixed(2)}`}/><Metric title="冻结金额" value={`¥${((data?.reserved_minor ?? 0) / 100).toFixed(2)}`}/><Metric title="所属 Company" value={workspace?.name ?? "-"}/></section><section className="rounded-xl border border-[#d6e5f5] bg-white p-5"><div className="flex items-center justify-between"><h2 className="m-0 text-base">BOSS 账单</h2><Link href="/billing/recharge" className="outline-button !h-9">充值说明</Link></div>{!data?.statements?.length ? <p className="mt-5 text-sm text-[#7187a8]">暂无已出具账单。AI 使用量结算和账单由 BOSS 记录。</p> : <div className="mt-4 space-y-2">{data.statements.map(item => <div key={item.id} className="flex items-center justify-between rounded-lg border border-[#e4edf7] p-3 text-sm"><span>{item.period} · {item.status}</span><strong>¥{(item.total_amount_minor / 100).toFixed(2)}</strong></div>)}</div>}</section></div>}
  </AppShell>;
}
function Metric({ title, value }: { title: string; value: string }) { return <article className="rounded-xl border border-[#d6e5f5] bg-white p-5"><p className="m-0 text-sm text-[#7187a8]">{title}</p><strong className="mt-3 block text-2xl text-[#09245d]">{value}</strong></article>; }
function Hint({ text }: { text: string }) { return <p className="mt-5 rounded-xl border border-[#d6e5f5] bg-white p-6 text-sm text-[#7187a8]">{text}</p>; }
