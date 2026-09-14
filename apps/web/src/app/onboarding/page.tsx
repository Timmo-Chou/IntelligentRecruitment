"use client";

import { Building2, CheckCircle2, Search, UserRound } from "lucide-react";
import { FormEvent, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { apiFetch, ApiError } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type Enterprise = { tenantId: string; tenantName: string; legalName: string };

export default function OnboardingPage() {
  const router = useRouter();
  const { refresh } = useWorkspace();
  const [tab, setTab] = useState<"personal" | "create" | "join">("personal");
  return <main className="login-canvas min-h-screen p-5 text-[#10285b] lg:p-10"><div className="mx-auto max-w-3xl rounded-[26px] border border-white/80 bg-white/95 p-7 shadow-[0_18px_60px_rgba(39,100,180,0.09)] sm:p-10">
    <div className="flex items-center gap-3 text-xl font-bold text-[#09245d]"><span className="brand-mark" aria-hidden="true"><i/><i/></span>iFoundX 智能招聘工作台</div>
    <h1 className="mb-0 mt-8 text-3xl font-bold">选择使用方式</h1><p className="mt-2 text-sm text-[#60799f]">个人租户会随账号创建；您也可以注册或申请加入企业租户。</p>
    <div className="mt-7 grid gap-3 md:grid-cols-3"><Tab active={tab === "personal"} onClick={() => setTab("personal")} icon={<UserRound/>} title="个人使用"/><Tab active={tab === "create"} onClick={() => setTab("create")} icon={<Building2/>} title="注册企业"/><Tab active={tab === "join"} onClick={() => setTab("join")} icon={<Search/>} title="加入企业"/></div>
    <section className="mt-6 rounded-2xl border border-[#d8e6f5] bg-[#f9fcff] p-6">
      {tab === "personal" && <div><h2 className="m-0 text-lg font-bold">个人租户</h2><p className="mt-2 text-sm text-[#60799f]">个人功能、套餐和积分独立管理，不与任一企业租户混用。</p><button className="primary-button mt-3" type="button" onClick={() => void refresh().then(() => router.replace("/"))}>进入个人工作台</button></div>}
      {tab === "create" && <EnterpriseRegistration/>} {tab === "join" && <JoinEnterprise/>}
    </section>
  </div></main>;
}

function Tab({ active, onClick, icon, title }: { active: boolean; onClick: () => void; icon: React.ReactNode; title: string }) { return <button type="button" onClick={onClick} className={`rounded-xl border p-4 text-left transition ${active ? "border-[#2f6bff] bg-[#edf5ff]" : "border-[#d8e4f1] bg-white hover:border-[#a9c6ea]"}`}><span className="text-[#2671ed]">{icon}</span><strong className="mt-2 block text-sm">{title}</strong></button>; }

function EnterpriseRegistration() {
  const [legalName, setLegalName] = useState(""); const [creditCode, setCreditCode] = useState(""); const [contactName, setContactName] = useState(""); const [contactPhone, setContactPhone] = useState("");
  const [documentId, setDocumentId] = useState(""); const [message, setMessage] = useState<string | null>(null); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false);
  async function upload(file?: File) { if (!file) return; setBusy(true); setError(null); try { const form = new FormData(); form.append("file", file); const result = await apiFetch<{ document_id: string }>("/tenants/enterprise-registration-documents", { method: "POST", body: form }); setDocumentId(result.document_id); } catch (cause) { setDocumentId(""); setError(cause instanceof ApiError ? cause.message : "营业执照上传失败"); } finally { setBusy(false); } }
  async function submit(event: FormEvent) { event.preventDefault(); setBusy(true); setError(null); try { const availability = await apiFetch<{ available: boolean }>(`/tenants/enterprise-registration/credit-code-availability?creditCode=${encodeURIComponent(creditCode.trim().toUpperCase())}`); if (!availability.available) { setError("该统一社会信用代码已绑定有效企业租户，不能重复提交。"); return; } await apiFetch("/tenants/enterprise-registrations", { method: "POST", body: JSON.stringify({ legalName: legalName.trim(), creditCode: creditCode.trim().toUpperCase(), licenseDocumentId: documentId, contactName: contactName.trim(), contactPhone: contactPhone.trim() }) }); setMessage("企业注册申请已提交，审核通过后将按平台配置开通 Trial 或合同套餐。"); } catch (cause) { setError(cause instanceof ApiError ? cause.message : "提交企业注册失败"); } finally { setBusy(false); } }
  return <form onSubmit={submit} className="space-y-4"><h2 className="m-0 text-lg font-bold">注册企业</h2><p className="m-0 text-sm text-[#60799f]">提交企业法定名称、统一社会信用代码和营业执照原件照片，由平台运营审核。</p><Fields values={{ legalName, creditCode, contactName, contactPhone }} set={{ setLegalName, setCreditCode, setContactName, setContactPhone }}/><label className="block text-sm font-medium">营业执照原件照片<input required type="file" accept="image/*" disabled={busy} onChange={event => void upload(event.target.files?.[0])} className="mt-2 block w-full rounded-lg border border-[#cddbea] bg-white p-2 text-sm"/>{documentId && <small className="mt-1 block text-emerald-700">已上传</small>}</label>{error && <Notice error>{error}</Notice>}{message && <Notice>{message}</Notice>}<button className="primary-button" disabled={busy || !documentId} type="submit">{busy ? "处理中…" : "提交审核"}</button></form>;
}

