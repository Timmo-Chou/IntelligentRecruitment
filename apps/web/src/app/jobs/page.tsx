import { BriefcaseBusiness, Building2, CalendarPlus, ChevronDown, CircleDot, Edit3, MapPin, Plus, Search, TimerReset } from "lucide-react";
import type { ReactNode } from "react";
import { AppShell } from "@/components/layout/app-shell";

const metrics = [
  ["职位总数", "128", "▲ 12%（较上周）", BriefcaseBusiness], ["今日新增", "6", "▲ 20%（较昨日）", CalendarPlus],
  ["正在招聘", "34", "", TimerReset], ["已关闭", "94", "", CircleDot],
] as const;
const jobs = [
  ["高级Java开发工程师", "北京智联科技有限公司", "上海 · 张江", "2025-05-26 10:30", "2025-05-26 14:20", "招聘中"],
  ["产品经理（SaaS平台）", "深圳云创科技", "北京 · 望京", "2025-05-25 09:15", "2025-05-25 16:40", "招聘中"],
  ["测试工程师（自动化）", "杭州数智科技", "杭州 · 滨江", "2025-05-24 15:40", "2025-05-24 18:30", "已关闭"],
  ["市场运营专员", "广州启航科技", "广州 · 天河", "2025-05-23 11:20", "2025-05-23 11:20", "招聘中"],
  ["数据分析师", "上海数聚信息", "上海 · 浦东", "2025-05-22 14:10", "2025-05-22 17:45", "已关闭"],
  ["前端开发工程师", "成都云帆科技", "成都 · 高新", "2025-05-21 10:00", "2025-05-21 13:50", "招聘中"],
  ["UI/UX设计师", "深圳创想科技", "深圳 · 南山", "2025-05-20 16:30", "2025-05-20 19:10", "招聘中"],
];

