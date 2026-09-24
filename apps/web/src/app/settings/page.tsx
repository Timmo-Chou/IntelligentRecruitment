"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { apiFetch, ApiError } from "@/lib/api-client";
import { useTenant } from "@/lib/tenant-context";

/** /me 响应：后端字段为 id（驼峰由 IR MeResponse 序列化） */
type Me = { id: string; displayName?: string; maskedPhone?: string };
/** 企业注册申请（BOSS 蛇形命名透传）：status: PENDING_REVIEW/APPROVED/REJECTED */
type Registration = { registration_id: string; tenant_id?: string | null; legal_name: string; credit_code: string; status: string; reject_reason?: string | null; submitted_at?: string };

/** 注册申请状态中文文案 */
const registrationStatusText: Record<string, string> = { PENDING_REVIEW: "待审核", APPROVED: "已通过", REJECTED: "已驳回" };
/** 租户状态中文文案 */
const tenantStatusText: Record<string, string> = { ACTIVE: "正常", TRIAL_EXPIRED_NO_CONTRACT: "试用到期", FROZEN: "已冻结", CLOSED: "已关闭" };

export default function SettingsPage() {
  // 当前激活的 TAB：profile=个人设置，enterprises=所属企业
  const [tab, setTab] = useState<"profile" | "enterprises">("profile");
  return <AppShell activeItem="" pageHeader={<div><h1 className="m-0 text-xl font-bold">个人设置</h1><p className="mb-0 mt-1 text-sm text-[#60799f]">账号资料属于用户本人；企业成员、席位、权限和账单在企业管理中处理。</p></div>}>
    <div className="grid max-w-3xl gap-4">
      {/* TAB 切换 */}
      <div className="flex gap-1 rounded-xl border border-[#d8e6f5] bg-white p-1" role="tablist">
        <button type="button" role="tab" aria-selected={tab === "profile"} onClick={() => setTab("profile")} className={`flex-1 rounded-lg px-4 py-2.5 text-sm font-medium transition ${tab === "profile" ? "bg-[#ddf8ef] font-semibold text-[#07945f]" : "text-[#27477f] hover:bg-[#f3f8fe]"}`}>个人设置</button>
        <button type="button" role="tab" aria-selected={tab === "enterprises"} onClick={() => setTab("enterprises")} className={`flex-1 rounded-lg px-4 py-2.5 text-sm font-medium transition ${tab === "enterprises" ? "bg-[#ddf8ef] font-semibold text-[#07945f]" : "text-[#27477f] hover:bg-[#f3f8fe]"}`}>所属企业</button>
      </div>
      {tab === "profile" ? <ProfilePanel/> : <EnterprisePanel/>}
    </div>
  </AppShell>;
}

/** 个人设置 TAB：账号资料表单（保存走 PUT /me/display-name） */
function ProfilePanel() {
  const [me, setMe] = useState<Me | null>(null); const [displayName, setDisplayName] = useState(""); const [message, setMessage] = useState(""); const [busy, setBusy] = useState(false);
  useEffect(() => { void apiFetch<Me>("/me").then(item => { setMe(item); setDisplayName(item.displayName ?? ""); }).catch(() => undefined); }, []);
  async function save(event: FormEvent) {
    event.preventDefault(); setBusy(true); setMessage("");
    try {
      // 接口路径为 /me/display-name（原 /me 路径不匹配导致"服务暂时不可用"提示）
      const updated = await apiFetch<Me>("/me/display-name", { method: "PUT", body: JSON.stringify({ displayName }) });
      setMe(updated); setMessage("个人资料已保存。");
    } catch (cause) { setMessage(cause instanceof ApiError ? cause.message : "保存失败"); }
    finally { setBusy(false); }
  }
  return <section className="rounded-2xl border border-[#d8e6f5] bg-white p-6"><h2 className="m-0 text-base font-bold">账号资料</h2><form className="mt-4 space-y-4" onSubmit={save}><label className="block text-sm font-medium">手机号<input disabled value={me?.maskedPhone ?? ""} className="mt-2 h-10 w-full rounded-lg border border-[#d8e6f5] bg-[#f7fbff] px-3"/></label><label className="block text-sm font-medium">显示名称<input value={displayName} maxLength={80} onChange={event => setDisplayName(event.target.value)} className="mt-2 h-10 w-full rounded-lg border border-[#cddbea] px-3"/></label>{message && <p className="rounded-lg bg-[#f4f8fc] px-3 py-2 text-sm text-[#526e96]">{message}</p>}<button className="primary-button" disabled={busy} type="submit">{busy ? "保存中…" : "保存"}</button></form></section>;
}

