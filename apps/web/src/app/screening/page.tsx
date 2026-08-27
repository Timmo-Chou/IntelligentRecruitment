"use client";

import { AlertCircle, CheckCircle2, CircleDollarSign, Filter, Loader2, RefreshCw, Save, Sparkles, Square, Target, UsersRound } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import { fetchCandidates, type CandidateSummary } from "@/lib/candidate-api";
import { fetchJobs, type Job } from "@/lib/job-api";
import { cancelScreeningRun, createRetryScreeningQuote, createScreeningPlan, createScreeningQuote, defaultScreeningDimensions, fetchScreeningPlans, fetchScreeningPricing, fetchScreeningRun, fetchScreeningRuns, retryFailedScreening, startScreeningRun, updateScreeningPlan, type ScreeningDimension, type ScreeningPlan, type ScreeningPricing, type ScreeningRun, type ScreeningRunSummary } from "@/lib/screening-api";
import { useWorkspace } from "@/lib/workspace-context";

const showMockScenarios = process.env.NEXT_PUBLIC_SHOW_AI_MOCK_SCENARIOS === "true";

export default function ScreeningPage() { return <ScreeningWorkspace />; }

function ScreeningWorkspace({ embedded = false }: { embedded?: boolean }) {
  const { workspaceId, workspace, loading: workspaceLoading, notAuthenticated } = useWorkspace();
  const [jobs, setJobs] = useState<Job[]>([]);
  const [candidates, setCandidates] = useState<CandidateSummary[]>([]);
  const [plans, setPlans] = useState<ScreeningPlan[]>([]);
  const [runs, setRuns] = useState<ScreeningRunSummary[]>([]);
  const [pricing, setPricing] = useState<ScreeningPricing | null>(null);
  const [jobId, setJobId] = useState("");
  const [planId, setPlanId] = useState("");
  const [dimensions, setDimensions] = useState<ScreeningDimension[]>(defaultScreeningDimensions);
  const [selectedCandidates, setSelectedCandidates] = useState<Set<string>>(new Set());
  const [scenario, setScenario] = useState("NORMAL");
  const [result, setResult] = useState<ScreeningRun | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const operationInFlight = useRef(false);
  const currentWorkspace = useRef<string | null>(null);

  const load = useCallback(async () => {
    if (!workspaceId) return;
    setLoading(true); setError(null);
    try {
      const [jobResult, candidateResult, planResult, runResult, pricingResult] = await Promise.all([
        fetchJobs(workspaceId, { page: 1, pageSize: 100 }), fetchCandidates(workspaceId, "", "PARSED"),
        fetchScreeningPlans(workspaceId), fetchScreeningRuns(workspaceId), fetchScreeningPricing(workspaceId),
      ]);
      if (currentWorkspace.current !== workspaceId) return;
      setJobs(jobResult.items); setCandidates(candidateResult.items); setPlans(planResult); setRuns(runResult); setPricing(pricingResult);
      setJobId(jobResult.items[0]?.id || "");
    } catch (cause) { setError(messageOf(cause)); }
    finally { setLoading(false); }
  }, [workspaceId]);

  useEffect(() => {
    currentWorkspace.current = workspaceId;
    const timer = window.setTimeout(() => {
      setJobs([]); setCandidates([]); setPlans([]); setRuns([]); setPricing(null);
      setJobId(""); setPlanId(""); setDimensions(defaultScreeningDimensions);
      setSelectedCandidates(new Set()); setResult(null); setError(null);
      void load();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [load, workspaceId]);
  useEffect(() => { if (notAuthenticated) window.location.replace("/login"); }, [notAuthenticated]);

  const jobPlans = plans.filter(plan => plan.jobId === jobId);

  async function handleCreatePlan() {
    if (!workspaceId || !jobId) return;
    setBusy(true); setError(null);
    try {
      const job = jobs.find(item => item.id === jobId);
      const created = await createScreeningPlan(workspaceId, { jobId, name: `${job?.title || "职位"}筛选方案`, dimensions });
      setPlans(await fetchScreeningPlans(workspaceId)); setPlanId(created.id); setDimensions(created.dimensions);
    } catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  async function handleSavePlan() {
    if (!workspaceId || !planId) return;
    setBusy(true); setError(null);
    try { const next = await updateScreeningPlan(workspaceId, planId, dimensions); setDimensions(next.dimensions); setPlans(await fetchScreeningPlans(workspaceId)); }
    catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  async function pollRun(targetWorkspaceId: string, runId: string) {
    for (let attempt = 0; attempt < 240; attempt += 1) {
      await new Promise(resolve => window.setTimeout(resolve, 500));
      if (currentWorkspace.current !== targetWorkspaceId) return;
      try {
        const next = await fetchScreeningRun(targetWorkspaceId, runId);
        if (currentWorkspace.current !== targetWorkspaceId) return;
        setResult(next);
        if (next.status !== "RUNNING") {
          setRuns(await fetchScreeningRuns(targetWorkspaceId));
          return;
        }
      } catch (cause) {
        if (currentWorkspace.current === targetWorkspaceId) setError(messageOf(cause));
        return;
      }
    }
    if (currentWorkspace.current === targetWorkspaceId) setError("筛选任务仍在执行，请稍后从任务历史中查看。");
  }

  async function handleRun() {
    if (!workspaceId || !planId || selectedCandidates.size === 0 || operationInFlight.current) return;
    operationInFlight.current = true;
    setBusy(true); setError(null);
    try {
      const candidateIds = Array.from(selectedCandidates);
      const quote = await createScreeningQuote(workspaceId, planId, candidateIds);
      if (quote.availableAmountMinor < quote.estimatedAmountMinor) {
        setError(`当前工作空间可用额度 ¥${money(quote.availableAmountMinor)}，不足以冻结 ¥${money(quote.estimatedAmountMinor)}。`);
        return;
      }
      const confirmed = window.confirm(`扣款工作空间：${workspace?.name}\n候选人：${quote.candidateCount} 人\n单价：¥${money(quote.unitPriceMinor)}/成功候选人\n预计冻结：¥${money(quote.estimatedAmountMinor)}\n可用余额：¥${money(quote.availableAmountMinor)}\n计价版本：${quote.pricingVersion}\n报价 ${Math.ceil((pricing?.quoteTtlSeconds || 300) / 60)} 分钟内有效，仅按成功结果结算。是否继续？`);
      if (!confirmed) return;
      const next = await startScreeningRun(workspaceId, planId, candidateIds, scenario, quote.id, crypto.randomUUID());
      setResult(next); setRuns(await fetchScreeningRuns(workspaceId)); void pollRun(workspaceId, next.id);
    } catch (cause) { setError(messageOf(cause)); }
    finally { operationInFlight.current = false; setBusy(false); }
  }

  async function openRun(runId: string) {
    if (!workspaceId) return;
    setBusy(true);
    try { setResult(await fetchScreeningRun(workspaceId, runId)); }
    catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  async function handleRetry() {
    if (!workspaceId || !result || operationInFlight.current) return;
    operationInFlight.current = true; setBusy(true); setError(null);
    try {
      const quote = await createRetryScreeningQuote(workspaceId, result.id);
      if (quote.availableAmountMinor < quote.estimatedAmountMinor) {
        setError(`当前工作空间可用额度 ¥${money(quote.availableAmountMinor)}，不足以冻结 ¥${money(quote.estimatedAmountMinor)}。`);
        return;
      }
      if (!window.confirm(`将按原职位、原简历解析和原筛选方案版本重试 ${quote.candidateCount} 个失败项。\n单价：¥${money(quote.unitPriceMinor)}/成功候选人\n预计冻结：¥${money(quote.estimatedAmountMinor)}\n仅成功项结算。是否继续？`)) return;
      const next = await retryFailedScreening(workspaceId, result.id, quote.id, crypto.randomUUID());
      setResult(next); setRuns(await fetchScreeningRuns(workspaceId)); void pollRun(workspaceId, next.id);
    }
    catch (cause) { setError(messageOf(cause)); }
    finally { operationInFlight.current = false; setBusy(false); }
  }

  async function handleCancel() {
    if (!workspaceId || !result || !window.confirm("确定取消该筛选任务吗？未处理候选人的冻结额度将释放。")) return;
    setBusy(true); setError(null);
    try { const next = await cancelScreeningRun(workspaceId, result.id, crypto.randomUUID()); setResult(next); setRuns(await fetchScreeningRuns(workspaceId)); }
    catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  function patchDimension(index: number, patch: Partial<ScreeningDimension>) {
    setDimensions(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item));
  }

  function choosePlan(value: string) {
    setPlanId(value);
    const plan = plans.find(item => item.id === value);
    if (plan) setDimensions(plan.dimensions);
  }

  function toggleCandidate(id: string) {
    setSelectedCandidates(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  }

  if (workspaceLoading) return embedded ? <div className="grid h-64 place-items-center text-sm text-[#7085a4]">正在加载工作空间...</div> : <State text="正在加载工作空间..."/>;
  if (!workspaceId) return embedded ? <div className="grid h-64 place-items-center text-sm text-[#7085a4]">请先进入一个可访问的工作空间</div> : <State text="请先进入一个可访问的工作空间"/>;

  const content = <>
    <section className="flex flex-wrap items-end justify-between gap-3"><div><h1 className="m-0 text-[25px] font-bold text-[#09245d]">简历筛选</h1><p className="mb-0 mt-1 text-sm text-[#60799f]">{workspace?.name} · 固定职位版本、解析版本和筛选规则后执行可解释匹配</p></div><div className="flex items-center gap-2 rounded-lg border border-[#cfe4f5] bg-white px-3 py-2 text-xs text-[#53709a]"><CircleDollarSign size={16} className="text-[#0a9a66]"/>{pricing ? `临时价 ¥${money(pricing.unitPriceMinor)}/成功候选人` : "正在读取计价规则"}</div></section>
    {error && <div className="mt-4 flex items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}
    {loading ? <div className="grid h-64 place-items-center"><Loader2 className="animate-spin text-[#1784d8]"/></div> : <div className="mt-4 grid gap-4 xl:grid-cols-[420px_minmax(0,1fr)]">
      <aside className="space-y-4">
        <Card title="1. 选择职位与方案" icon={<Target/>}>
          <label className="field-label">职位<select value={jobId} onChange={(event) => { setJobId(event.target.value); setPlanId(""); setDimensions(defaultScreeningDimensions); }} className="field-control"><option value="">请选择职位</option>{jobs.map(job => <option key={job.id} value={job.id}>{job.title}</option>)}</select></label>
          <label className="field-label mt-3">筛选方案<select value={planId} onChange={(event) => choosePlan(event.target.value)} className="field-control"><option value="">新建筛选方案</option>{jobPlans.map(plan => <option key={plan.id} value={plan.id}>{plan.name} · v{plan.versionNumber}</option>)}</select></label>
          <div className="mt-3 space-y-2">{dimensions.map((dimension, index) => <div key={dimension.name} className="rounded-lg bg-[#f6f9fd] px-3 py-2"><div className="grid grid-cols-[1fr_70px] items-center gap-2"><span><strong className="block text-xs text-[#36537e]">{dimension.name}{dimension.required && <i className="ml-1 text-[9px] not-italic text-[#d05d2f]">必须项</i>}</strong><small className="text-[10px] text-[#8191a8]">{dimension.description}</small></span><label className="flex items-center gap-1 text-xs text-[#62799a]"><input type="number" min={0} max={100} value={dimension.weight} onChange={(event) => setDimensions(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, weight: Number(event.target.value) } : item))} className="h-8 w-12 rounded border border-[#ccdaea] text-center"/>%</label></div><div className="mt-2 flex gap-2"><label className="flex items-center gap-1 text-[10px] text-[#60799f]"><input type="checkbox" checked={dimension.required} onChange={(event) => patchDimension(index, { required: event.target.checked })}/>必须项</label><select value={dimension.missingPolicy || "REVIEW"} onChange={(event) => patchDimension(index, { missingPolicy: event.target.value as ScreeningDimension["missingPolicy"] })} className="h-7 rounded border border-[#d4dfeb] bg-white px-2 text-[10px] text-[#60799f]"><option value="REVIEW">缺失时人工复核</option><option value="NEGOTIABLE">缺失时可协商</option><option value="IGNORE">缺失时忽略</option></select></div><input value={dimension.exclusionRule || ""} onChange={(event) => patchDimension(index, { exclusionRule: event.target.value })} placeholder="可选排除项（不得使用性别、年龄、婚育等属性）" className="mt-2 h-7 w-full rounded border border-[#d4dfeb] bg-white px-2 text-[10px] text-[#60799f] outline-none"/></div>)}</div>
          <p className={`mb-0 mt-2 text-xs ${dimensions.reduce((sum, item) => sum + item.weight, 0) === 100 ? "text-[#168573]" : "text-[#c14343]"}`}>权重合计：{dimensions.reduce((sum, item) => sum + item.weight, 0)}%</p>
          <button type="button" className="primary-button mt-3 w-full justify-center" disabled={busy || !jobId} onClick={() => void (planId ? handleSavePlan() : handleCreatePlan())}>{planId ? <Save size={15}/> : <Sparkles size={15}/>} {planId ? "保存为新版本" : "生成筛选方案"}</button>
        </Card>
        <Card title="2. 选择候选人" icon={<UsersRound/>}>
          {candidates.length === 0 ? <p className="text-xs leading-6 text-[#7185a3]">当前工作空间还没有已解析候选人，请先到人才库上传简历。</p> : <div className="max-h-56 space-y-2 overflow-y-auto">{candidates.map(candidate => <label key={candidate.id} className="flex cursor-pointer items-center gap-3 rounded-lg border border-[#e2eaf3] p-3 hover:bg-[#f7fbff]"><input type="checkbox" checked={selectedCandidates.has(candidate.id)} onChange={() => toggleCandidate(candidate.id)}/><span className="min-w-0"><strong className="block text-xs text-[#29486f]">{candidate.displayNameMasked}</strong><small className="block truncate text-[10px] text-[#8292a8]">{candidate.headline}</small></span></label>)}</div>}
          <div className="mt-3 flex items-center justify-between text-xs text-[#60799f]"><span>已选 {selectedCandidates.size} 人</span><strong className="text-[#0c8c69]">预计 ¥{money(selectedCandidates.size * (pricing?.unitPriceMinor || 0))}</strong></div>
          {showMockScenarios && <select value={scenario} onChange={(event) => setScenario(event.target.value)} className="field-control mt-3"><option value="NORMAL">正常筛选</option><option value="PARTIAL_FAILURE">模拟部分失败</option><option value="INVALID_SCHEMA">模拟非法结果</option></select>}
          <button type="button" className="primary-button mt-3 w-full justify-center" disabled={busy || !planId || selectedCandidates.size === 0} onClick={() => void handleRun()}>{busy ? <Loader2 className="animate-spin" size={15}/> : <Filter size={15}/>}确认费用并开始筛选</button>
        </Card>
      </aside>

      <main className="min-w-0 space-y-4">
        <section className="rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
          <div className="flex items-center justify-between"><div><h2 className="m-0 text-base text-[#173568]">筛选结果</h2>{result && <p className="mb-0 mt-1 text-xs text-[#7185a3]">{result.jobTitle} · {result.planName} · {result.status === "RUNNING" ? `执行中 ${result.progress}%` : `实际结算 ¥${money(result.settledAmountMinor)}`}</p>}</div><div className="flex gap-2">{result?.status === "RUNNING" && <button type="button" className="outline-button" disabled={busy} onClick={() => void handleCancel()}><Square size={13}/>取消任务</button>}{result?.status !== "RUNNING" && result?.items.some(item => item.status === "FAILED") && <button type="button" className="outline-button" disabled={busy} onClick={() => void handleRetry()}><RefreshCw size={14}/>重试失败项</button>}</div></div>
          {!result ? <div className="flex h-[420px] flex-col items-center justify-center text-center"><span className="grid h-14 w-14 place-items-center rounded-2xl bg-[#eaf8f5] text-[#169b83]"><Target size={27}/></span><h3 className="mb-0 mt-4 text-lg text-[#193866]">选择方案和候选人开始筛选</h3><p className="mt-2 text-sm text-[#7185a3]">结果会展示匹配点、不符点、可协商项、缺失信息、风险和证据。</p></div> : <div className="mt-4 space-y-3">{result.items.map(item => <article key={item.id} className="rounded-xl border border-[#dde8f2] p-4"><div className="flex flex-wrap items-center justify-between gap-3"><div className="flex items-center gap-3"><strong className="text-sm text-[#26466f]">{item.candidateName}</strong><ItemStatus status={item.status} errorCode={item.errorCode}/></div>{item.score !== null && <strong className={`text-2xl ${item.score >= 85 ? "text-[#07945f]" : item.score >= 70 ? "text-[#1676d2]" : "text-[#d17a20]"}`}>{item.score}<small className="text-xs font-normal">/100</small></strong>}</div>{item.status === "SUCCEEDED" && <><div className="mt-3 grid gap-3 md:grid-cols-2"><ResultGroup label="匹配点" values={item.matchedPoints} tone="good"/><ResultGroup label="不符点" values={item.unmatchedPoints} tone="bad"/><ResultGroup label="可协商项" values={item.negotiablePoints}/><ResultGroup label="缺失信息" values={item.missingInformation}/><ResultGroup label="风险与提示" values={item.risks}/><ResultGroup label="证据" values={item.evidence}/></div><p className="mb-0 mt-3 text-[10px] text-[#8a6e37]">AI 结果仅供辅助，不能自动淘汰、拒绝或替代招聘人员最终判断。</p></>}</article>)}</div>}
        </section>
        {runs.length > 0 && <section className="rounded-xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 text-sm text-[#173568]">最近筛选任务</h2><div className="mt-3 flex flex-wrap gap-2">{runs.slice(0, 8).map(run => <button key={run.id} type="button" onClick={() => void openRun(run.id)} className="rounded-lg border border-[#dce6f0] px-3 py-2 text-left text-xs hover:bg-[#f5faff]"><strong className="block text-[#345277]">{run.jobTitle}</strong><span className="text-[#8292a8]">{run.succeededItems}/{run.totalItems} 成功 · ¥{(run.settledAmountMinor / 100).toFixed(2)}</span></button>)}</div></section>}
      </main>
    </div>}
  </>;
  return embedded ? content : <AppShell activeItem="简历筛选">{content}</AppShell>;
}

function Card({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) { return <section className="rounded-xl border border-[#d6e5f5] bg-white p-4 shadow-[0_6px_20px_rgba(30,92,160,0.04)]"><h2 className="m-0 flex items-center gap-2 text-sm text-[#173568]"><span className="text-[#13977e]">{icon}</span>{title}</h2><div className="mt-4">{children}</div></section>; }
function ItemStatus({ status, errorCode }: { status: string; errorCode: string | null }) {
  if (status === "SUCCEEDED") return <span className="flex items-center gap-1 text-xs text-[#108760]"><CheckCircle2 size={14}/>完成</span>;
  if (status === "PENDING") return <span className="flex items-center gap-1 text-xs text-[#1676d2]"><Loader2 className="animate-spin" size={13}/>等待处理</span>;
  if (status === "CANCELLED") return <span className="text-xs text-[#7185a3]">已取消</span>;
  return <span className="text-xs text-[#c24b3f]">失败{errorCode ? ` · ${errorCode}` : ""}</span>;
}
function ResultGroup({ label, values, tone }: { label: string; values: string[]; tone?: "good" | "bad" }) { return <div className={`rounded-lg p-3 text-xs ${tone === "good" ? "bg-[#edf9f5] text-[#24745f]" : tone === "bad" ? "bg-[#fff5f2] text-[#9a574b]" : "bg-[#f5f8fc] text-[#5f7492]"}`}><strong>{label}</strong><p className="mb-0 mt-1 leading-5">{values.length ? values.join("；") : "无"}</p></div>; }
function State({ text }: { text: string }) { return <AppShell activeItem="简历筛选"><div className="grid h-64 place-items-center text-sm text-[#7085a4]">{text}</div></AppShell>; }
function messageOf(cause: unknown) { return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试"; }
function money(value: number) { return (value / 100).toFixed(2); }
