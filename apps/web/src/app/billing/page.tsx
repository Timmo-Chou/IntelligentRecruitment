"use client";

import { Clock3, Coins, ReceiptText } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { apiFetch, ApiError } from "@/lib/api-client";

type Workspace={id:string;name:string;hasDataAccess:boolean};
type Billing={currency:string;availableAmountMinor:number;reservedAmountMinor:number;canViewLedger:boolean;creditLots:{id:string;sourceType:string;originalAmountMinor:number;availableAmountMinor:number;issuedAt:string;expiresAt:string;status:string}[];ledger:{id:string;entryType:string;amountMinor:number;businessReference:string;reason:string;createdAt:string}[]};

export default function BillingPage(){const[spaces,setSpaces]=useState<Workspace[]>([]);const[selected,setSelected]=useState("");const[data,setData]=useState<Billing|null>(null);const[error,setError]=useState("");
  useEffect(()=>{apiFetch<Workspace[]>("/workspaces").then(items=>{const allowed=items.filter(i=>i.hasDataAccess);setSpaces(allowed);if(allowed[0])setSelected(allowed[0].id);}).catch(e=>setError(e instanceof ApiError?e.message:"加载失败"));},[]);
  useEffect(()=>{if(selected)apiFetch<Billing>(`/workspaces/${selected}/billing`).then(setData).catch(e=>setError(e instanceof ApiError?e.message:"账本加载失败"));},[selected]);
  return <AppShell activeItem="额度与账本" pageHeader={<section className="flex flex-wrap items-end justify-between gap-3"><div><h1 className="m-0 text-[25px] font-bold text-[#09245d]">额度与账本</h1><p className="mt-1 text-sm text-[#55709d]">试用金按 Workspace 归属，所有资金变动保留不可变流水。</p></div><div className="flex items-center gap-2"><select value={selected} onChange={e=>setSelected(e.target.value)} className="h-10 rounded-lg border border-[#bdd3ef] bg-white px-3 text-sm">{spaces.map(s=><option key={s.id} value={s.id}>{s.name}</option>)}</select><Link href="/billing/recharge" className="primary-button">充值额度</Link></div></section>}>
    {error&&<p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{error}</p>}
    <section className="mt-5 grid gap-3 md:grid-cols-3"><Metric icon={<Coins/>} label="可用额度" value={money(data?.availableAmountMinor)}/><Metric icon={<Clock3/>} label="已冻结" value={money(data?.reservedAmountMinor)}/><Metric icon={<ReceiptText/>} label="额度批次" value={`${data?.creditLots.length??0} 笔`}/></section>
    <div className="mt-4 grid gap-4 xl:grid-cols-[360px_minmax(0,1fr)]"><section className="rounded-xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 text-base">额度批次</h2>{data?.creditLots.length?data.creditLots.map(l=><div key={l.id} className="mt-3 rounded-lg border border-[#dce8f4] p-3 text-sm"><div className="flex justify-between"><strong>{l.sourceType==="TRIAL"?"试用额度":l.sourceType}</strong><strong className="text-[#07945f]">{money(l.availableAmountMinor)}</strong></div><p className="mb-0 mt-2 text-xs text-[#7187a8]">到期：{format(l.expiresAt)} · 原始 {money(l.originalAmountMinor)}</p></div>):<Empty/>}</section>
      <section className="overflow-hidden rounded-xl border border-[#d6e5f5] bg-white"><div className="border-b border-[#e1ebf5] p-5"><h2 className="m-0 text-base">账本流水</h2></div><div className="overflow-x-auto"><table className="w-full min-w-[620px] text-left text-sm"><thead className="bg-[#f8fbff] text-[#60799f]"><tr><th className="p-3">时间</th><th className="p-3">类型</th><th className="p-3">金额</th><th className="p-3">业务引用</th><th className="p-3">说明</th></tr></thead><tbody>{data?.ledger.map(e=><tr key={e.id} className="border-t border-[#e4edf7]"><td className="p-3">{format(e.createdAt)}</td><td className="p-3">{e.entryType}</td><td className={`p-3 font-semibold ${e.amountMinor>=0?"text-[#07945f]":"text-[#d14f4f]"}`}>{signedMoney(e.amountMinor)}</td><td className="p-3 text-xs">{e.businessReference}</td><td className="p-3 text-xs">{e.reason}</td></tr>)}</tbody></table>{data&&!data.canViewLedger?<p className="p-5 text-sm text-[#7187a8]">当前角色可以查看余额，但完整账本仅 Workspace Owner/Admin 可见。</p>:!data?.ledger.length&&<Empty/>}</div></section></div>
  </AppShell>}
function Metric({icon,label,value}:{icon:React.ReactNode;label:string;value:string}){return <article className="metric-card"><span className="metric-icon">{icon}</span><div><p className="m-0 text-sm text-[#526b93]">{label}</p><strong className="mt-1 block text-2xl text-[#09245d]">{value}</strong></div></article>}
function Empty(){return <p className="p-5 text-sm text-[#7187a8]">暂无记录</p>}
function money(value?:number){return value===undefined?"--":`¥${(value/100).toFixed(2)}`}
function signedMoney(value:number){return `${value>=0?"+":"-"}¥${(Math.abs(value)/100).toFixed(2)}`}
function format(value:string){return new Intl.DateTimeFormat("zh-CN",{dateStyle:"medium",timeStyle:"short"}).format(new Date(value))}
