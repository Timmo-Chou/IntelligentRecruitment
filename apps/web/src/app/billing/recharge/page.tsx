"use client";

import Link from "next/link";
import { ChangeEvent, FormEvent, useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError, apiFetch } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type ReceivingAccount = { bankName: string; beneficiaryName: string; accountNumber: string; contactPhone?: string | null; contactEmail?: string | null };
type RechargeContext = { alipayEnabled: boolean; transferEnabled: boolean; receivingAccount?: ReceivingAccount | null };
type AlipayOrder = { paymentOrderId: string; outTradeNo: string; amountMinor: number; expiresAt: string; paymentForm: string };
type TransferOrder = { paymentOrderId: string; outTradeNo: string; amountMinor: number; expiresAt: string; receivingAccount: ReceivingAccount };

export default function RechargePage() {
  const { workspace, workspaceId, loading } = useWorkspace();
  const companyId = workspace?.type === "COMPANY" ? workspaceId : null;
  const [context, setContext] = useState<RechargeContext | null>(null); const [amount, setAmount] = useState("100"); const [payerName, setPayerName] = useState("");
  const [transfer, setTransfer] = useState<TransferOrder | null>(null); const [proof, setProof] = useState<File | null>(null); const [error, setError] = useState(""); const [message, setMessage] = useState(""); const [submitting, setSubmitting] = useState(false);
  useEffect(() => {
    if (!companyId) { setContext(null); return; }
    setContext(null); setTransfer(null); setError("");
    apiFetch<RechargeContext>(`/companies/${companyId}/billing/recharge-context`).then(setContext).catch(cause => setError(cause instanceof ApiError ? cause.message : "充值方式加载失败"));
  }, [companyId]);
  const amountMinor = Math.round(Number(amount) * 100);
  function requestPayload() { return { amountMinor, payerName: payerName.trim() }; }
  async function payWithAlipay() {
    setSubmitting(true); setError(""); setMessage("");
    try {
      const order = await apiFetch<AlipayOrder>(`/companies/${companyId}/billing/recharge/alipay`, { method: "POST", body: JSON.stringify(requestPayload()) });
      const documentFragment = new DOMParser().parseFromString(order.paymentForm, "text/html"); const form = documentFragment.querySelector("form");
      if (!form) throw new Error("BOSS 返回的支付宝支付表单无效");
      document.body.appendChild(form); (form as HTMLFormElement).submit();
    } catch (cause) { setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "支付宝订单创建失败"); setSubmitting(false); }
  }
  async function createTransfer() {
    setSubmitting(true); setError(""); setMessage("");
    try { setTransfer(await apiFetch<TransferOrder>(`/companies/${companyId}/billing/recharge/transfer`, { method: "POST", body: JSON.stringify(requestPayload()) })); }
    catch (cause) { setError(cause instanceof ApiError ? cause.message : "转账订单创建失败"); }
    finally { setSubmitting(false); }
  }
  async function uploadProof(event: FormEvent) {
    event.preventDefault(); if (!transfer || !proof) return;
    setSubmitting(true); setError(""); setMessage("");
    try { const body = new FormData(); body.append("file", proof); await apiFetch(`/companies/${companyId}/billing/recharge/transfer/${transfer.paymentOrderId}/proof`, { method: "POST", body }); setMessage("凭证已提交，平台管理员审核通过后将充值到该 Company。"); setProof(null); }
    catch (cause) { setError(cause instanceof ApiError ? cause.message : "转账凭证提交失败"); }
    finally { setSubmitting(false); }
  }
  return <AppShell activeItem="额度与账单" pageHeader={<section><h1 className="m-0 text-[25px] font-bold text-[#09245d]">充值额度</h1><p className="mt-1 text-sm text-[#55709d]">支付订单、收款账户和到账审核均由 BOSS 财务控制面负责。</p></section>}>
    {loading ? <Hint text="正在加载 BOSS Company…"/> : !companyId ? <Hint text="请先在右上角切换至企业 Company 后充值。个人账号不提供 Company 额度充值。"/> : <main className="mt-5 max-w-3xl space-y-5">
      {error && <Notice error text={error}/>} {message && <Notice text={message}/>}<section className="rounded-2xl border border-[#d6e5f5] bg-white p-6"><h2 className="m-0 text-lg text-[#09245d]">为 {workspace?.name} 充值</h2><p className="mt-2 text-sm text-[#7187a8]">单次金额 ¥10–¥5,000；订单创建后 30 分钟内完成支付或提交凭证。</p><div className="mt-5 grid gap-4 sm:grid-cols-2"><label className="text-sm font-medium">充值金额（元）<input inputMode="decimal" min="10" max="5000" required value={amount} onChange={event => setAmount(event.target.value)} className="mt-2 h-11 w-full rounded-lg border border-[#cbdbea] px-3"/></label><label className="text-sm font-medium">付款方名称<input required maxLength={120} value={payerName} onChange={event => setPayerName(event.target.value)} placeholder="企业或个人付款方名称" className="mt-2 h-11 w-full rounded-lg border border-[#cbdbea] px-3"/></label></div></section>
      <section className="grid gap-4 sm:grid-cols-2"><article className="rounded-2xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 text-base">支付宝</h2><p className="mt-2 min-h-10 text-sm text-[#7187a8]">电脑网站支付。订单和签名由 BOSS 生成，支付页面将跳转至支付宝。</p><button type="button" disabled={submitting || !context?.alipayEnabled || !validAmount(amountMinor) || !payerName.trim()} onClick={() => void payWithAlipay()} className="primary-button mt-4 !h-10">{submitting ? "正在创建…" : context?.alipayEnabled ? "前往支付宝支付" : "暂未启用"}</button></article><article className="rounded-2xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 text-base">对公转账</h2><p className="mt-2 min-h-10 text-sm text-[#7187a8]">创建订单后按 BOSS 收款账户转账，并上传真实转账凭证供平台管理员审核。</p><button type="button" disabled={submitting || !context?.transferEnabled || !validAmount(amountMinor) || !payerName.trim()} onClick={() => void createTransfer()} className="outline-button mt-4 !h-10">{submitting ? "正在创建…" : context?.transferEnabled ? "创建转账订单" : "暂未启用"}</button></article></section>
      {transfer && <TransferCard order={transfer} proof={proof} onProof={event => setProof(event.target.files?.[0] ?? null)} onSubmit={uploadProof} submitting={submitting}/>}<Link href="/billing" className="inline-flex text-sm text-[#2467ca]">返回额度与账单</Link>
    </main>}
  </AppShell>;
}