/** 所属企业 TAB：待审核申请 + 已注册企业 + 已加入企业 列表 */
function EnterprisePanel() {
  const { tenant, tenants } = useTenant();
  const [registration, setRegistration] = useState<Registration | null>(null);
  useEffect(() => {
    // 查询最近一次企业注册申请（无申请记录时后端返回空对象）
    void apiFetch<Registration>("/tenants/enterprise-registrations/me")
      .then(item => setRegistration(item && item.legal_name ? item : null))
      .catch(() => setRegistration(null));
  }, []);
  // 已注册企业：本人是 Owner；已加入企业：本人非 Owner 的企业租户
  const owned = tenants.filter(item => item.type === "ENTERPRISE" && item.owner);
  const joined = tenants.filter(item => item.type === "ENTERPRISE" && !item.owner);
  return <section className="rounded-2xl border border-[#d8e6f5] bg-white p-6">
    <h2 className="m-0 text-base font-bold">所属企业</h2>
    <p className="mt-2 text-sm text-[#60799f]">一个用户可加入多个企业租户；各企业数据、权限、套餐和积分互相隔离。</p>
    <div className="mt-4 space-y-5">
      {/* 待审核注册申请 */}
      {registration && <div>
        <h3 className="m-0 text-sm font-semibold text-[#27477f]">已提交注册待审核</h3>
        <div className="mt-2 flex items-center justify-between rounded-lg border border-[#d8e6f5] bg-[#f9fcff] p-3">
          <div className="min-w-0"><strong className="block truncate text-sm">{registration.legal_name}</strong><small className="text-[#7187a8]">{registration.credit_code}</small>{registration.status === "REJECTED" && registration.reject_reason && <p className="m-0 mt-1 text-xs text-[#c53c3c]">驳回原因：{registration.reject_reason}</p>}</div>
          <span className={`ml-3 shrink-0 rounded-full px-2.5 py-1 text-xs ${registration.status === "PENDING_REVIEW" ? "bg-[#fff7e6] text-[#b06e00]" : registration.status === "REJECTED" ? "bg-[#fff1f1] text-[#c53c3c]" : "bg-[#eefaf4] text-[#0a7d55]"}`}>{registrationStatusText[registration.status] ?? registration.status}</span>
        </div>
      </div>}
      {/* 已注册企业（本人为 Owner） */}
      <div>
        <h3 className="m-0 text-sm font-semibold text-[#27477f]">已注册企业</h3>
        {owned.length
          ? <div className="mt-2 space-y-2">{owned.map(item => <div key={item.id} className="flex items-center justify-between rounded-lg border border-[#d8e6f5] p-3"><div className="min-w-0"><strong className="block truncate text-sm">{item.name}</strong><small className="text-[#7187a8]">角色：Owner</small></div><span className="ml-3 shrink-0 rounded-full bg-[#eef2fb] px-2.5 py-1 text-xs text-[#33518f]">{tenantStatusText[item.status] ?? item.status}</span></div>)}</div>
          : <p className="mt-2 text-sm text-[#7187a8]">暂无已注册的企业</p>}
      </div>
      {/* 已加入企业（非 Owner） */}
      <div>
        <h3 className="m-0 text-sm font-semibold text-[#27477f]">已加入企业</h3>
        {joined.length
          ? <div className="mt-2 space-y-2">{joined.map(item => <div key={item.id} className="flex items-center justify-between rounded-lg border border-[#d8e6f5] p-3"><div className="min-w-0"><strong className="block truncate text-sm">{item.name}</strong><small className="text-[#7187a8]">角色：{item.roleCode ?? "--"}</small></div><span className="ml-3 shrink-0 rounded-full bg-[#eef2fb] px-2.5 py-1 text-xs text-[#33518f]">{tenantStatusText[item.status] ?? item.status}</span></div>)}</div>
          : <p className="mt-2 text-sm text-[#7187a8]">暂无已加入的企业</p>}
      </div>
    </div>
    <div className="mt-5">
      <Link href="/onboarding" className="outline-button">注册或申请加入企业</Link>
      {tenant?.type === "ENTERPRISE" && tenant.owner && <Link href="/enterprise-management" className="ml-2 text-sm text-[#2467ca]">进入当前企业管理</Link>}
    </div>
  </section>;
}
