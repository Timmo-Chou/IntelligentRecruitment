"use client";

import { AlertCircle, CheckCircle2, CircleDollarSign, Filter, Loader2, Pencil, RefreshCw, Save, Square, Target, UsersRound, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import { fetchCandidates, type CandidateSummary } from "@/lib/candidate-api";
import { fetchJobs, type Job } from "@/lib/job-api";
import { cancelScreeningRun, createRetryScreeningQuote, createScreeningPlan, createScreeningQuote, defaultScreeningDimensions, fetchScreeningPlans, fetchScreeningPricing, fetchScreeningRun, fetchScreeningRuns, retryFailedScreening, startScreeningRun, updateScreeningPlan, type ScreeningDimension, type ScreeningPlan, type ScreeningPricing, type ScreeningRun, type ScreeningRunSummary } from "@/lib/screening-api";
import { useWorkspace } from "@/lib/workspace-context";

const showMockScenarios = process.env.NEXT_PUBLIC_SHOW_AI_MOCK_SCENARIOS === "true";

export function ScreeningWorkspace({ embedded = false, recruitmentTaskId, initialJobId }: { embedded?: boolean; recruitmentTaskId?: string; initialJobId?: string | null }) {
  const { workspaceId, workspace, loading: workspaceLoading, notAuthenticated } = useWorkspace();
  const [jobs, setJobs] = useState<Job[]>([]), [candidates, setCandidates] = useState<CandidateSummary[]>([]);
  const [plans, setPlans] = useState<ScreeningPlan[]>([]), [runs, setRuns] = useState<ScreeningRunSummary[]>([]);
  const [pricing, setPricing] = useState<ScreeningPricing | null>(null), [jobId, setJobId] = useState(""), [planId, setPlanId] = useState("");
  const [dimensions, setDimensions] = useState<ScreeningDimension[]>(defaultScreeningDimensions), [selected, setSelected] = useState<Set<string>>(new Set());
  const [result, setResult] = useState<ScreeningRun | null>(null), [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [error, setError] = useState<string | null>(null);
  const [editingJob, setEditingJob] = useState(false), [pickerOpen, setPickerOpen] = useState(false), [candidateFilter, setCandidateFilter] = useState(""), [scenario, setScenario] = useState("NORMAL");
  const workspaceRef = useRef<string | null>(null), selectedInitialised = useRef(false), inFlight = useRef(false);

  const load = useCallback(async () => {
    if (!workspaceId) return;
    setLoading(true); setError(null);
    try {
      const [jobData, candidateData, planData, runData, pricingData] = await Promise.all([
        fetchJobs(workspaceId, { page: 1, pageSize: 100 }),
        fetchCandidates(workspaceId, { status: "PARSED", pageSize: 200 }),
        fetchScreeningPlans(workspaceId, recruitmentTaskId), fetchScreeningRuns(workspaceId, recruitmentTaskId), fetchScreeningPricing(workspaceId),
      ]);
      if (workspaceRef.current !== workspaceId) return;
      const plan = planData[0];
      setJobs(jobData.items); setCandidates(candidateData.items); setPlans(planData); setRuns(runData); setPricing(pricingData);
      setJobId(current => current || initialJobId || plan?.jobId || jobData.items[0]?.id || "");
      setPlanId(plan?.id || ""); setDimensions(plan?.dimensions || defaultScreeningDimensions);
      if (!selectedInitialised.current) { selectedInitialised.current = true; setSelected(new Set(candidateData.items.map(item => item.id))); }
    } catch (cause) { setError(messageOf(cause)); } finally { setLoading(false); }
  }, [workspaceId, recruitmentTaskId, initialJobId]);

  useEffect(() => { workspaceRef.current = workspaceId; selectedInitialised.current = false; setResult(null); void load(); }, [workspaceId, load]);
  useEffect(() => { if (notAuthenticated) window.location.replace("/login"); }, [notAuthenticated]);
  const selectedJob = jobs.find(job => job.id === jobId), resultMode = result !== null;
  const visibleCandidates = candidates.filter(item => `${item.displayNameMasked} ${item.headline} ${item.skills.join(" ")}`.toLowerCase().includes(candidateFilter.toLowerCase()));
  const totalWeight = dimensions.reduce((sum, item) => sum + item.weight, 0);

  function patchDimension(index: number, patch: Partial<ScreeningDimension>) { setDimensions(current => current.map((item, i) => i === index ? { ...item, ...patch } : item)); }
  function toggleCandidate(id: string) { setSelected(current => { const next = new Set(current); next.has(id) ? next.delete(id) : next.add(id); return next; }); }
  async function ensurePlan() {
    if (!workspaceId || !jobId) throw new Error("请先选择职位");
    const title = jobs.find(item => item.id === jobId)?.title || "职位";
    const plan = planId
      ? await updateScreeningPlan(workspaceId, planId, dimensions, jobId)
      : await createScreeningPlan(workspaceId, { jobId, name: `${title}筛选方案`, dimensions, recruitmentTaskId });
    setPlanId(plan.id); setDimensions(plan.dimensions); setPlans(current => [plan, ...current.filter(item => item.id !== plan.id)]);
    return plan;
  }
  async function savePlan() { setBusy(true); setError(null); try { await ensurePlan(); } catch (cause) { setError(messageOf(cause)); } finally { setBusy(false); } }
  async function poll(runId: string) {
    if (!workspaceId) return;
    for (let i = 0; i < 240; i += 1) {
      await new Promise(resolve => window.setTimeout(resolve, 500));
      if (workspaceRef.current !== workspaceId) return;
      const next = await fetchScreeningRun(workspaceId, runId); setResult(next);
      if (next.status !== "RUNNING") { setRuns(await fetchScreeningRuns(workspaceId, recruitmentTaskId)); return; }
    }
  }
  async function start() {
    if (!workspaceId || !jobId || !selected.size || inFlight.current) return;
    inFlight.current = true; setBusy(true); setError(null);
    try {
      const plan = await ensurePlan(), ids = [...selected], quote = await createScreeningQuote(workspaceId, plan.id, ids);
      if (quote.availableAmountMinor < quote.estimatedAmountMinor) throw new Error("当前工作空间额度不足，请充值后重试。");
      if (!window.confirm(`候选人：${quote.candidateCount} 人\n预计冻结：¥${money(quote.estimatedAmountMinor)}\n仅成功结果结算。是否继续？`)) return;
      const run = await startScreeningRun(workspaceId, plan.id, ids, scenario, quote.id, crypto.randomUUID());
      setResult(run); setRuns(await fetchScreeningRuns(workspaceId, recruitmentTaskId)); void poll(run.id);
    } catch (cause) { setError(messageOf(cause)); } finally { inFlight.current = false; setBusy(false); }
  }
  async function openRun(id: string) { if (!workspaceId) return; setBusy(true); try { setResult(await fetchScreeningRun(workspaceId, id)); } catch (cause) { setError(messageOf(cause)); } finally { setBusy(false); } }
  async function retry() {
    if (!workspaceId || !result) return; setBusy(true);
    try { const quote = await createRetryScreeningQuote(workspaceId, result.id); const next = await retryFailedScreening(workspaceId, result.id, quote.id, crypto.randomUUID()); setResult(next); void poll(next.id); }
    catch (cause) { setError(messageOf(cause)); } finally { setBusy(false); }
  }
  async function cancel() { if (!workspaceId || !result || !window.confirm("确定取消该筛选任务吗？")) return; setBusy(true); try { setResult(await cancelScreeningRun(workspaceId, result.id, crypto.randomUUID())); } catch (cause) { setError(messageOf(cause)); } finally { setBusy(false); } }

  if (workspaceLoading) return embedded ? <Loading text="正在加载工作空间..."/> : <PageState text="正在加载工作空间..."/>;
  if (!workspaceId) return embedded ? <Loading text="请先进入一个可访问的工作空间"/> : <PageState text="请先进入一个可访问的工作空间"/>;
  const body = <>{error && <div className="flex items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}
    {loading ? <Loading text="正在读取筛选数据..."/> : <section className="mt-4 rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#e4edf6] pb-4"><div><h2 className="m-0 flex items-center gap-2 text-base text-[#173568]"><Target className="text-[#13977e]" size={18}/>{resultMode ? "筛选结果" : "简历筛选"}</h2><p className="mb-0 mt-1 text-xs text-[#7185a3]">{selectedJob ? `${selectedJob.title} · ${selectedJob.location || "工作地点待确认"} · ${selectedJob.experienceLevel || "经验待确认"}` : "请确认本次筛选对应的职位"}</p></div>
        {resultMode && <div className="flex gap-2">{result?.status === "RUNNING" && <button type="button" className="outline-button" onClick={() => void cancel()} disabled={busy}><Square size={13}/>取消任务</button>}{result?.status !== "RUNNING" && result?.items.some(item => item.status === "FAILED") && <button type="button" className="outline-button" onClick={() => void retry()} disabled={busy}><RefreshCw size={14}/>重试失败项</button>}{result?.status !== "RUNNING" && <button type="button" className="primary-button" onClick={() => setResult(null)}><Pencil size={14}/>调整方案并重新筛选</button>}</div>}</div>
      {resultMode ? <ResultList result={result!}/> : <div className="mt-5 space-y-6">
        <section><h3 className="section-title">已选职位</h3><div className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-[#f6f9fd] px-4 py-3"><div><strong className="text-sm text-[#29486f]">{selectedJob?.title || "尚未选择职位"}</strong><p className="mb-0 mt-1 text-xs text-[#7185a3]">{selectedJob ? `${selectedJob.companyName} · ${selectedJob.skills || "技能待确认"}` : "从职位库选择本次筛选的职位"}</p></div>{editingJob ? <select autoFocus value={jobId} onChange={event => { setJobId(event.target.value); setEditingJob(false); }} className="field-control w-56"><option value="">请选择职位</option>{jobs.map(job => <option key={job.id} value={job.id}>{job.title}</option>)}</select> : <button type="button" className="outline-button" onClick={() => setEditingJob(true)}><Pencil size={14}/>{selectedJob ? "更换职位" : "选择职位"}</button>}</div></section>
        <section><div className="flex items-center justify-between"><h3 className="section-title">筛选方案</h3><button type="button" className="outline-button" disabled={busy || !jobId} onClick={() => void savePlan()}><Save size={14}/>保存方案</button></div><p className="mt-1 text-xs text-[#7185a3]">本招聘任务只有一个筛选方案；保存会生成新版本，便于追溯。</p><div className="mt-3 grid gap-2 md:grid-cols-2">{dimensions.map((item, index) => <div key={item.name} className="rounded-lg bg-[#f6f9fd] px-3 py-2"><div className="grid grid-cols-[1fr_72px] items-center gap-2"><span><strong className="block text-xs text-[#36537e]">{item.name}{item.required && <i className="ml-1 text-[9px] not-italic text-[#d05d2f]">必须项</i>}</strong><small className="text-[10px] text-[#8191a8]">{item.description}</small></span><label className="text-xs"><input type="number" min={0} max={100} value={item.weight} onChange={event => patchDimension(index, { weight: Number(event.target.value) })} className="h-8 w-12 rounded border border-[#ccdaea] text-center"/>%</label></div><div className="mt-2 flex gap-2 text-[10px]"><label><input type="checkbox" checked={item.required} onChange={event => patchDimension(index, { required: event.target.checked })}/> 必须项</label><select value={item.missingPolicy} onChange={event => patchDimension(index, { missingPolicy: event.target.value as ScreeningDimension["missingPolicy"] })} className="h-7 rounded border border-[#d4dfeb] bg-white px-2"><option value="REVIEW">缺失时人工复核</option><option value="NEGOTIABLE">缺失时可协商</option><option value="IGNORE">缺失时忽略</option></select></div></div>)}</div><p className={`mb-0 mt-2 text-xs ${totalWeight === 100 ? "text-[#168573]" : "text-[#c14343]"}`}>权重合计：{totalWeight}%</p></section>
        <section><div className="flex flex-wrap items-center justify-between gap-3"><div><h3 className="section-title">候选人</h3><p className="mb-0 mt-1 text-xs text-[#7185a3]">默认选择人才库全部已解析候选人，可在弹窗中筛选范围。</p></div><button type="button" className="outline-button" onClick={() => setPickerOpen(true)}><UsersRound size={14}/>人才库筛选（已选 {selected.size} 人）</button></div>{showMockScenarios && <select value={scenario} onChange={event => setScenario(event.target.value)} className="field-control mt-3"><option value="NORMAL">正常筛选</option><option value="PARTIAL_FAILURE">模拟部分失败</option><option value="INVALID_SCHEMA">模拟非法结果</option></select>}<div className="mt-4 flex justify-between rounded-lg bg-[#f6f9fd] px-4 py-3 text-sm"><span>本次将筛选 {selected.size} 份简历</span><strong className="text-[#0c8c69]">预计 ¥{money(selected.size * (pricing?.unitPriceMinor || 0))}</strong></div><button type="button" className="primary-button mt-4 w-full justify-center" disabled={busy || !jobId || !selected.size || totalWeight !== 100} onClick={() => void start()}>{busy ? <Loader2 className="animate-spin" size={15}/> : <Filter size={15}/>}确认费用并开始筛选</button></section>
      </div>}</section>}
    {!resultMode && runs.length > 0 && <section className="mt-4 rounded-xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 text-sm text-[#173568]">历史筛选记录</h2><div className="mt-3 flex flex-wrap gap-2">{runs.slice(0, 8).map(run => <button key={run.id} type="button" onClick={() => void openRun(run.id)} className="rounded-lg border border-[#dce6f0] px-3 py-2 text-left text-xs"><strong className="block text-[#345277]">{run.jobTitle}</strong><span className="text-[#8292a8]">{run.succeededItems}/{run.totalItems} 成功</span></button>)}</div></section>}
    {pickerOpen && <CandidatePicker candidates={visibleCandidates} selected={selected} filter={candidateFilter} onFilter={setCandidateFilter} onToggle={toggleCandidate} onAll={() => setSelected(new Set(visibleCandidates.map(item => item.id)))} onClear={() => setSelected(new Set())} onClose={() => setPickerOpen(false)}/>}
  </>;
  return embedded ? body : <AppShell activeItem="简历筛选" pageHeader={<section className="flex flex-wrap items-end justify-between gap-3"><div><h1 className="m-0 text-[25px] font-bold text-[#09245d]">简历筛选</h1><p className="mb-0 mt-1 text-sm text-[#60799f]">批量调用 AI 评估候选人与职位的匹配程度</p></div><div className="flex items-center gap-2 rounded-lg border border-[#cfe4f5] bg-white px-3 py-2 text-xs text-[#53709a]"><CircleDollarSign size={16} className="text-[#0a9a66]"/>{pricing ? `临时价 ¥${money(pricing.unitPriceMinor)}/成功候选人` : "正在读取计价规则"}</div></section>}>{body}</AppShell>;
}

function ResultList({ result }: { result: ScreeningRun }) { return <div className="mt-4 space-y-3"><p className="text-xs text-[#7185a3]">{result.status === "RUNNING" ? `AI 正在筛选，当前进度 ${result.progress}%` : `实际结算 ¥${money(result.settledAmountMinor)}`}</p>{result.items.map(item => <article key={item.id} className="rounded-xl border border-[#dde8f2] p-4"><div className="flex justify-between gap-3"><div className="flex gap-3"><strong className="text-sm text-[#26466f]">{item.candidateName}</strong><ItemStatus status={item.status} errorCode={item.errorCode}/></div>{item.score !== null && <strong className="text-2xl text-[#1676d2]">{item.score}<small className="text-xs font-normal">/100</small></strong>}</div>{item.status === "SUCCEEDED" && <div className="mt-3 grid gap-3 md:grid-cols-2"><ResultGroup label="匹配点" values={item.matchedPoints}/><ResultGroup label="不符点" values={item.unmatchedPoints}/><ResultGroup label="可协商项" values={item.negotiablePoints}/><ResultGroup label="缺失信息" values={item.missingInformation}/><ResultGroup label="风险与提示" values={item.risks}/><ResultGroup label="证据" values={item.evidence}/></div>}</article>)}</div>; }
function CandidatePicker({ candidates, selected, filter, onFilter, onToggle, onAll, onClear, onClose }: { candidates: CandidateSummary[]; selected: Set<string>; filter: string; onFilter(value: string): void; onToggle(id: string): void; onAll(): void; onClear(): void; onClose(): void }) { return <div className="fixed inset-0 z-50 grid place-items-center bg-[#11274a]/35 p-4"><section className="flex max-h-[80vh] w-full max-w-2xl flex-col rounded-2xl bg-white p-5 shadow-2xl"><div className="flex justify-between"><div><h2 className="m-0 text-base text-[#173568]">从人才库选择候选人</h2><p className="mb-0 mt-1 text-xs">已选 {selected.size} 人</p></div><button type="button" className="outline-button" onClick={onClose}><X size={15}/>关闭</button></div><input value={filter} onChange={event => onFilter(event.target.value)} placeholder="按姓名、职位或技能筛选" className="field-control mt-4"/><div className="mt-3 flex gap-2"><button type="button" className="outline-button" onClick={onAll}>选择当前结果</button><button type="button" className="outline-button" onClick={onClear}>清空选择</button></div><div className="mt-3 min-h-0 flex-1 space-y-2 overflow-y-auto">{candidates.map(item => <label key={item.id} className="flex cursor-pointer items-center gap-3 rounded-lg border border-[#e2eaf3] p-3"><input type="checkbox" checked={selected.has(item.id)} onChange={() => onToggle(item.id)}/><span><strong className="block text-xs text-[#29486f]">{item.displayNameMasked}</strong><small className="text-[10px] text-[#8292a8]">{item.headline || item.skills.join("、")}</small></span></label>)}</div></section></div>; }
function ItemStatus({ status, errorCode }: { status: string; errorCode: string | null }) { if (status === "SUCCEEDED") return <span className="text-xs text-[#108760]"><CheckCircle2 className="inline" size={14}/>完成</span>; if (status === "PENDING" || status === "PROCESSING") return <span className="text-xs text-[#1676d2]">AI 处理中</span>; return <span className="text-xs text-[#c24b3f]">失败{errorCode ? ` · ${errorCode}` : ""}</span>; }
function ResultGroup({ label, values }: { label: string; values: string[] }) { return <div className="rounded-lg bg-[#f5f8fc] p-3 text-xs text-[#5f7492]"><strong>{label}</strong><p className="mb-0 mt-1">{values.length ? values.join("；") : "无"}</p></div>; }
function Loading({ text }: { text: string }) { return <div className="grid h-64 place-items-center text-sm text-[#7085a4]">{text}</div>; }
function PageState({ text }: { text: string }) { return <AppShell activeItem="简历筛选"><Loading text={text}/></AppShell>; }
function messageOf(cause: unknown) { return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试"; }
function money(value: number) { return (value / 100).toFixed(2); }
