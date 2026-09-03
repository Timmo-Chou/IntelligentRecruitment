"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { CheckCircle2, FilePenLine, ListChecks, Loader2, Save, Sparkles } from "lucide-react";
import { apiFetch } from "@/lib/api-client";
import { fetchJobVersions, type Job } from "@/lib/job-api";
import type { CandidateSummary } from "@/lib/candidate-api";

type CoreCompetency = { name: string; description: string };
type InterviewQuestion = { id?: string; category: string; content: string; rationale: string; focusPoints: string; referenceAnswerPoints: string; scoringPoints: string; evidenceRefs: string; sortOrder?: number };
type InterviewKit = { id: string; jobTitle: string; candidateName: string; status: string; coreCompetencies: CoreCompetency[]; matchSummary: string; questions: InterviewQuestion[] };

export function InterviewWorkspace({
  workspaceId,
  job,
  candidate,
  /** 每递增一次，组件自动触发一次出题（用于「智能招聘首页点击发送后自动出题」，无需用户再点按钮）。undefined/0 表示不自动触发。 */
  autoGenerateTrigger = 0,
}: {
  workspaceId: string;
  job: Job | null;
  candidate: CandidateSummary | null;
  autoGenerateTrigger?: number;
}) {
  const [kit, setKit] = useState<InterviewKit | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  // 记录已经处理过的 trigger，防止 StrictMode 双渲染 / props 复用时重复调用
  const handledTriggerRef = useRef<number>(0);

  useEffect(() => { setKit(null); setMessage(""); }, [job?.id, candidate?.id]);

  /** 自动出题：autoGenerateTrigger 递增一次，且职位 + 人才都已选中时，立即调 generate() */
  useEffect(() => {
    if (!autoGenerateTrigger || autoGenerateTrigger <= handledTriggerRef.current) return;
    if (!job || !candidate || loading || kit) return;
    handledTriggerRef.current = autoGenerateTrigger;
    void generate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoGenerateTrigger, job, candidate]);

  const groups = useMemo(() => {
    const map = new Map<string, InterviewQuestion[]>();
    for (const question of kit?.questions ?? []) map.set(question.category, [...(map.get(question.category) ?? []), question]);
    return [...map.entries()];
  }, [kit]);

  async function generate() {
    if (!job || !candidate) return;
    setLoading(true); setMessage("");
    try {
      const versions = await fetchJobVersions(workspaceId, job.id);
      const latest = versions[0];
      if (!latest) throw new Error("所选 JD 暂无可用版本，请先在职位库保存 JD。");
      const result = await apiFetch<InterviewKit>(`/workspaces/${workspaceId}/interview-kits`, {
        method: "POST", body: JSON.stringify({ candidateId: candidate.id, jobVersionId: latest.id, questionCount: 8 }),
      });
      setKit(result); setMessage("已根据所选 JD 和人才生成可编辑题包。");
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : "生成面试题失败"); }
    finally { setLoading(false); }
  }

  function updateQuestion(index: number, field: keyof InterviewQuestion, value: string) {
    if (!kit) return;
    setKit({ ...kit, questions: kit.questions.map((question, i) => i === index ? { ...question, [field]: value } : question) });
  }

  async function save() {
    if (!kit) return;
    setSaving(true); setMessage("");
    try {
      const result = await apiFetch<InterviewKit>(`/workspaces/${workspaceId}/interview-kits/${kit.id}`, { method: "PUT", body: JSON.stringify(kit.questions) });
      setKit(result); setMessage("题目草稿已保存。");
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : "保存失败"); }
    finally { setSaving(false); }
  }

  async function publish() {
    if (!kit) return;
    setSaving(true); setMessage("");
    try {
      const result = await apiFetch<InterviewKit>(`/workspaces/${workspaceId}/interview-kits/${kit.id}/confirm`, { method: "POST" });
      setKit(result); setMessage("面试题已发布并保存至面试题库。");
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : "发布失败"); }
    finally { setSaving(false); }
  }

  if (!job || !candidate) return <section className="grid min-h-[430px] place-items-center rounded-xl border border-dashed border-[#c8d9eb] bg-[#f9fcff] p-8 text-center"><div><ListChecks className="mx-auto text-[#2878da]" size={34}/><h2 className="mb-2 mt-4 text-xl font-bold text-[#173568]">请选择 JD 与人才</h2><p className="m-0 max-w-md text-sm leading-6 text-[#6a7f9e]">在智能招聘输入框上方完成 JD 和人才选择后，才可以生成针对该候选人的面试题。</p></div></section>;

  return <section className="space-y-4">
    <header className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-[#d6e5f5] bg-[#f8fbff] p-4"><div><p className="m-0 text-xs font-semibold text-[#2878da]">AI 面试出题</p><h2 className="mb-0 mt-1 text-xl font-bold text-[#173568]">{job.title} · {candidate.displayNameMasked}</h2><p className="mb-0 mt-1 text-xs text-[#7187a8]">基于 JD 版本与人才档案生成，支持逐题编辑后发布。</p></div>{!kit && <button type="button" onClick={() => void generate()} disabled={loading} className="primary-button">{loading ? <Loader2 className="animate-spin" size={16}/> : <Sparkles size={16}/>}生成面试题</button>}{kit && <div className="flex gap-2"><button type="button" onClick={() => void save()} disabled={saving} className="outline-button"><Save size={15}/>保存草稿</button><button type="button" onClick={() => void publish()} disabled={saving || kit.status === "CONFIRMED"} className="primary-button">{kit.status === "CONFIRMED" ? <CheckCircle2 size={15}/> : <FilePenLine size={15}/>} {kit.status === "CONFIRMED" ? "已发布" : "发布到题库"}</button></div>}</header>
    {message && <p className="m-0 rounded-lg bg-[#eef8ff] px-3 py-2 text-sm text-[#35628f]">{message}</p>}
    {kit && <>
      <section className="rounded-xl border border-[#d6e5f5] bg-white p-4"><h3 className="m-0 text-base font-bold text-[#173568]">JD 要求的核心胜任能力</h3><div className="mt-3 grid gap-3 md:grid-cols-3">{kit.coreCompetencies.map((item) => <article key={item.name} className="rounded-lg bg-[#f4f9ff] p-3"><p className="m-0 font-semibold text-[#1e65bd]">{item.name}</p><p className="mb-0 mt-1 text-xs leading-5 text-[#647a9b]">{item.description}</p></article>)}</div></section>
      <section className="rounded-xl border border-[#d6e5f5] bg-white p-4"><h3 className="m-0 text-base font-bold text-[#173568]">JD 与人才的匹配度分析总结</h3><p className="mb-0 mt-3 whitespace-pre-wrap text-sm leading-6 text-[#526b90]">{kit.matchSummary}</p></section>
      <section className="rounded-xl border border-[#d6e5f5] bg-white p-4"><h3 className="m-0 text-base font-bold text-[#173568]">面试题结果</h3><p className="mb-0 mt-1 text-xs text-[#7187a8]">按面试题类型组织；每类题目的说明文字即为核心考察点。</p><div className="mt-4 space-y-5">{groups.map(([type, questions]) => <section key={type} className="rounded-xl border border-[#e0eaf4] bg-[#fbfdff] p-3"><div className="border-b border-[#e8eef5] pb-3"><h4 className="m-0 font-bold text-[#1d5fae]">{type}</h4><p className="mb-0 mt-1 text-xs text-[#687e9c]">核心考察点：{questions[0]?.focusPoints}</p></div><div className="mt-3 space-y-3">{questions.map((question) => { const index = kit.questions.indexOf(question); return <article key={question.id ?? index} className="rounded-lg border border-[#dce7f3] bg-white p-3"><label className="block text-xs font-semibold text-[#4b668b]">面试题目<textarea value={question.content} onChange={(event) => updateQuestion(index, "content", event.target.value)} className="mt-1 min-h-20 w-full rounded-md border border-[#cbdbea] p-2 text-sm font-normal text-[#29486e] outline-none focus:border-[#4a8be8]"/></label><label className="mt-3 block text-xs font-semibold text-[#4b668b]">参考答案要点<textarea value={question.referenceAnswerPoints} onChange={(event) => updateQuestion(index, "referenceAnswerPoints", event.target.value)} className="mt-1 min-h-16 w-full rounded-md border border-[#cbdbea] p-2 text-sm font-normal text-[#29486e] outline-none focus:border-[#4a8be8]"/></label><label className="mt-3 block text-xs font-semibold text-[#4b668b]">评分标准<textarea value={question.scoringPoints} onChange={(event) => updateQuestion(index, "scoringPoints", event.target.value)} className="mt-1 min-h-16 w-full rounded-md border border-[#cbdbea] p-2 text-sm font-normal text-[#29486e] outline-none focus:border-[#4a8be8]"/></label></article>; })}</div></section>)}</div></section>
    </>}
  </section>;
}
