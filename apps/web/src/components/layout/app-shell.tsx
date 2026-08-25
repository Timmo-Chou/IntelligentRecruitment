import { Bell, Bot, BriefcaseBusiness, FileSearch, GitBranch, LayoutDashboard, Library, MessageSquareText, Settings, Sparkles, Users } from "lucide-react";
import Link from "next/link";
import React, { type ReactNode } from "react";
import { SessionSummary } from "./session-summary";

const navItems = [
  ["概览", LayoutDashboard, "/"], ["智能招聘", Sparkles, "/recruitment"], ["JD生成", GitBranch, "/recruitment"],
  ["简历筛选", FileSearch, "/recruitment"], ["AI面试出题", MessageSquareText, "/recruitment"], ["AI招聘工作流", Bot, "/recruitment"],
  ["职位库", BriefcaseBusiness, "/jobs"], ["人才库", Users, "/candidates"], ["面试题库", Library, "/interviews"], ["设置", Settings, "/settings"],
] as const;

export function AppShell({ children, activeItem = "概览" }: { children: ReactNode; activeItem?: string }) {
  return <div className="min-h-screen bg-[#f7fbff] text-[#10285b]">
    <header className="app-header flex h-[66px] items-center justify-between border-b border-[#dbe9f8] px-6 lg:px-8">
      <div className="flex items-center gap-3 text-[21px] font-bold tracking-tight text-[#09245d]"><span className="brand-mark" aria-hidden="true"><i/><i/></span>AI招聘工作台</div>
      <div className="flex items-center gap-2">
        <button className="relative grid h-9 w-9 place-items-center rounded-full hover:bg-white/70" type="button" aria-label="通知"><Bell size={19}/><span className="absolute right-1 top-1 h-2 w-2 rounded-full bg-red-500 ring-2 ring-white"/></button>
        <SessionSummary/>
      </div>
    </header>
    <div className="grid min-h-[calc(100vh-66px)] grid-cols-1 lg:grid-cols-[210px_minmax(0,1fr)]">
      <aside className="hidden border-r border-[#dbe9f8] bg-white px-3 py-4 lg:flex lg:flex-col">
        <nav className="space-y-1" aria-label="主导航">{navItems.map(([label, Icon, href], index) => <Link key={label} href={href} className={`flex w-full items-center gap-3 rounded-xl px-4 py-3 text-left text-[15px] transition ${index === 9 ? "mt-5" : ""} ${activeItem === label ? "bg-[#ddf8ef] font-semibold text-[#07945f]" : "text-[#27477f] hover:bg-[#f3f8fe]"}`}><Icon aria-hidden="true" size={18} strokeWidth={1.9}/>{label}</Link>)}</nav>
        <div className="mt-auto overflow-hidden rounded-xl bg-gradient-to-br from-[#d8fff6] to-[#d7efff] p-4"><p className="m-0 text-sm font-bold text-[#087aa4]">AI助力招聘</p><p className="mb-0 mt-2 text-xs font-medium text-[#2788a8]">更高效 · 更精准</p><Bot className="ml-auto -mt-3 text-[#236ee8]" size={42}/></div>
      </aside>
      <main className="min-w-0 p-4 sm:p-5 xl:p-6">{children}</main>
    </div>
  </div>;
}
