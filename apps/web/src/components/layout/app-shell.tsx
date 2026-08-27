"use client";

import { Bell, Bot, BriefcaseBusiness, Filter, LayoutDashboard, Library, Plus, Settings, Sparkles, Users } from "lucide-react";
import Link from "next/link";
import React, { type ReactNode, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { apiFetch } from "@/lib/api-client";
import { SessionSummary } from "./session-summary";
import { WorkspaceSwitcher } from "./workspace-switcher";
import { useWorkspace } from "@/lib/workspace-context";
import { fetchTasks, type TaskSummary } from "@/lib/recruitment-api";

const navItems = [
  ["概览", LayoutDashboard, "/"], ["智能招聘", Sparkles, "/recruitment"],
  ["职位库", BriefcaseBusiness, "/jobs"], ["人才库", Users, "/candidates"], ["简历筛选", Filter, "/screening"], ["面试题库", Library, "/interviews"], ["设置", Settings, "/settings"],
] as const;

function NotificationBell() {
  const [unread, setUnread] = useState(0);
  useEffect(() => {
    apiFetch<{ unreadCount: number }>("/notifications")
      .then((data) => setUnread(data.unreadCount))
      .catch(() => undefined);
  }, []);
  return <Link className="relative grid h-9 w-9 place-items-center rounded-full hover:bg-white/70" href="/notifications" aria-label={unread ? `通知，${unread}条未读` : "通知"}>
    <Bell size={19}/>
    {unread > 0 && <span className="absolute -right-1 -top-1 min-w-4 rounded-full bg-red-500 px-1 text-center text-[10px] leading-4 text-white ring-2 ring-white">{unread > 99 ? "99+" : unread}</span>}
  </Link>;
}

function RecruitmentTaskMenu({ visible }: { visible: boolean }) {
  const { workspaceId } = useWorkspace();
  const searchParams = useSearchParams();
  const selectedTaskId = searchParams.get("task");
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  useEffect(() => {
    if (!visible || !workspaceId) return;
    const load = () => { void fetchTasks(workspaceId).then(setTasks).catch(() => setTasks([])); };
    load();
    window.addEventListener("recruitment-tasks-changed", load);
    return () => window.removeEventListener("recruitment-tasks-changed", load);
  }, [visible, workspaceId]);
  if (!visible) return null;
  return <section className="mb-3 ml-3 border-l border-[#d9e6f3] pl-3" aria-label="智能招聘任务">
    <p className="mb-2 mt-3 text-xs font-semibold text-[#6e82a0]">任务历史</p>
    {tasks.length ? <div className="space-y-1">{tasks.slice(0, 8).map(task => <Link key={task.id} href={`/recruitment?task=${task.id}`} className={`block truncate rounded-lg px-2 py-2 text-xs transition ${selectedTaskId === task.id ? "bg-[#ddf8ef] font-semibold text-[#07945f]" : "text-[#526e96] hover:bg-[#eef6ff]"}`} title={task.title}>{task.title}</Link>)}</div> : <Link href="/recruitment" className="flex items-center gap-2 rounded-lg px-2 py-2 text-sm text-[#60789d] hover:bg-[#eef6ff]"><Plus size={16}/>开始你的第一个任务</Link>}
  </section>;
}

export function AppShell({ children, activeItem = "概览" }: { children: ReactNode; activeItem?: string }) {
  const router=useRouter(); const pathname=usePathname();
  useEffect(()=>{if(pathname==="/onboarding"||pathname==="/login")return; apiFetch<unknown[]>("/workspaces").then(items=>{if(!items.length)router.replace("/onboarding");}).catch(()=>{});},[pathname,router]);
  return <div className="min-h-screen bg-[#f7fbff] text-[#10285b]">
    <header className="app-header flex h-[66px] items-center justify-between border-b border-[#dbe9f8] px-6 lg:px-8">
      <div className="flex items-center gap-3 text-[21px] font-bold tracking-tight text-[#09245d]"><span className="brand-mark" aria-hidden="true"><i/><i/></span>AI招聘工作台</div>
      <div className="flex items-center gap-2">
        <NotificationBell />
        <WorkspaceSwitcher />
        <SessionSummary/>
      </div>
    </header>
    <div className="grid min-h-[calc(100vh-66px)] grid-cols-1 lg:grid-cols-[210px_minmax(0,1fr)]">
      <aside className="hidden border-r border-[#dbe9f8] bg-white px-3 py-4 lg:flex lg:flex-col">
        <nav className="space-y-1" aria-label="主导航">{navItems.map(([label, Icon, href]) => <React.Fragment key={label}><Link href={href} className={`flex w-full items-center gap-3 rounded-xl px-4 py-3 text-left text-[15px] transition ${label === "设置" ? "mt-5" : ""} ${activeItem === label ? "bg-[#ddf8ef] font-semibold text-[#07945f]" : "text-[#27477f] hover:bg-[#f3f8fe]"}`}><Icon aria-hidden="true" size={18} strokeWidth={1.9}/>{label}</Link>{label === "智能招聘" && <RecruitmentTaskMenu visible={pathname.startsWith("/recruitment")}/>}</React.Fragment>)}</nav>
        <div className="mt-auto overflow-hidden rounded-xl bg-gradient-to-br from-[#d8fff6] to-[#d7efff] p-4"><p className="m-0 text-sm font-bold text-[#087aa4]">AI助力招聘</p><p className="mb-0 mt-2 text-xs font-medium text-[#2788a8]">更高效 · 更精准</p><Bot className="ml-auto -mt-3 text-[#236ee8]" size={42}/></div>
      </aside>
      <main className="min-w-0 p-4 sm:p-5 xl:p-6">{children}</main>
    </div>
  </div>;
}