export default function HomePage() {
  return <AppShell activeItem="职位库">
    <section className="mb-4 flex flex-wrap items-end justify-between gap-3"><div className="flex items-baseline gap-4"><h1 className="m-0 text-[25px] font-bold tracking-tight text-[#09245d]">职位库</h1><p className="m-0 text-sm text-[#55709d]">管理和搜索企业职位信息，支持向量语义搜索</p></div></section>
    <section className="mb-4 grid gap-3 xl:grid-cols-[minmax(360px,1fr)_112px_116px_116px]">
      <label className="flex h-11 items-center gap-3 rounded-lg border border-[#bdd3ef] bg-white px-4 text-[#6b80a4] shadow-sm"><Search size={18}/><input className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none" placeholder="搜索职位名称、关键词、行业、地点等（支持语义搜索，如‘Java开发工程师’）"/></label>
      <button className="outline-button" type="button">全部状态 <ChevronDown size={15}/></button><button className="primary-button" type="button"><Plus size={16}/> 新建职位</button><button className="outline-button" type="button">批量操作 <ChevronDown size={15}/></button>
    </section>
    <div className="grid gap-3 2xl:grid-cols-[minmax(700px,1fr)_420px]">
      <div className="min-w-0">
        <section className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4" aria-label="职位概览">{metrics.map(([label,value,note,Icon]) => <article key={label} className="metric-card"><span className="metric-icon"><Icon size={18}/></span><div><p className="m-0 text-sm font-semibold text-[#2b4775]">{label}</p><p className="mb-0 mt-1 flex items-end gap-2"><strong className="text-[27px] leading-none text-[#09245d]">{value}<small className="ml-1 text-sm">个</small></strong>{note && <span className="text-[10px] font-semibold text-[#06925f]">{note}</span>}</p></div></article>)}</section>
        <section className="overflow-hidden rounded-xl border border-[#d6e5f5] bg-white shadow-[0_6px_20px_rgba(30,92,160,0.04)]"><div className="overflow-x-auto"><table className="w-full min-w-[900px] border-collapse text-left text-xs text-[#36527f]"><thead className="bg-[#f8fbff] text-[#536b91]"><tr>{["", "职位名称", "企业名称", "工作地点", "创建时间", "更新时间", "状态", "操作"].map((item,i) => <th key={`${item}-${i}`} className="border-b border-[#dbe8f6] px-3 py-3 font-medium">{item || <input type="checkbox" aria-label="选择全部职位"/>}</th>)}</tr></thead><tbody>{jobs.map((job,index) => <tr key={job[0]} className={index === 0 ? "bg-[#eafff7]" : "hover:bg-[#f8fbff]"}><td className="table-cell"><input type="checkbox" aria-label={`选择${job[0]}`}/></td><td className="table-cell font-semibold text-[#132e61]">{job[0]}</td><td className="table-cell">{job[1]}</td><td className="table-cell">{job[2]}</td><td className="table-cell">{job[3]}</td><td className="table-cell">{job[4]}</td><td className="table-cell"><span className={job[5] === "招聘中" ? "status-live" : "status-closed"}>● {job[5]}</span></td><td className="table-cell whitespace-nowrap font-medium text-[#0874e8]">查看　编辑　删除</td></tr>)}</tbody></table></div>
          <footer className="flex items-center justify-end gap-2 px-4 py-4 text-xs text-[#4d6388]"><span className="mr-3">共 128 条</span>{["1","2","3","4","5","…","13"].map((page,i) => <button key={`${page}-${i}`} className={i===0 ? "page-active" : "page-button"} type="button">{page}</button>)}<button className="outline-button !h-8 !px-3" type="button">每页 10 条 <ChevronDown size={13}/></button></footer>
        </section>
      </div>
      <aside className="rounded-xl border border-[#d6e5f5] bg-white p-4 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        <div className="flex items-center justify-between border-b border-[#e0eaf5] pb-3"><h2 className="m-0 text-base font-bold text-[#173568]">职位详情</h2><button className="outline-button !h-8 !px-3 text-[#0874e8]" type="button"><Edit3 size={14}/> 编辑</button></div>
        <div className="py-4"><div className="flex items-center gap-3"><h3 className="m-0 text-xl font-bold text-[#102d64]">高级Java开发工程师</h3><span className="rounded-md bg-[#dff8ee] px-2 py-1 text-xs font-semibold text-[#07945f]">招聘中</span></div><p className="mb-2 mt-3 flex items-center gap-2 text-sm font-semibold"><Building2 size={16}/> 北京智联科技有限公司</p><p className="m-0 flex flex-wrap items-center gap-3 text-xs text-[#4b6793]"><span className="flex items-center gap-1"><MapPin size={14}/> 上海·张江</span><span>▣ 5年以上</span><span>本科及以上</span><span>全职</span></p></div>
        <Detail title="职位描述"><p>负责公司核心业务系统的后端设计与开发，参与分布式系统架构设计与落地；负责核心模块的开发与优化，保障系统的稳定性与高可用。</p><p>参与需求分析、独立设计及文档编写；与产品、前端、测试等团队紧密协作，推动项目高质量交付。</p></Detail>
        <Detail title="任职要求"><ul><li>本科及以上学历，计算机相关专业，5年以上后端开发经验；</li><li>精通 Java，熟悉 Spring Boot、Spring Cloud 等主流框架；</li><li>熟悉 MySQL、Redis、Kafka 等中间件的使用；</li><li>具备良好的沟通与协作能力，责任心强。</li></ul></Detail>
        <Detail title="关键技能"><div className="flex flex-wrap gap-2">{["Java","Spring Boot","Spring Cloud","MySQL","Redis","Kafka"].map(skill => <span key={skill} className="skill-tag">{skill}</span>)}</div></Detail>
        <Detail title="创建信息"><p>创建人：张晓梅（HRBP）　创建时间：2025-05-26 10:30</p></Detail>
        <div className="mt-4 grid grid-cols-2 gap-3 rounded-xl border border-[#deebf7] bg-[#f9fcff] p-4"><button className="primary-button" type="button">AI招聘助手</button><button className="outline-button text-[#1872e8]" type="button">删除职位</button></div>
      </aside>
    </div>
  </AppShell>;
}

function Detail({title,children}:{title:string;children:ReactNode}) { return <section className="mb-4 text-xs leading-6 text-[#344f7b]"><h4 className="mb-1 mt-0 text-sm font-bold text-[#173568]">{title}</h4>{children}</section>; }
