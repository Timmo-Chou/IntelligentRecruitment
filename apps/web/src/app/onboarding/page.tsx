"use client";

import { Building2, CheckCircle2, LogOut, Search, UserRound, X } from "lucide-react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { apiFetch, ApiError, setAccessToken } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type Mode = "personal" | "company";
type CompanySubMode = "create" | "join";

type CompanySearchResult = {
  id: string;
  displayName: string;
  legalName: string;
  verificationStatus: string;
  memberCount: number;
};

export default function OnboardingPage() {
  const [mode, setMode] = useState<Mode>("personal");
  const [pending,setPending]=useState<{legalName:string;displayName:string}|null>(null);
  const router=useRouter();
  useEffect(()=>{apiFetch<{legalName:string;displayName:string}|null>("/company-verifications/pending").then(setPending).catch(error=>{if(error instanceof ApiError&&error.status===401)router.replace("/login");});},[router]);
  if(pending) return <main className="min-h-screen bg-[#f7fbff] text-[#10285b]"><header className="app-header flex h-[66px] items-center justify-between border-b border-[#dbe9f8] px-6 lg:px-8"><div className="flex items-center gap-3 text-[21px] font-bold tracking-tight text-[#09245d]"><span className="brand-mark" aria-hidden="true"><i/><i/></span>AI招聘工作台</div><button type="button" aria-label="退出当前设备" onClick={async()=>{await apiFetch("/auth/logout",{method:"POST"}).catch(()=>{});setAccessToken(null);router.replace("/login");}} className="grid h-9 w-9 place-items-center rounded-full text-[#405781] hover:bg-white/70"><LogOut size={17}/></button></header><div className="grid min-h-[calc(100vh-66px)] place-items-center p-5"><div className="w-full max-w-xl rounded-2xl bg-white p-10 text-center shadow-xl"><Building2 className="mx-auto text-[#2f6bff]" size={42}/><h1 className="mt-5 text-2xl font-bold">企业申请审核中</h1><p className="mt-4 text-[#60799f]">您所提交的“{pending.displayName||pending.legalName}”企业信息正在审核中，预计需要1个工作日，请耐心等待。</p></div></div></main>;
  return <main className="login-canvas min-h-screen p-5 text-[#10285b] lg:p-10">
    <div className="mx-auto max-w-5xl rounded-[26px] border border-white/80 bg-white/95 p-7 shadow-[0_18px_60px_rgba(39,100,180,0.09)] sm:p-10">
      <div className="flex items-center gap-3 text-xl font-bold text-[#09245d]"><span className="brand-mark" aria-hidden="true"><i/><i/></span>AI招聘工作台</div>
      <h1 className="mb-0 mt-8 text-3xl font-bold">选择使用方式</h1>
      <p className="mt-2 text-sm text-[#60799f]">账号类型不会被永久锁定，之后仍可加入企业或工作空间。</p><Link href="/login" className="mt-3 inline-block text-sm text-[#176ce5] hover:underline">返回登录页</Link>
      <div className="mt-7 grid gap-3 md:grid-cols-2">
        <ModeButton active={mode === "personal"} onClick={() => setMode("personal")} icon={<UserRound/>} title="个人使用" desc="个人HR或SOHO猎头"/>
        <ModeButton active={mode === "company"} onClick={() => setMode("company")} icon={<Building2/>} title="认证企业" desc="创建企业或加入已有企业"/>
      </div>
      <section className="mt-6 rounded-2xl border border-[#d8e6f5] bg-[#f9fcff] p-6">
        {mode === "personal" && <PersonalForm/>}
        {mode === "company" && <CompanySection/>}
      </section>
    </div>
  </main>;
}

function CompanySection() {
  const [subMode, setSubMode] = useState<CompanySubMode>("create");
  return <div>
    <div className="mb-5 flex gap-3">
      <SubModeButton active={subMode === "create"} onClick={() => setSubMode("create")} title="创建企业" desc="提交企业认证，平台审核通过后创建企业"/>
      <SubModeButton active={subMode === "join"} onClick={() => setSubMode("join")} title="加入企业" desc="搜索已注册的企业并申请加入"/>
    </div>
    {subMode === "create" && <CompanyForm/>}
    {subMode === "join" && <JoinCompanyForm/>}
  </div>;
}

function SubModeButton({ active, onClick, title, desc }: {active:boolean; onClick:()=>void; title:string; desc:string}) {
  return <button type="button" onClick={onClick} className={`flex-1 rounded-xl border p-4 text-left transition ${active ? "border-[#2f6bff] bg-[#edf5ff] shadow-[0_0_0_3px_rgba(47,107,255,.08)]" : "border-[#d8e4f1] bg-white hover:border-[#a9c6ea]"}`}>
    <strong className="block text-sm">{title}</strong><small className="mt-1 block text-xs text-[#7187a8]">{desc}</small>
  </button>;
}