function Fields({ values, set }: { values: { legalName: string; creditCode: string; contactName: string; contactPhone: string }; set: Record<string, (value: string) => void> }) { const fields = [["企业法定名称", "legalName", "setLegalName"], ["统一社会信用代码", "creditCode", "setCreditCode"], ["联系人", "contactName", "setContactName"], ["联系电话", "contactPhone", "setContactPhone"]] as const; return <div className="grid gap-4 sm:grid-cols-2">{fields.map(([label, key, setter]) => <label key={key} className="block text-sm font-medium">{label}<input required value={values[key]} onChange={event => set[setter](event.target.value)} className="mt-2 h-11 w-full rounded-lg border border-[#cddbea] bg-white px-3 outline-none focus:border-[#2f6bff]"/></label>)}</div>; }

function JoinEnterprise() {
  const [keyword, setKeyword] = useState(""); const [items, setItems] = useState<Enterprise[]>([]); const [message, setMessage] = useState<string | null>(null); const [busy, setBusy] = useState<string | null>(null); const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => { if (timer.current) clearTimeout(timer.current); if (!keyword.trim()) { setItems([]); return; } timer.current = setTimeout(() => { void apiFetch<Enterprise[]>(`/tenants/enterprises/search?keyword=${encodeURIComponent(keyword.trim())}`).then(setItems).catch(() => setItems([])); }, 300); return () => { if (timer.current) clearTimeout(timer.current); }; }, [keyword]);
  async function apply(id: string) { setBusy(id); try { await apiFetch(`/tenants/${id}/join-applications`, { method: "POST", body: JSON.stringify({ roleCode: "MEMBER" }) }); setMessage("申请已提交；企业 Owner 审批且有空闲席位时，系统才会加入企业并自动占用席位。"); } catch (cause) { setMessage(cause instanceof ApiError ? cause.message : "申请加入失败"); } finally { setBusy(null); } }
  return <div className="space-y-4"><h2 className="m-0 text-lg font-bold">申请加入企业</h2><p className="m-0 text-sm text-[#60799f]">企业只管理本企业成员；您的其他企业归属不会被展示。</p><input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="输入企业名称" className="h-11 w-full rounded-lg border border-[#cddbea] bg-white px-3 outline-none focus:border-[#2f6bff]"/>{items.map(item => <div key={item.tenantId} className="flex items-center justify-between rounded-lg border border-[#d8e6f5] bg-white p-3"><div><strong className="block text-sm">{item.tenantName}</strong><small className="text-[#7187a8]">{item.legalName}</small></div><button type="button" className="outline-button text-xs" disabled={busy === item.tenantId} onClick={() => void apply(item.tenantId)}>{busy === item.tenantId ? "提交中…" : "申请加入"}</button></div>)}{message && <Notice>{message}</Notice>}</div>;
}
function Notice({ children, error = false }: { children: React.ReactNode; error?: boolean }) { return <p className={`flex gap-2 rounded-lg px-3 py-2 text-sm ${error ? "bg-red-50 text-red-700" : "bg-emerald-50 text-emerald-700"}`}><CheckCircle2 size={16} className="mt-0.5 shrink-0"/>{children}</p>; }
