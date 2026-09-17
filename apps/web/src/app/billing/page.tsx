"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError, apiFetch } from "@/lib/api-client";
import { useTenant } from "@/lib/tenant-context";

type Billing = { availableCredits: number; subscription?: { kind: string; seatCapacity: number; creditAmount: number; endsAt: string; status: string } | null; creditLots: Array<{ creditLotId: string; sourceType: string; originalAmount: number; availableAmount: number; expiresAt: string; status: string }>; contracts: Array<{ contractOrderId: string; contractNumber: string; status: string; seatCount: number; startsAt: string; endsAt: string }> };
type CreditConsumption = { consumptionId: string; actorUserId: string; featureCode: string; consumedAmount: number; consumedAt: string };

export default function BillingPage() {
  const { tenantId, tenant, loading } = useTenant(); const [data, setData] = useState<Billing | null>(null); const [consumptions, setConsumptions] = useState<CreditConsumption[]>([]); const [error, setError] = useState("");
  useEffect(() => {
    if (!tenantId || !tenant) return;
    setData(null); setConsumptions([]); setError("");
    void apiFetch<CreditConsumption[]>(`/tenants/${tenantId}/my-credit-consumptions`).then(setConsumptions).catch(cause => setError(cause instanceof ApiError ? cause.message : "消耗明细加载失败"));
    if (tenant.type === "PERSONAL" || tenant.owner) {
      void apiFetch<Billing>(`/tenants/${tenantId}/billing`).then(setData).catch(cause => setError(cause instanceof ApiError ? cause.message : "账单加载失败"));
    }
  }, [tenantId, tenant]);
  const enterprise = tenant?.type === "ENTERPRISE";
  return <AppShell activeItem="额度与账单" pageHeader={<section><h1 className="m-0 text-[25px] font-bold text-[#09245d]">套餐、积分与账单</h1><p className="mt-1 text-sm text-[#55709d]">积分余额与消耗按当前租户独立管理。</p></section>}>
    {loading ? <Hint text="正在加载租户积分…"/> : !tenantId ? <Hint text="暂无可用租户"/> : error ? <Hint text={error}/> : <div className="mt-5 space-y-5">{data ? <><section className="grid gap-4 sm:grid-cols-3"><Metric title="可用积分" value={String(data.availableCredits)}/><Metric title="当前套餐" value={data.subscription?.kind ?? "未开通"}/><Metric title="套餐到期" value={data.subscription?.endsAt ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium" }).format(new Date(data.subscription.endsAt)) : "--"}/></section><section className="rounded-xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 text-base">积分包</h2>{data.creditLots.length ? <div className="mt-4 divide-y divide-[#e4edf7]">{data.creditLots.map(item => <div key={item.creditLotId} className="flex justify-between py-3 text-sm"><span>{item.sourceType} · {item.status}</span><span>{item.availableAmount}/{item.originalAmount}</span></div>)}</div> : <p className="mt-4 text-sm text-[#7187a8]">暂无可用积分包。</p>}</section></> : <Hint text="企业共享积分、套餐与合同仅由 Owner 查看。"/>}<section className="rounded-xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 text-base">我的积分消耗</h2>{consumptions.length ? <div className="mt-4 divide-y divide-[#e4edf7]">{consumptions.map(item => <div key={item.consumptionId} className="flex justify-between py-3 text-sm"><span>{item.featureCode}</span><span>{item.consumedAmount} 积分 · {new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(item.consumedAt))}</span></div>)}</div> : <p className="mt-4 text-sm text-[#7187a8]">暂无积分消耗记录。</p>}</section>{enterprise && tenant?.owner && <Link href="/enterprise-management?tab=billing" className="outline-button">查看企业合同订单与账单</Link>}<p className="text-sm text-[#7187a8]">企业套餐与续期由合同订单及平台运营开通；个人套餐和积分包的线上支付将只在个人租户内提供，不会与企业合同账户混用。</p></div>}
  </AppShell>;
}
function Metric({ title, value }: { title: string; value: string }) { return <article className="rounded-xl border border-[#d6e5f5] bg-white p-5"><p className="m-0 text-sm text-[#7187a8]">{title}</p><strong className="mt-3 block text-2xl text-[#09245d]">{value}</strong></article>; }
function Hint({ text }: { text: string }) { return <p className="mt-5 rounded-xl border border-[#d6e5f5] bg-white p-6 text-sm text-[#7187a8]">{text}</p>; }
