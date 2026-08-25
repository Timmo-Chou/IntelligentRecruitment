import { Construction, Sparkles } from "lucide-react";
import { AppShell } from "@/components/layout/app-shell";

export function PhasePlaceholder({activeItem,title,description,phase}:{activeItem:string;title:string;description:string;phase:string}){
  return <AppShell activeItem={activeItem}><section><h1 className="m-0 text-[25px] font-bold text-[#09245d]">{title}</h1><p className="mt-1 text-sm text-[#55709d]">{description}</p></section><section className="mt-5 grid min-h-[420px] place-items-center rounded-xl border border-[#d6e5f5] bg-white p-8 text-center"><div className="max-w-md"><span className="mx-auto grid h-16 w-16 place-items-center rounded-2xl bg-gradient-to-br from-[#e9f4ff] to-[#e2fbf3] text-[#2873e6]"><Construction size={29}/></span><h2 className="mb-0 mt-5 text-xl text-[#102d64]">产品框架已就绪</h2><p className="mt-2 text-sm leading-6 text-[#6b82a6]">该模块将在 {phase} 接入真实业务流程。当前页面保留正式导航与权限框架，不展示会被误认为真实结果的演示数据。</p><span className="mt-4 inline-flex items-center gap-2 rounded-lg bg-[#eef7ff] px-3 py-2 text-xs font-semibold text-[#2769c6]"><Sparkles size={14}/>AI 输出需经过人工确认</span></div></section></AppShell>;
}
