"use client";

import { useEffect, useMemo, useState } from "react";
import { CheckCircle2, Library, Loader2 } from "lucide-react";
import { AppShell } from "@/components/layout/app-shell";
import { apiFetch } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

type Question = { id: string; category: string; content: string; focusPoints: string; referenceAnswerPoints: string; scoringPoints: string };
type Kit = { id: string; candidateName: string; jobTitle: string; status: string; createdAt: string };
type KitDetail = { id: string; candidateName: string; jobTitle: string; status: string; coreCompetencies: { name: string; description: string }[]; matchSummary: string; questions: Question[] };

export default function InterviewsPage() {
  const { workspaceId } = useWorkspace();
  const [kits, setKits] = useState<Kit[]>([]);
  const [detail, setDetail] = useState<KitDetail | null>(null);
  const [loading, setLoading] = useState(false);

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    try { setKits(await apiFetch<Kit[]>(`/workspaces/${workspaceId}/interview-kits`)); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, [workspaceId]);
  const groups = useMemo(() => {
    const map = new Map<string, Question[]>();
    detail?.questions.forEach((question) => map.set(question.category, [...(map.get(question.category) ?? []), question]));
    return [...map.entries()];
  }, [detail]);
  async function open(kit: Kit) { if (workspaceId) setDetail(await apiFetch<KitDetail>(`/workspaces/${workspaceId}/interview-kits/${kit.id}`)); }

  return <AppShell activeItem="面试题库" pageHeader={<div className="flex items-center gap-3"><span className="grid h-12 w-12 place-items-center rounded-2xl bg-[#edf5ff] text-[#2878da]"><Library size={24}/></span><div><h1 className="m-0 text-[25px] font-bold text-[#09245d]">面试题库</h1><p className="mb-0 mt-1 text-sm text-[#55709d]">已发布的题包会保留对应 JD、人才及结构化面试结果。</p></div></div>}>
    <div className="grid gap-4 xl:grid-cols-[320px_minmax(0,1fr)]"><aside className="rounded-xl border border-[#d6e5f5] bg-white p-4"><h2 className="m-0 text-base font-bold text-[#173568]">已保存题包</h2>{loading && <Loader2 className="mx-auto my-8 animate-spin text-[#2878da]"/>}{!loading && kits.length === 0 && <p className="mt-5 text-sm leading-6 text-[#7187a8]">暂无题包。请在「智能招聘」中选择 JD 和人才后生成并发布。</p>}{kits.map((kit) => <button type="button" key={kit.id} onClick={() => void open(kit)} className={`mt-3 w-full rounded-lg border p-3 text-left transition ${detail?.id === kit.id ? "border-[#69a8ed] bg-[#f1f8ff]" : "border-[#dce7f3] hover:bg-[#f8fbff]"}`}><p className="m-0 text-sm font-semibold text-[#244a78]">{kit.jobTitle || "未关联 JD"}</p><p className="mb-0 mt-1 text-xs text-[#7187a8]">人才：{kit.candidateName}</p><span className="mt-2 inline-flex items-center gap-1 text-[11px] font-semibold text-[#07885b]">{kit.status === "CONFIRMED" && <CheckCircle2 size={12}/>} {kit.status === "CONFIRMED" ? "已发布" : "草稿"}</span></button>)}</aside>
      <main className="rounded-xl border border-[#d6e5f5] bg-white p-5">{!detail ? <div className="grid min-h-[420px] place-items-center text-center text-sm text-[#7187a8]">从左侧选择一个面试题包查看完整结果。</div> : <div className="space-y-5"><header><h2 className="m-0 text-xl font-bold text-[#173568]">{detail.jobTitle} · {detail.candidateName}</h2><p className="mb-0 mt-2 text-sm text-[#7187a8]">题包状态：{detail.status === "CONFIRMED" ? "已发布" : "草稿"}</p></header><section className="rounded-xl bg-[#f5f9ff] p-4"><h3 className="m-0 text-base font-bold text-[#173568]">核心胜任能力</h3><div className="mt-3 grid gap-3 md:grid-cols-3">{detail.coreCompetencies.map((item) => <div key={item.name}><p className="m-0 text-sm font-semibold text-[#2467ba]">{item.name}</p><p className="mb-0 mt-1 text-xs leading-5 text-[#617a9d]">{item.description}</p></div>)}</div></section><section><h3 className="m-0 text-base font-bold text-[#173568]">匹配度分析总结</h3><p className="mb-0 mt-2 text-sm leading-6 text-[#526b90]">{detail.matchSummary}</p></section><section><h3 className="m-0 text-base font-bold text-[#173568]">面试题结果</h3><div className="mt-3 space-y-4">{groups.map(([type, questions]) => <article key={type} className="rounded-xl border border-[#dce7f3] p-4"><h4 className="m-0 text-sm font-bold text-[#1e65bd]">{type}</h4><p className="mb-0 mt-1 text-xs text-[#687e9c]">核心考察点：{questions[0]?.focusPoints}</p>{questions.map((question) => <div key={question.id} className="mt-3 border-t border-[#edf1f5] pt-3 text-sm"><p className="m-0 font-semibold text-[#29486e]">{question.content}</p><p className="mb-0 mt-2 text-xs leading-5 text-[#617a9d]">参考答案要点：{question.referenceAnswerPoints}<br/>评分标准：{question.scoringPoints}</p></div>)}</article>)}</div></section></div>}</main></div>
  </AppShell>;
}
