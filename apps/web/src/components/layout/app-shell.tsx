"use client";

import { Bell, Bot, BriefcaseBusiness, ChevronRight, LayoutDashboard, Library, MoreHorizontal, Pencil, Plus, Settings, Sparkles, Trash2, Users } from "lucide-react";
import Link from "next/link";
import React, { type ReactNode, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { apiFetch } from "@/lib/api-client";
import { SessionSummary } from "./session-summary";
import { WorkspaceSwitcher } from "./workspace-switcher";
import { useWorkspace } from "@/lib/workspace-context";
import { deleteTask, fetchTasks, renameTask, type TaskSummary } from "@/lib/recruitment-api";
import { AIChatDialog } from "../ai-assistant/ai-chat-dialog";

const navItems = [
  ["概览", LayoutDashboard, "/"], ["智能招聘", Sparkles, "/recruitment"],
  ["职位库", BriefcaseBusiness, "/jobs"], ["人才库", Users, "/candidates"], ["面试题库", Library, "/interviews"], ["设置", Settings, "/settings"],
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
  const router = useRouter();
  const selectedTaskId = searchParams.get("task");
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [menuTaskId, setMenuTaskId] = useState<string | null>(null);
  const [renamingTask, setRenamingTask] = useState<TaskSummary | null>(null);
  const [deletingTask, setDeletingTask] = useState<TaskSummary | null>(null);
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 下拉菜单的固定定位样式
  const [popupStyle, setPopupStyle] = useState<{ top: number; left: number } | null>(null);
  // Portal 挂载节点（SSR 时 document 不存在；hydration 后再赋值避免 DOM mismatch）
  const [portalHost, setPortalHost] = useState<HTMLElement | null>(null);
  useEffect(() => { setPortalHost(typeof document !== "undefined" ? document.body : null); }, []);
  // 折叠状态：使用localStorage持久化用户偏好
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    if (typeof window === "undefined" || typeof window.localStorage?.getItem !== "function") return false;
    return window.localStorage.getItem("task-history-collapsed") === "true";
  });
  useEffect(() => {
    if (!visible || !workspaceId) return;
    const load = () => { void fetchTasks(workspaceId).then(setTasks).catch(() => setTasks([])); };
    load();
    window.addEventListener("recruitment-tasks-changed", load);
    return () => window.removeEventListener("recruitment-tasks-changed", load);
  }, [visible, workspaceId]);
  // 保存折叠状态到localStorage
  const toggleCollapse = () => {
    const next = !collapsed;
    setCollapsed(next);
    if (typeof window !== "undefined" && typeof window.localStorage?.setItem === "function") {
      window.localStorage.setItem("task-history-collapsed", String(next));
    }
  };
  // 打开/关闭下拉菜单时，用fixed定位避免被父容器overflow裁切
  const toggleTaskMenu = (taskId: string, e: React.MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    e.preventDefault();
    if (menuTaskId === taskId) {
      setMenuTaskId(null);
      setPopupStyle(null);
    } else {
      const rect = e.currentTarget.getBoundingClientRect();
      setPopupStyle({ top: rect.bottom + 2, left: rect.right - 112 });
      setMenuTaskId(taskId);
    }
  };
  // 点击空白处关闭下拉
  useEffect(() => {
    if (!menuTaskId) return;
    const handler = () => {
      setMenuTaskId(null);
      setPopupStyle(null);
    };
    document.addEventListener("click", handler);
    return () => document.removeEventListener("click", handler);
  }, [menuTaskId]);
  const beginRename = (task: TaskSummary) => {
    setMenuTaskId(null);
    setError(null);
    setTitle(task.title);
    setRenamingTask(task);
  };
  const beginDelete = (task: TaskSummary) => {
    setMenuTaskId(null);
    setError(null);
    setDeletingTask(task);
  };
  const submitRename = async () => {
    if (!workspaceId || !renamingTask || !title.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await renameTask(workspaceId, renamingTask.id, title.trim());
      setTasks(current => current.map(task => task.id === updated.id ? updated : task));
      window.dispatchEvent(new Event("recruitment-tasks-changed"));
      setRenamingTask(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "重命名失败，请稍后重试");
    } finally { setBusy(false); }
  };
  const confirmDelete = async () => {
    if (!workspaceId || !deletingTask) return;
    setBusy(true);
    setError(null);
    try {
      await deleteTask(workspaceId, deletingTask.id);
      const wasSelected = selectedTaskId === deletingTask.id;
      setTasks(current => current.filter(task => task.id !== deletingTask.id));
      window.dispatchEvent(new Event("recruitment-tasks-changed"));
      setDeletingTask(null);
      if (wasSelected) router.push("/recruitment");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "删除失败，请稍后重试");
    } finally { setBusy(false); }
  };
  if (!visible) return null;
  return <section className="mb-3 ml-3 border-l border-[#d9e6f3] pl-3" aria-label="智能招聘任务">
    {/* 可折叠标题 */}
    <button
      type="button"
      onClick={toggleCollapse}
      className="mb-1 mt-3 flex w-full items-center gap-1 text-left text-xs font-semibold text-[#6e82a0] transition hover:text-[#27477f]"
      aria-expanded={!collapsed}
      aria-controls="task-history-list"
    >
      <ChevronRight
        size={14}
        className={`transition-transform duration-150 ${collapsed ? "" : "rotate-90"}`}
      />
      <span>任务历史</span>
    </button>
    {/* 列表内容：折叠时隐藏 */}
    <div
      id="task-history-list"
      className={`transition-[max-height] duration-200 ease-in-out ${collapsed ? "max-h-0" : "max-h-[600px]"}`}
    >
      <div className={`transition-[opacity] duration-150 ${collapsed ? "pointer-events-none opacity-0" : "opacity-100"}`}>
      {tasks.length
        ? <div className="space-y-1 pt-1">
            {tasks.slice(0, 8).map(task => <div key={task.id} className={`group flex items-center rounded-lg pr-1 transition ${selectedTaskId === task.id ? "bg-[#ddf8ef]" : "hover:bg-[#eef6ff]"}`}><Link href={`/recruitment?task=${task.id}`} className={`min-w-0 flex-1 truncate px-2 py-2 text-xs ${selectedTaskId === task.id ? "font-semibold text-[#07945f]" : "text-[#526e96]"}`} title={task.title}>{task.title}</Link>
              {/* 操作按钮 */}
              <div className="shrink-0">
                <button type="button" onClick={(e) => toggleTaskMenu(task.id, e)} className="grid h-7 w-7 place-items-center rounded-md text-[#7388a4] hover:bg-white hover:text-[#254c7e]" aria-label={`${task.title}更多操作`} aria-expanded={menuTaskId === task.id}>
                  <MoreHorizontal size={17}/>
                </button>
              </div>
            </div>)}
          </div>
        : <Link href="/recruitment" className="mt-1 flex items-center gap-2 rounded-lg px-2 py-2 text-sm text-[#60789d] hover:bg-[#eef6ff]"><Plus size={16}/>开始你的第一个任务</Link>}
      </div>
    </div>
    {/* 下拉菜单：fixed定位，避免被折叠容器overflow裁切 */}
    {menuTaskId && popupStyle && (() => {
      const task = tasks.find(t => t.id === menuTaskId);
      if (!task) return null;
      return (
        <div className="fixed z-[9999] w-28 rounded-lg border border-[#dce7f1] bg-white py-1 shadow-xl" style={{ top: popupStyle.top, left: popupStyle.left }}>
          <button type="button" onClick={() => beginRename(task)} className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs text-[#36557f] hover:bg-[#f3f8fe]"><Pencil size={14}/>重命名</button>
          <button type="button" onClick={() => beginDelete(task)} className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs text-[#d14343] hover:bg-[#fff3f3]"><Trash2 size={14}/>删除</button>
        </div>
      );
    })()}
    {/* 重命名 / 删除 两个确认弹窗：挂载到 document.body 彻底脱离 sidebar 层级，避免被主内容区卡片/阴影盖住 */}
    {portalHost && renamingTask && createPortal(<TaskDialog title="重命名招聘任务" busy={busy} error={error} onClose={() => setRenamingTask(null)}><label className="block text-sm font-medium text-[#36557f]">任务名称<input autoFocus value={title} onChange={event => setTitle(event.target.value)} maxLength={200} className="mt-2 h-10 w-full rounded-lg border border-[#cbdced] px-3 text-sm font-normal outline-none focus:border-[#16a99b]" placeholder="请输入任务名称"/></label><div className="mt-5 flex justify-end gap-2"><button type="button" onClick={() => setRenamingTask(null)} className="rounded-lg border border-[#d7e3ee] px-4 py-2 text-sm text-[#496587]">取消</button><button type="button" disabled={busy || !title.trim()} onClick={() => void submitRename()} className="rounded-lg bg-[#0ca58c] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">{busy ? "保存中..." : "保存"}</button></div></TaskDialog>, portalHost)}
    {portalHost && deletingTask && createPortal(<TaskDialog title="删除招聘任务" busy={busy} error={error} onClose={() => setDeletingTask(null)}><p className="m-0 text-sm leading-6 text-[#58708f]">确定删除“{deletingTask.title}”吗？删除后将永久移除任务、需求对话和未确认的 JD 草稿，且无法恢复。</p><div className="mt-5 flex justify-end gap-2"><button type="button" onClick={() => setDeletingTask(null)} className="rounded-lg border border-[#d7e3ee] px-4 py-2 text-sm text-[#496587]">取消</button><button type="button" disabled={busy} onClick={() => void confirmDelete()} className="rounded-lg bg-[#d94a4a] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">{busy ? "删除中..." : "确认删除"}</button></div></TaskDialog>, portalHost)}
  </section>;
}

function TaskDialog({ title, busy, error, onClose, children }: { title: string; busy: boolean; error: string | null; onClose: () => void; children: ReactNode }) {
  return <div className="fixed inset-0 z-50 grid place-items-center bg-[#102d64]/30 p-4" role="presentation"><section className="w-full max-w-sm rounded-2xl bg-white p-5 shadow-2xl" role="dialog" aria-modal="true" aria-label={title}><div className="flex items-center justify-between gap-3"><h2 className="m-0 text-base font-bold text-[#173568]">{title}</h2><button type="button" disabled={busy} onClick={onClose} className="text-sm text-[#7185a1] hover:text-[#244b7f]">关闭</button></div>{error && <p className="mb-0 mt-4 rounded-lg bg-[#fff1f1] px-3 py-2 text-xs leading-5 text-[#c53c3c]">{error}</p>}<div className="mt-4">{children}</div></section></div>;
}

export function AppShell({ children, activeItem = "概览", pageHeader }: { children: ReactNode; activeItem?: string; pageHeader?: ReactNode }) {
  const router=useRouter(); const pathname=usePathname();
  // AI咨询助手开关
  const [aiOpen, setAiOpen] = useState(false);
  useEffect(()=>{if(pathname==="/onboarding"||pathname==="/login")return; apiFetch<unknown[]>("/workspaces").then(items=>{if(!items.length)router.replace("/onboarding");}).catch(()=>{});},[pathname,router]);
  return <div className="min-h-screen bg-[#f7fbff] text-[#10285b]">
    <header className="app-header sticky top-0 z-40 flex h-[66px] items-center justify-between border-b border-[#dbe9f8] bg-white px-6 lg:px-8">
      <div className="flex items-center gap-3 text-[21px] font-bold tracking-tight text-[#09245d]"><span className="brand-mark" aria-hidden="true"><i/><i/></span>iFoundX 智能招聘工作台</div>
      <div className="flex items-center gap-2">
        <NotificationBell />
        <WorkspaceSwitcher />
        <SessionSummary/>
      </div>
    </header>
    <div className="grid min-h-[calc(100vh-66px)] grid-cols-1 lg:grid-cols-[210px_minmax(0,1fr)]">
      <aside className="sticky top-[66px] hidden h-[calc(100vh-66px)] border-r border-[#dbe9f8] bg-white px-3 py-4 lg:flex lg:flex-col overflow-hidden">
        <nav className="space-y-1 overflow-y-auto overflow-x-hidden" aria-label="主导航">
          {navItems.map(([label, Icon, href]) => (
            <React.Fragment key={label}>
              <Link href={href} className={`flex w-full items-center gap-3 rounded-xl px-4 py-3 text-left text-[15px] transition ${label === "设置" ? "mt-5" : ""} ${activeItem === label ? "bg-[#ddf8ef] font-semibold text-[#07945f]" : "text-[#27477f] hover:bg-[#f3f8fe]"}`}>
                <Icon aria-hidden="true" size={18} strokeWidth={1.9}/>{label}
              </Link>
              {label === "智能招聘" && (
                <RecruitmentTaskMenu visible={true} />
              )}
            </React.Fragment>
          ))}
        </nav>
        {/* AI咨询助手：放在左侧菜单栏的最右边（卡片内部靠右对齐） */}
        <button
          type="button"
          onClick={() => setAiOpen(true)}
          className="relative mt-auto w-full cursor-pointer overflow-hidden rounded-xl bg-gradient-to-br from-[#d8fff6] to-[#d7efff] p-4 text-left transition hover:shadow-md hover:brightness-105 active:scale-[0.99]"
          aria-label="AI咨询助手"
          title="点击打开AI咨询助手"
        >
          {/* 未读红点：放到按钮右上角 */}
          <span className="absolute right-2 top-2 z-10 h-2.5 w-2.5 rounded-full bg-red-500 ring-2 ring-white"/>
          <p className="m-0 text-sm font-bold text-[#087aa4]">AI咨询助手</p>
          <p className="mb-0 mt-2 text-xs font-medium text-[#2788a8]">随时提问招聘问题</p>
          {/* 机器人图标：卡片内部最右边 */}
          <span className="pointer-events-none absolute bottom-2 right-2 grid h-11 w-11 place-items-center rounded-full bg-white/70 text-[#236ee8] shadow-sm">
            <Bot size={26}/>
          </span>
        </button>
      </aside>
      <main className="min-w-0">
        {/* 页面标题 + 说明 + 操作按钮：固定在顶部导航下方 */}
        {pageHeader && (
          <div className="sticky top-[66px] z-30 border-b border-[#dbe9f8] bg-[#f7fbff]/90 px-4 pb-4 pt-4 backdrop-blur sm:px-5 xl:px-6">
            {pageHeader}
          </div>
        )}
        <div className="px-4 py-4 sm:px-5 xl:px-6">{children}</div>
      </main>
    </div>
    {/* AI咨询助手对话弹窗 */}
    {aiOpen && <AIChatDialog onClose={() => setAiOpen(false)} />}
  </div>;
}
