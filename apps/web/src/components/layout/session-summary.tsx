"use client";

import { Check, ChevronDown, ChevronRight, LogOut, UserRound } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { apiFetch, ApiError, setAccessToken } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type Me = { userId: string; displayName?: string; maskedPhone?: string };
type Company = { companyId: string; legalName: string; companyStatus: string; tenantStatus: string };

/** Account flyout: the primary menu stays on the right while its account submenu opens to the left. */
export function SessionSummary() {
  const [me, setMe] = useState<Me | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const [companyName, setCompanyName] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [switchOpen, setSwitchOpen] = useState(false);
  const { workspace, workspaces, selectWorkspace } = useWorkspace();

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const [user, companies] = await Promise.all([apiFetch<Me>("/me"), apiFetch<Company[]>("/companies")]);
        if (!active) return;
        setMe(user); setCompanyName(companies[0]?.legalName ?? null);
        const selected = companies.find(item => item.companyStatus === "ACTIVE" && item.tenantStatus === "ACTIVE") ?? null;
        if (selected) {
          const bill = await apiFetch<{ balance_minor: number }>(`/companies/${selected.companyId}/billing`);
          if (active) setBalance(bill.balance_minor);
        }
      } catch (error) {
        if (active && error instanceof ApiError && error.status === 401) window.location.replace("/login");
      }
    })();
    return () => { active = false; };
  }, []);

  async function logout() {
    try { await apiFetch("/auth/logout", { method: "POST" }); }
    finally { setAccessToken(null); window.location.replace("/login"); }
  }

  const displayName = workspace?.type === "PERSONAL" ? "个人账号" : workspace?.name ?? companyName ?? me?.displayName ?? "招聘用户";
  return <div className="flex items-center gap-3 text-xs text-[#405781]">
    <Link href="/billing" className="top-pill flex font-semibold text-[#07945f]">{balance === null ? "额度 --" : `¥${(balance / 100).toFixed(2)}`} <ChevronDown size={14}/></Link>
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
        </div>}
      </div>}
    </div>
    <button type="button" aria-label="退出当前设备" onClick={logout} className="grid h-9 w-9 place-items-center rounded-full hover:bg-white/70"><LogOut size={17}/></button>
  </div>;
}
