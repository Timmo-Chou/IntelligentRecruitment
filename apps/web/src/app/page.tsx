import { ArrowRight, Bot, BriefcaseBusiness, CircleCheck, Clock3, FileSearch, Sparkles, Users } from "lucide-react";
import Link from "next/link";
import { AppShell } from "@/components/layout/app-shell";

const stats = [
  ["在招职位", "--", "Phase 3 接入", BriefcaseBusiness, "/jobs"],
  ["人才总数", "--", "Phase 4 接入", Users, "/candidates"],
  ["进行中任务", "--", "Phase 3 接入", Clock3, "/recruitment"],
  ["本月 AI 完成", "--", "业务能力接入后统计", CircleCheck, "/recruitment"],
] as const;

const tasks = [
  ["高级 Java 开发工程师", "JD 草稿等待确认", "5 分钟前", "待确认"],
  ["产品经理（SaaS 平台）", "简历筛选 42/60", "12 分钟前", "处理中"],
  ["测试工程师（自动化）", "候选人面试题已生成", "1 小时前", "已完成"],
] as const;

export default function OverviewPage() {
  return <AppShell activeItem="概览">
    <section className="flex flex-wrap items-start justify-between gap-4">
      <div><h1 className="m-0 text-[25px] font-bold tracking-tight text-[#09245d]">概览</h1><p className="mb-0 mt-1 text-sm text-[#55709d]">查看招聘进展、AI 任务和需要人工确认的事项</p></div>
      <Link href="/recruitment" className="primary-button"><Sparkles size={16}/> 开始智能招聘</Link>
    </section>

    <section className="mt-5 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="招聘概览">
      {stats.map(([label,value,note,Icon,href]) => <Link href={href} key={label} className="metric-card !min-h-[112px] transition hover:-translate-y-0.5 hover:border-[#b9d9ed]"><span className="metric-icon !h-10 !w-10"><Icon size={20}/></span><div className="min-w-0"><p className="m-0 text-sm font-semibold text-[#2b4775]">{label}</p><strong className="mt-1 block text-[30px] leading-none text-[#09245d]">{value}</strong><p className="mb-0 mt-2 truncate text-xs text-[#6c83a7]">{note}</p></div></Link>)}
    </section>

    <div className="mt-4 grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <section className="rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        <div className="flex items-center justify-between"><div><h2 className="m-0 text-base font-bold text-[#173568]">招聘任务</h2><p className="mb-0 mt-1 text-xs text-[#7187a8]">演示状态，正式数据将在 Phase 3 接入</p></div><Link href="/recruitment" className="flex items-center gap-1 text-xs font-semibold text-[#1672df]">查看全部 <ArrowRight size={14}/></Link></div>
        <div className="mt-4 divide-y divide-[#e4edf7]">{tasks.map(([title,desc,time,status]) => <article key={title} className="flex flex-wrap items-center gap-3 py-4"><span className="grid h-10 w-10 place-items-center rounded-xl bg-[#eaf7ff] text-[#1688d4]"><Bot size={19}/></span><div className="min-w-0 flex-1"><h3 className="m-0 text-sm font-semibold text-[#173568]">{title}</h3><p className="mb-0 mt-1 text-xs text-[#7187a8]">{desc} · {time}</p></div><span className={status === "已完成" ? "status-live text-xs" : "rounded-md bg-[#edf5ff] px-2 py-1 text-xs font-semibold text-[#3373c4]"}>{status}</span></article>)}</div>
      </section>

      <aside className="rounded-xl border border-[#d6e5f5] bg-gradient-to-br from-white to-[#effbff] p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        <span className="grid h-11 w-11 place-items-center rounded-2xl bg-gradient-to-br from-[#247aff] to-[#17bd91] text-white"><FileSearch size={22}/></span>
        <h2 className="mb-0 mt-4 text-lg font-bold text-[#102d64]">从一个招聘需求开始</h2><p className="mt-2 text-sm leading-6 text-[#60799f]">AI 协助生成 JD、解析简历、准备筛选方案和面试题；关键业务结果均由招聘人员确认。</p>
        <Link href="/recruitment" className="primary-button mt-4 w-full">进入智能招聘工作台 <ArrowRight size={15}/></Link>
        <div className="mt-5 rounded-lg border border-[#dbe8f5] bg-white/80 p-3 text-xs leading-5 text-[#60799f]">当前可用余额以顶部额度入口和账本页面为准。<br/>收费任务执行前会展示费用估算。</div>
      </aside>
    </div>
  </AppShell>;
}
