"use client";

import { Check, ChevronDown, ChevronRight, LogOut, UserRound } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { apiFetch, ApiError, setAccessToken } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type Me = { userId: string; displayName?: string; maskedPhone?: string };
type Billing = { availableCredits?: number; subscription?: { kind?: string; endsAt?: string } | null };

/** Account flyout: the primary menu stays on the right while its account submenu opens to the left. */
export function SessionSummary() {
  const [me, setMe] = useState<Me | null>(null);
  const [billing, setBilling] = useState<Billing | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [switchOpen, setSwitchOpen] = useState(false);
  const { workspace, workspaces, selectWorkspace } = useWorkspace();

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const user = await apiFetch<Me>("/me");
        if (!active) return;
        setMe(user);
        if (!workspace) return;
        if (workspace.owner) {
          const bill = await apiFetch<Billing>(`/tenants/${workspace.id}/billing`);
          if (active) setBilling(bill);
        } else if (active) setBilling(null);
      } catch (error) {
        if (active && error instanceof ApiError && error.status === 401) window.location.replace("/login");
      }
    })();
    return () => { active = false; };
  }, [workspace]);

  async function logout() {
    try { await apiFetch("/auth/logout", { method: "POST" }); }
    finally { setAccessToken(null); window.location.replace("/login"); }
  }

  const displayName = workspace?.type === "PERSONAL" ? "个人账号" : workspace?.name ?? me?.displayName ?? "招聘用户";
  return <div className="flex items-center gap-3 text-xs text-[#405781]">
    <details className="relative"><summary className="top-pill flex cursor-pointer list-none font-semibold text-[#07945f]">{billing === null ? "积分 --" : `${billing.availableCredits ?? 0} 积分`} <ChevronDown size={14}/></summary><div className="absolute right-0 z-50 mt-2 w-52 rounded-xl border border-[#d8dee8] bg-white p-3 text-xs shadow-[0_16px_36px_rgba(28,47,76,0.18)]"><p className="m-0 flex justify-between"><span>可用积分</span><strong>{billing?.availableCredits ?? "--"}</strong></p><p className="mb-0 mt-3 border-t border-[#e4edf7] pt-2 text-[#7187a8]">套餐：{billing?.subscription?.kind ?? "未开通"}</p>{workspace?.type === "ENTERPRISE" && workspace.owner ? <Link href="/enterprise-management?tab=billing" className="mt-2 block text-[#2467ca]">查看企业套餐与账单</Link> : workspace?.type === "PERSONAL" && <Link href="/billing" className="mt-2 block text-[#2467ca]">查看个人套餐与积分包</Link>}</div></details>
    <div className="relative hidden border-l border-[#d9e6f3] pl-3 md:block">
      <button type="button" aria-expanded={menuOpen} aria-haspopup="menu" onClick={() => { setMenuOpen(value => !value); setSwitchOpen(false); }} className="flex items-center gap-2 text-left">
        <span className="grid h-9 w-9 place-items-center rounded-full bg-gradient-to-br from-[#dff7f4] to-[#b7d5ff] text-[#0d57aa]"><UserRound size={18}/></span>
        <span className="leading-tight"><strong className="block max-w-36 truncate text-[#10285b]">{displayName}</strong><small className="block text-[#7187a8]">{me?.maskedPhone ?? ""}</small></span><ChevronDown size={14}/>
      </button>
      {menuOpen && <div onMouseLeave={() => setSwitchOpen(false)} className="absolute right-0 z-50 mt-2 w-72 rounded-xl border border-[#d8dee8] bg-white p-2 shadow-[0_16px_36px_rgba(28,47,76,0.18)]" role="menu">
        <div onMouseEnter={() => setSwitchOpen(true)} className={`flex h-12 w-full items-center justify-between rounded-lg px-4 text-xs font-medium text-[#20242c] transition-colors ${switchOpen ? "bg-[#f0f1f3]" : "hover:bg-[#f0f1f3]"}`}>
          <span>切换账号</span><ChevronRight size={20} className="text-[#9099a6]"/>
        </div>
        {switchOpen && <div onMouseLeave={() => setSwitchOpen(false)} className="absolute right-[calc(100%+6px)] top-0 w-72 rounded-xl border border-[#d8dee8] bg-white p-2 shadow-[0_16px_36px_rgba(28,47,76,0.18)]" role="menu" aria-label="账号切换">
          <p className="px-3 pb-2 pt-1 text-xs text-[#7187a8]">已登录账号</p>
          <div className="space-y-1">{workspaces.map(item => <button key={item.id} type="button" onClick={() => { selectWorkspace(item.id); setMenuOpen(false); setSwitchOpen(false); }} className={`flex w-full items-center justify-between rounded-lg px-3 py-3 text-left text-xs transition-colors ${workspace?.id === item.id ? "bg-[#f7faff]" : "hover:bg-[#f6f8fb]"}`}>
            <span className="min-w-0 truncate font-medium text-[#20242c]">{item.type === "PERSONAL" ? "个人账号" : item.name}</span>
            {workspace?.id === item.id && <Check size={19} className="ml-3 shrink-0 text-[#2f75ff]"/>}
          </button>)}</div>
          <Link href="/onboarding" onClick={() => { setMenuOpen(false); setSwitchOpen(false); }} className="mt-2 block rounded-lg px-3 py-3 text-xs font-medium text-[#2467ca] hover:bg-[#f6f8fb]">注册或申请加入企业</Link>
        </div>}
      </div>}
    </div>
    <button type="button" aria-label="退出当前设备" onClick={logout} className="grid h-9 w-9 place-items-center rounded-full hover:bg-white/70"><LogOut size={17}/></button>
  </div>;
}