function JoinCompanyForm() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<CompanySearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [applyingId, setApplyingId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [messageError, setMessageError] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const doSearch = useCallback(async (q: string) => {
    if (!q.trim()) { setResults([]); setSearched(false); return; }
    setLoading(true); setSearched(true);
    try {
      const data = await apiFetch<CompanySearchResult[]>(`/companies/search?q=${encodeURIComponent(q.trim())}`);
      setResults(data);
    } catch { setResults([]); }
    setLoading(false);
  }, []);

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => doSearch(query), 300);
    return () => { if (timerRef.current) clearTimeout(timerRef.current); };
  }, [query, doSearch]);

  async function applyToCompany(companyId: string) {
    setApplyingId(companyId); setMessage(null); setMessageError(false);
    try {
      await apiFetch(`/companies/${companyId}/membership-applications`, {
        method: "POST", body: JSON.stringify({ evidence: "通过平台搜索申请加入" }),
      });
      setMessage("加入申请已提交，请等待该企业 Owner 或管理员审核。");
      setMessageError(false);
    } catch (error) {
      setMessageError(true);
      setMessage(error instanceof ApiError ? error.message : "申请失败，请稍后重试");
    }
    setApplyingId(null);
  }

  return <div className="space-y-4">
    <Heading title="加入已有企业" note="搜索已通过平台认证的企业并申请加入。"/>
    <label className="block text-sm font-medium">
      <span className="mb-2 block">搜索企业名称</span>
      <div className="relative">
        <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-[#9ab1d4]" />
        <input value={query} onChange={e => setQuery(e.target.value)} placeholder="输入企业名称或简称…" className="h-11 w-full rounded-lg border border-[#cddbea] bg-white pl-9 pr-3 outline-none focus:border-[#2f6bff]"/>
      </div>
    </label>

    {loading && <p className="text-sm text-[#7187a8]">搜索中…</p>}

    {searched && !loading && results.length === 0 && query.trim() && (
      <p className="text-sm text-[#7187a8]">未找到匹配的企业</p>
    )}

    {results.length > 0 && (
      <div className="space-y-2">
        {results.map(company => (
          <div key={company.id} className="flex items-center justify-between rounded-lg border border-[#d8e6f5] bg-white p-3">
            <div>
              <p className="text-sm font-medium text-[#10285b]">{company.displayName}</p>
              <p className="text-xs text-[#7187a8]">{company.legalName} · {company.memberCount} 名成员</p>
            </div>
            <button className="outline-button text-xs" disabled={applyingId === company.id} onClick={() => applyToCompany(company.id)} type="button">
              {applyingId === company.id ? "申请中…" : "申请加入"}
            </button>
          </div>
        ))}
      </div>
    )}

    {message && <p className={`flex items-start gap-2 rounded-lg px-3 py-2 text-sm ${messageError ? "bg-red-50 text-red-700" : "bg-emerald-50 text-emerald-700"}`}><CheckCircle2 className="mt-0.5 shrink-0" size={16}/>{message}</p>}
  </div>;
}

function ModeButton({ active, onClick, icon, title, desc }: {active:boolean; onClick:()=>void; icon:React.ReactNode; title:string; desc:string}) {
  return <button type="button" onClick={onClick} className={`rounded-2xl border p-5 text-left transition ${active ? "border-[#2f6bff] bg-[#edf5ff] shadow-[0_0_0_3px_rgba(47,107,255,.08)]" : "border-[#d8e4f1] bg-white hover:border-[#a9c6ea]"}`}>
    <span className="text-[#2671ed]">{icon}</span><strong className="mt-3 block">{title}</strong><small className="mt-1 block text-[#7187a8]">{desc}</small>
  </button>;
}

