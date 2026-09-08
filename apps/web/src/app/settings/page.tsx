"use client";

import { Building2, CheckCircle2, MailPlus, Search, UserRound, UsersRound } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError, apiFetch } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type Me = { userId: string; displayName: string; maskedPhone: string; status: string };
type Company = { companyId: string; tenantId: string; legalName: string; companyStatus: string; tenantStatus: string; companyOwner: boolean };
type Member = { userId: string; displayName: string; maskedPhone: string; status: string; joinedAt: string };
type Application = { applicationId: string; applicantUserId: string; displayName: string; maskedPhone: string; status: string; createdAt: string };
type Invitation = { invitationId: string; maskedPhone: string; expiresAt: string; acceptedAt: string | null; revokedAt: string | null; createdAt: string };

export default function SettingsPage() {
  const { workspaces, workspace } = useWorkspace();
  const [me, setMe] = useState<Me | null>(null); const [companies, setCompanies] = useState<Company[]>([]); const [error, setError] = useState("");
  const [name, setName] = useState(""); const [saving, setSaving] = useState(false); const [message, setMessage] = useState("");
  async function load() { try { const [user, companyItems] = await Promise.all([apiFetch<Me>("/me"), apiFetch<Company[]>("/companies")]); setMe(user); setName(user.displayName ?? ""); setCompanies(companyItems); setError(""); } catch (cause) { setError(cause instanceof ApiError ? cause.message : "设置加载失败"); } }
  useEffect(() => { void load(); }, []);
  async function save(event: FormEvent) { event.preventDefault(); setSaving(true); setMessage(""); try { await apiFetch("/me/display-name", { method: "PUT", body: JSON.stringify({ displayName: name.trim() }) }); await load(); setMessage("昵称已保存"); } catch (cause) { setMessage(cause instanceof ApiError ? cause.message : "昵称保存失败"); } finally { setSaving(false); } }
  const selectedCompanyId = workspace?.type === "COMPANY" ? workspace.companyId : companies[0]?.companyId;
  return <AppShell activeItem="设置" pageHeader={<header><h1 className="m-0 text-[25px] font-bold text-[#09245d]">账号与组织设置</h1><p className="mt-1 text-sm text-[#55709d]">账号、企业、组织单元与成员权限均由 BOSS 统一管理。</p></header>}>
    {error && <Notice error message={error}/>} {message && <Notice message={message}/>}<section className="mt-4 grid gap-4 xl:grid-cols-[360px_minmax(0,1fr)]"><aside className="space-y-4"><Card><div className="flex items-center gap-3"><span className="grid h-11 w-11 place-items-center rounded-xl bg-[#eaf7ff] text-[#2f6bff]"><UserRound size={20}/></span><div><p className="m-0 text-xs text-[#7187a8]">当前登录账号</p><p className="m-0 text-base font-semibold text-[#09245d]">{me?.maskedPhone ?? "加载中…"}</p></div></div><form onSubmit={save} className="mt-5 border-t border-[#e0eaf5] pt-4"><label className="block text-sm font-medium">昵称<input value={name} maxLength={80} onChange={event => setName(event.target.value)} placeholder="请输入昵称" className="mt-2 h-10 w-full rounded-lg border border-[#cbdbea] px-3 text-sm"/></label><button disabled={saving} className="primary-button mt-3 !h-9" type="submit">{saving ? "保存中…" : "保存昵称"}</button></form></Card><CompanyCard companies={companies}/></aside><section className="space-y-4"><Card><div className="mb-3 flex items-center gap-2"><UsersRound size={17} className="text-[#2f6bff]"/><h2 className="m-0 text-base">当前业务主体</h2></div><div className="grid gap-3 sm:grid-cols-2">{workspaces.map(item => <div key={item.id} className="rounded-xl border border-[#dce7f3] p-4"><span className="grid h-10 w-10 place-items-center rounded-xl bg-[#eaf7ff] text-[#2471dc]">{item.type === "PERSONAL" ? <UserRound size={20}/> : <Building2 size={20}/>}</span><h3 className="mb-1 mt-3 text-base font-semibold">{item.type === "PERSONAL" ? "个人账号" : item.name}</h3><p className="m-0 text-xs text-[#7187a8]">{item.type === "PERSONAL" ? "Personal Tenant" : "企业 Company"} · {item.status}</p></div>)}</div></Card>{selectedCompanyId ? <TeamGovernance companyId={selectedCompanyId}/> : <Card><p className="m-0 text-sm text-[#7187a8]">加入或创建认证企业后，可在此管理企业成员。</p></Card>}</section></section>
  </AppShell>;
}

function CompanyCard({ companies }: { companies: Company[] }) { return <Card><Building2 className="text-[#16a477]"/><h2 className="mb-3 mt-3 text-base">所属企业</h2>{!companies.length ? <p className="m-0 text-sm text-[#7187a8]">尚未加入企业。可在开户引导中注册企业或申请加入已有企业。</p> : <div className="space-y-3">{companies.map(company => <div key={company.companyId} className="rounded-lg border border-[#dce7f3] p-3"><div className="flex items-center justify-between gap-2"><strong className="text-sm">{company.legalName}</strong>{company.companyOwner && <span className="rounded-md bg-[#edf5ff] px-2 py-1 text-xs text-[#2467ca]">Owner</span>}</div><p className="m-0 mt-2 text-xs text-[#7187a8]">Company：{company.companyStatus} · Tenant：{company.tenantStatus}</p></div>)}</div>}</Card>; }

