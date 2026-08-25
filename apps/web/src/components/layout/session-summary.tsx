"use client";

import { ChevronDown, LogOut, UserRound } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { apiFetch, ApiError, setAccessToken } from "@/lib/api-client";

type Me = { maskedPhone:string; displayName?:string };
type Workspace = { id:string; name:string; hasDataAccess:boolean };

export function SessionSummary() {
  const [me,setMe]=useState<Me|null>(null); const [workspace,setWorkspace]=useState<Workspace|null>(null); const [balance,setBalance]=useState<number|null>(null);
  useEffect(()=>{ let active=true; (async()=>{ try { const [user,spaces]=await Promise.all([apiFetch<Me>("/me"),apiFetch<Workspace[]>("/workspaces")]); if(!active)return; setMe(user); const selected=spaces.find(item=>item.hasDataAccess)??null; setWorkspace(selected); if(selected){const bill=await apiFetch<{availableAmountMinor:number}>(`/workspaces/${selected.id}/billing`);if(active)setBalance(bill.availableAmountMinor);} } catch (error) { if(active && error instanceof ApiError && error.status===401) window.location.replace("/login"); } })(); return()=>{active=false};},[]);
  async function logout(){try{await apiFetch("/auth/logout",{method:"POST"});}finally{setAccessToken(null);window.location.replace("/login");}}
  return <div className="flex items-center gap-3 text-xs text-[#405781]">
    <Link href="/billing" className="top-pill flex font-semibold text-[#07945f]">{balance===null?"额度 --":`¥${(balance/100).toFixed(2)}`} <ChevronDown size={14}/></Link>
    <span className="top-pill hidden sm:flex">{workspace?.name??"尚未选择工作空间"}</span>
    <div className="hidden items-center gap-2 border-l border-[#d9e6f3] pl-3 md:flex"><span className="grid h-9 w-9 place-items-center rounded-full bg-gradient-to-br from-[#dff7f4] to-[#b7d5ff] text-[#0d57aa]"><UserRound size={18}/></span><span className="leading-tight"><strong className="block text-[#10285b]">{me?.displayName??"招聘用户"}</strong><small>{me?.maskedPhone??"未登录"}</small></span></div>
    <button type="button" aria-label="退出当前设备" onClick={logout} className="grid h-9 w-9 place-items-center rounded-full hover:bg-white/70"><LogOut size={17}/></button>
  </div>;
}