function TransferCard({ order, proof, onProof, onSubmit, submitting }: { order: TransferOrder; proof: File | null; onProof: (event: ChangeEvent<HTMLInputElement>) => void; onSubmit: (event: FormEvent) => Promise<void>; submitting: boolean }) { const account = order.receivingAccount; return <section className="rounded-2xl border border-[#c9dcf4] bg-[#f8fbff] p-6"><h2 className="m-0 text-base text-[#09245d]">转账订单已创建</h2><dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2"><Item label="订单号" value={order.outTradeNo}/><Item label="应付金额" value={`¥${(order.amountMinor / 100).toFixed(2)}`}/><Item label="开户行" value={account.bankName}/><Item label="收款户名" value={account.beneficiaryName}/><Item label="收款账号" value={account.accountNumber}/><Item label="支付截止" value={new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(order.expiresAt))}/></dl><form onSubmit={event => void onSubmit(event)} className="mt-5 border-t border-[#d6e5f5] pt-5"><label className="block text-sm font-medium">上传转账凭证<input required accept="image/jpeg,image/png,image/webp,application/pdf" type="file" onChange={onProof} className="mt-2 block w-full text-sm"/></label><p className="mb-0 mt-2 text-xs text-[#7187a8]">仅支持 JPG、PNG、WEBP、PDF，且不超过 10MB。{proof ? `已选择：${proof.name}` : ""}</p><button disabled={submitting || !proof} className="primary-button mt-4 !h-10" type="submit">{submitting ? "提交中…" : "提交凭证审核"}</button></form></section>; }
function Item({ label, value }: { label: string; value: string }) { return <div><dt className="text-xs text-[#7187a8]">{label}</dt><dd className="m-0 mt-1 break-all font-medium text-[#163869]">{value}</dd></div>; }
function validAmount(amountMinor: number) { return Number.isInteger(amountMinor) && amountMinor >= 1000 && amountMinor <= 500000; }
function Hint({ text }: { text: string }) { return <p className="mt-5 rounded-xl border border-[#d6e5f5] bg-white p-6 text-sm text-[#7187a8]">{text}</p>; }
function Notice({ text, error = false }: { text: string; error?: boolean }) { return <p className={`rounded-xl p-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-emerald-50 text-emerald-700"}`}>{text}</p>; }