function PersonalForm() {
  const router = useRouter();
  const { refresh: refreshWorkspaces } = useWorkspace();
  const [name, setName] = useState("我的招聘空间");
  const [nickname, setNickname] = useState("");
  const [realName, setRealName] = useState("");
  const [identityNumber, setIdentityNumber] = useState("");
  const [created, setCreated] = useState(false);
  const status = useActionStatus();
  async function submit(event: FormEvent) {
    event.preventDefault(); status.start();
    try {
      if (!created) { await apiFetch("/workspaces/personal", {method:"POST", body:JSON.stringify({name})}); setCreated(true); }
      if (nickname.trim()) await apiFetch("/me/display-name", {method:"PUT", body:JSON.stringify({displayName:nickname.trim()})});
      if (realName && identityNumber) await apiFetch("/personal-verifications", {method:"POST", body:JSON.stringify({realName, identityNumber})});
      // 创建后立即刷新全局 Workspace 状态，再进入招聘页，避免展示“没有空间”的旧状态。
      await refreshWorkspaces();
      status.success(realName && identityNumber ? "实名认证已提交，平台审核通过后发放30元试用金。" : "个人空间已创建，可稍后在设置中实名认证。", () => router.push("/recruitment"));
    } catch (error) { status.fail(error); }
  }
  const [showVerify,setShowVerify]=useState(false);
  return <><form onSubmit={submit} className="space-y-4"><Heading title="创建个人工作空间" note="MVP期间每个用户只能创建一个个人 Workspace。"/>
    <Field label="空间名称" value={name} onChange={setName}/><Field label="昵称（选填，用于工作台展示）" value={nickname} onChange={setNickname} placeholder="例如：小张"/><button type="button" className="outline-button" onClick={()=>setShowVerify(true)}>实名认证</button>
    <StatusLine status={status}/><button className="primary-button" disabled={status.loading} type="submit">{status.loading ? "提交中…" : "创建并继续"}</button>
  </form>{showVerify&&<div className="fixed inset-0 z-50 grid place-items-center bg-[#071b4b]/40 p-5"><div role="dialog" aria-modal="true" className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl"><div className="flex justify-between"><h2 className="m-0 text-lg font-bold">实名认证信息</h2><button type="button" onClick={()=>setShowVerify(false)} aria-label="关闭"><X size={18}/></button></div><div className="mt-5 space-y-4"><Field label="真实姓名" value={realName} onChange={setRealName}/><Field label="身份证明号码" value={identityNumber} onChange={setIdentityNumber}/><button type="button" className="primary-button w-full" onClick={()=>setShowVerify(false)}>保存</button></div></div></div>}</>;
}

function CompanyForm() {
  const status = useActionStatus();
  const [form, setForm] = useState({legalName:"", displayName:"", creditCode:"", licenseReference:"", firstWorkspaceName:"招聘团队"});
  async function submit(event: FormEvent) {
    event.preventDefault(); status.start();
    try { const result = await apiFetch<{id:string}>("/company-verifications", {method:"POST", body:JSON.stringify(form)}); status.success(`企业认证已提交（申请号 ${result.id.slice(0,8)}），平台审核通过后创建企业和首个空间并发放100元试用金。`); }
    catch (error) { status.fail(error); }
  }
  const [fileName,setFileName]=useState("");
  return <form onSubmit={submit} className="space-y-4"><Heading title="企业认证" note="企业 Owner 仅在平台审核或认领通过后产生。"/>
    <div className="grid gap-4 sm:grid-cols-2"><Field label="企业法定名称" value={form.legalName} onChange={value=>setForm({...form,legalName:value})}/><Field label="企业简称" value={form.displayName} onChange={value=>setForm({...form,displayName:value})}/><Field label="统一社会信用代码" value={form.creditCode} onChange={value=>setForm({...form,creditCode:value})}/><Field label="首个 Workspace 名称" value={form.firstWorkspaceName} onChange={value=>setForm({...form,firstWorkspaceName:value})}/></div>
    <label className="block text-sm font-medium"><span className="mb-2 block">营业执照</span><input required type="file" accept="image/*,.pdf" onChange={e=>{const f=e.target.files?.[0];if(!f)return;if(f.type.startsWith("image/")||f.type==="application/pdf"){setFileName(f.name);setForm({...form,licenseReference:f.name});}}} className="block w-full rounded-lg border border-[#cddbea] bg-white p-2 text-sm"/><small className="mt-1 block text-xs text-[#7187a8]">支持 JPG、PNG、WEBP、PDF{fileName&&` · 已选择：${fileName}`}</small></label>
    <StatusLine status={status}/><button className="primary-button" disabled={status.loading} type="submit">提交平台审核</button>
  </form>;
}

function Heading({title,note}:{title:string;note:string}) { return <div><h2 className="m-0 text-lg font-bold">{title}</h2><p className="mb-0 mt-1 text-sm text-[#6b82a6]">{note}</p></div>; }
function Field({label,value,onChange,placeholder}:{label:string;value:string;onChange:(v:string)=>void;placeholder?:string}) { return <label className="block text-sm font-medium"><span className="mb-2 block">{label}</span><input required={!label.includes("可稍后")} value={value} placeholder={placeholder} onChange={e=>onChange(e.target.value)} className="h-11 w-full rounded-lg border border-[#cddbea] bg-white px-3 outline-none focus:border-[#2f6bff]"/></label>; }
function StatusLine({status}:{status:ReturnType<typeof useActionStatus>}) { return status.message ? <p className={`flex items-start gap-2 rounded-lg px-3 py-2 text-sm ${status.error ? "bg-red-50 text-red-700" : "bg-emerald-50 text-emerald-700"}`}><CheckCircle2 className="mt-0.5 shrink-0" size={16}/>{status.message}</p> : null; }
function useActionStatus() { const [loading,setLoading]=useState(false); const [message,setMessage]=useState<string|null>(null); const [error,setError]=useState(false); return {loading,message,error,start(){setLoading(true);setMessage(null);setError(false);},success(text:string,done?:()=>void){setLoading(false);setMessage(text);setError(false);if(done)setTimeout(done,900);},fail(reason:unknown){setLoading(false);setError(true);setMessage(reason instanceof ApiError?reason.message:"操作失败，请稍后重试");}}; }