function TeamGovernance({ companyId }: { companyId: string }) {
  const [members, setMembers] = useState<Member[]>([]); const [applications, setApplications] = useState<Application[]>([]); const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [message, setMessage] = useState(""); const [phone, setPhone] = useState(""); const [loading, setLoading] = useState(false); const [canManage, setCanManage] = useState(false);
  async function load() {
    setLoading(true);
    try {
      const permissions = await apiFetch<string[]>(`/companies/${companyId}/permissions`);
      const mayManage = permissions.includes("company.member.manage"); setCanManage(mayManage);
      const [memberItems, applicationItems, invitationItems] = await Promise.all([
        apiFetch<Member[]>(`/companies/${companyId}/governance/members`),
        mayManage ? apiFetch<Application[]>(`/companies/${companyId}/governance/membership-applications`) : Promise.resolve([]),
        mayManage ? apiFetch<Invitation[]>(`/companies/${companyId}/governance/invitations`) : Promise.resolve([]),
      ]);
      setMembers(memberItems); setApplications(applicationItems); setInvitations(invitationItems);
    } catch (cause) { setMessage(cause instanceof ApiError ? cause.message : "成员数据加载失败"); } finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, [companyId]);
  async function invite(event: FormEvent) { event.preventDefault(); try { await apiFetch(`/companies/${companyId}/invitations`, { method: "POST", body: JSON.stringify({ phone }) }); setPhone(""); setMessage("邀请已创建，受邀人使用该手机号登录后可接受邀请。"); await load(); } catch (cause) { setMessage(cause instanceof ApiError ? cause.message : "邀请创建失败"); } }
  async function decide(applicationId: string, approve: boolean) { try { await apiFetch(`/companies/membership-applications/${applicationId}/decision`, { method: "POST", body: JSON.stringify({ approve, reason: approve ? null : "不符合企业成员要求" }) }); setMessage(approve ? "已批准加入申请" : "已拒绝加入申请"); await load(); } catch (cause) { setMessage(cause instanceof ApiError ? cause.message : "审核失败"); } }
  const activeMembers = useMemo(() => members.filter(member => member.status !== "REMOVED"), [members]);
  return <>{message && <Notice message={message}/>}<Card><div className="flex items-center justify-between gap-2"><div className="flex items-center gap-2"><UsersRound size={17} className="text-[#2f6bff]"/><h2 className="m-0 text-base">企业成员</h2></div><span className="text-xs text-[#7187a8]">{loading ? "加载中…" : `${activeMembers.length} 名成员`}</span></div><div className="mt-4 space-y-2">{activeMembers.map(member => <div key={member.userId} className="flex items-center justify-between rounded-lg border border-[#dce7f3] p-3"><span><strong className="block text-sm">{member.displayName || "未设置昵称"}</strong><small className="text-[#7187a8]">{member.maskedPhone} · {member.status}</small></span></div>)}{!loading && !activeMembers.length && <p className="text-sm text-[#7187a8]">暂无成员</p>}</div></Card>{canManage && <><form onSubmit={invite} className="rounded-xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 flex items-center gap-2 text-base"><MailPlus size={17}/>邀请成员</h2><p className="mt-1 text-xs text-[#7187a8]">BOSS 仅保存手机号哈希及尾号，邀请仅可由目标手机号接受。</p><div className="mt-4 flex gap-3"><input required pattern="1\\d{10}" value={phone} onChange={event => setPhone(event.target.value)} placeholder="成员手机号" className="h-10 min-w-0 flex-1 rounded-lg border border-[#cbdbea] px-3 text-sm"/><button className="primary-button !h-10" type="submit">创建邀请</button></div></form><Card><h2 className="m-0 text-base">加入企业申请</h2><div className="mt-3 space-y-2">{applications.map(application => <div key={application.applicationId} className="flex items-center justify-between rounded-lg border border-[#dce7f3] p-3"><span className="text-sm">{application.displayName || "未设置昵称"} · {application.maskedPhone}</span><span className="flex gap-2"><button type="button" onClick={() => void decide(application.applicationId, true)} className="primary-button !h-8 !px-3">批准</button><button type="button" onClick={() => void decide(application.applicationId, false)} className="outline-button !h-8 !px-3">拒绝</button></span></div>)}{!applications.length && <p className="text-sm text-[#7187a8]">暂无待审核申请</p>}</div></Card><Card><h2 className="m-0 text-base">已创建邀请</h2><div className="mt-3 space-y-2">{invitations.map(invitation => <div key={invitation.invitationId} className="rounded-lg border border-[#dce7f3] p-3 text-sm"><strong>{invitation.maskedPhone}</strong><p className="m-0 mt-1 text-xs text-[#7187a8]">{invitation.acceptedAt ? "已接受" : invitation.revokedAt ? "已撤销" : `待接受 · 截止 ${format(invitation.expiresAt)}`}</p></div>)}{!invitations.length && <p className="text-sm text-[#7187a8]">暂无邀请</p>}</div></Card></>}</>;
}
function Card({ children }: { children: React.ReactNode }) { return <article className="rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">{children}</article>; }
function Notice({ message, error = false }: { message: string; error?: boolean }) { return <p className={`mb-4 rounded-lg p-3 text-sm ${error ? "bg-red-50 text-red-700" : "bg-emerald-50 text-emerald-700"}`}><CheckCircle2 className="mr-2 inline" size={16}/>{message}</p>; }
function format(value: string) { return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)); }
