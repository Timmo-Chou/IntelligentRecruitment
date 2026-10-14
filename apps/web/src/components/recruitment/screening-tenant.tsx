"use client";

import { AlertCircle, CheckCircle2, Filter, Loader2, Pencil, RefreshCw, Square, Target, UsersRound, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import { fetchCandidates, type CandidateSummary } from "@/lib/candidate-api";
import { fetchJobs, type Job } from "@/lib/job-api";
import { cancelScreeningRun, fetchScreeningPlans, fetchScreeningRun, fetchScreeningRuns, retryFailedScreening, startScreeningRun, type ScreeningDimension, type ScreeningPlan, type ScreeningRun, type ScreeningRunSummary } from "@/lib/screening-api";
import { useTenant } from "@/lib/tenant-context";
import { getDisplayLabel, getSafeErrorMessage } from "@intelligent-recruitment/display-dictionary";
import { screeningRunSettlementSummary } from "@/lib/screening-run-display";

type ScreeningTenantProps = { embedded?: boolean; recruitmentTaskId?: string; initialJobId?: string | null };

export function ScreeningTenant(props: ScreeningTenantProps) {
  const { tenantId } = useTenant();
  return <ScreeningTenantContent key={tenantId ?? "no-tenant"} {...props}/>;
}

function ScreeningTenantContent({ embedded = false, recruitmentTaskId, initialJobId }: ScreeningTenantProps) {
  const { tenantId, loading: tenantLoading, notAuthenticated } = useTenant();
  const [jobs, setJobs] = useState<Job[]>([]), [candidates, setCandidates] = useState<CandidateSummary[]>([]);
  const [plans, setPlans] = useState<ScreeningPlan[]>([]), [runs, setRuns] = useState<ScreeningRunSummary[]>([]);
  const [jobId, setJobId] = useState(""), [planId, setPlanId] = useState("");
  const [dimensions, setDimensions] = useState<ScreeningDimension[]>([]), [selected, setSelected] = useState<Set<string>>(new Set());
  const [result, setResult] = useState<ScreeningRun | null>(null), [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [error, setError] = useState<string | null>(null);
  const [editingJob, setEditingJob] = useState(false), [pickerOpen, setPickerOpen] = useState(false), [candidateFilter, setCandidateFilter] = useState("");
  const tenantRef = useRef<string | null>(null), selectedInitialised = useRef(false), inFlight = useRef(false), preferredJobId = useRef(initialJobId || "");

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true); setError(null);
    try {
      const [jobData, candidateData, planData, runData] = await Promise.all([
        fetchJobs(tenantId, { page: 1, pageSize: 100 }),
        fetchCandidates(tenantId, { status: "PARSED", pageSize: 200 }),
        fetchScreeningPlans(tenantId, recruitmentTaskId), fetchScreeningRuns(tenantId, recruitmentTaskId),
      ]);
      if (tenantRef.current !== tenantId) return;
      const nextJobId = preferredJobId.current || planData[0]?.jobId || jobData.items[0]?.id || "";
      preferredJobId.current = nextJobId;
      const plan = planData.find(item => item.jobId === nextJobId) ?? null;
      setJobs(jobData.items); setCandidates(candidateData.items); setPlans(planData); setRuns(runData);
      setJobId(nextJobId);
      setPlanId(plan?.id || ""); setDimensions(plan?.dimensions || []);
      if (!selectedInitialised.current) { selectedInitialised.current = true; setSelected(new Set()); }
    } catch (cause) {
      setJobs([]); setCandidates([]); setPlans([]); setRuns([]); setJobId(""); setPlanId("");
      setDimensions([]); setSelected(new Set()); setResult(null); setError(messageOf(cause));
    } finally { setLoading(false); }
  }, [tenantId, recruitmentTaskId]);

  useEffect(() => { tenantRef.current = tenantId; selectedInitialised.current = false; void Promise.resolve().then(load); }, [tenantId, load]);
  useEffect(() => { if (notAuthenticated) window.location.replace("/login"); }, [notAuthenticated]);
  const selectedJob = jobs.find(job => job.id === jobId), resultMode = result !== null;
  const activePlan = plans.find(item => item.id === planId && item.jobId === jobId) ?? null;
  const visibleCandidates = candidates.filter(item => `${item.displayNameMasked} ${item.headline} ${item.skills.join(" ")}`.toLowerCase().includes(candidateFilter.toLowerCase()));
  const totalWeight = dimensions.reduce((sum, item) => sum + item.weight, 0);

  function chooseJob(nextJobId: string) {
    preferredJobId.current = nextJobId;
    setJobId(nextJobId);
    const plan = plans.find(item => item.jobId === nextJobId) ?? null;
    setPlanId(plan?.id || ""); setDimensions(plan?.dimensions || []);
  }
  function toggleCandidate(id: string) { setSelected(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; }); }
  async function ensurePlan() {
    if (!tenantId || !jobId) throw new Error("请先选择职位");
    if(!activePlan)throw new Error("该职位尚未配置筛选方案，请联系运营管理员");
    return activePlan;
  }
  async function poll(runId: string) {
    if (!tenantId) return;
    for (let i = 0; i < 240; i += 1) {
      await new Promise(resolve => window.setTimeout(resolve, 500));
      if (tenantRef.current !== tenantId) return;
      const next = await fetchScreeningRun(tenantId, runId); setResult(next);
      if (next.status !== "RUNNING") { setRuns(await fetchScreeningRuns(tenantId, recruitmentTaskId)); return; }
    }
  }
  async function start() {
    if (!tenantId || !jobId || !selected.size || inFlight.current) return;
    inFlight.current = true; setBusy(true); setError(null);
    try {
      const plan = await ensurePlan(), ids = [...selected];
      const run = await startScreeningRun(tenantId, plan.id, ids, crypto.randomUUID());
      setResult(run); setRuns(await fetchScreeningRuns(tenantId, recruitmentTaskId)); void poll(run.id);
    } catch (cause) { setError(messageOf(cause)); } finally { inFlight.current = false; setBusy(false); }
  }
  async function openRun(id: string) { if (!tenantId) return; setBusy(true); try { setResult(await fetchScreeningRun(tenantId, id)); } catch (cause) { setError(messageOf(cause)); } finally { setBusy(false); } }
  async function retry() {
    if (!tenantId || !result) return; setBusy(true);
    try { const next = await retryFailedScreening(tenantId, result.id, crypto.randomUUID()); setResult(next); void poll(next.id); }
    catch (cause) { setError(messageOf(cause)); } finally { setBusy(false); }
  }
  async function cancel() { if (!tenantId || !result || !window.confirm("确定取消该筛选任务吗？")) return; setBusy(true); try { setResult(await cancelScreeningRun(tenantId, result.id, crypto.randomUUID())); } catch (cause) { setError(messageOf(cause)); } finally { setBusy(false); } }

  if (tenantLoading) return embedded ? <Loading text="正在加载工作空间..."/> : <PageState text="正在加载工作空间..."/>;
  if (!tenantId) return embedded ? <Loading text="请先进入一个可访问的工作空间"/> : <PageState text="请先进入一个可访问的工作空间"/>;
  const body = <>{error && <div className="flex flex-wrap items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}
    {loading ? <Loading text="正在读取筛选数据..."/> : <section className="mt-4 rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#e4edf6] pb-4"><div><h2 className="m-0 flex items-center gap-2 text-base text-[#173568]"><Target className="text-[#13977e]" size={18}/>{resultMode ? "筛选结果" : "简历筛选"}</h2><p className="mb-0 mt-1 text-xs text-[#7185a3]">{selectedJob ? `${selectedJob.title} · ${selectedJob.location || "工作地点待确认"} · ${selectedJob.experienceLevel || "经验待确认"}` : "请确认本次筛选对应的职位"}</p></div>
        {resultMode && <div className="flex gap-2">{result?.status === "RUNNING" && <button type="button" className="outline-button" onClick={() => void cancel()} disabled={busy}><Square size={13}/>取消任务</button>}{result?.status !== "RUNNING" && result?.items.some(item => item.status === "FAILED") && <button type="button" className="outline-button" onClick={() => void retry()} disabled={busy}><RefreshCw size={14}/>重试失败项</button>}{result?.status !== "RUNNING" && <button type="button" className="primary-button" onClick={() => setResult(null)}><Pencil size={14}/>重新选择候选人</button>}</div>}</div>
      {resultMode ? <ResultList result={result!}/> : <div className="mt-5 space-y-6">
        <section><h3 className="section-title">已选职位</h3><div className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-[#f6f9fd] px-4 py-3"><div><strong className="text-sm text-[#29486f]">{selectedJob?.title || "尚未选择职位"}</strong><p className="mb-0 mt-1 text-xs text-[#7185a3]">{selectedJob ? `${selectedJob.companyName} · ${selectedJob.skills || "技能待确认"}` : "从职位库选择本次筛选的职位"}</p></div>{editingJob ? <select autoFocus value={jobId} onChange={event => { chooseJob(event.target.value); setEditingJob(false); }} className="field-control w-56"><option value="">请选择职位</option>{jobs.map(job => <option key={job.id} value={job.id}>{job.title}</option>)}</select> : <button type="button" className="outline-button" onClick={() => setEditingJob(true)}><Pencil size={14}/>{selectedJob ? "更换职位" : "选择职位"}</button>}</div></section>
        <section><div className="flex items-center justify-between"><h3 className="section-title">筛选方案</h3><span className="text-xs text-[#7185a3]">由运营管理员维护</span></div>{activePlan ? <><p className="mt-1 text-xs text-[#7185a3]">当前方案：{activePlan.name} · 第 {activePlan.versionNumber} 版。</p><div className="mt-3 grid gap-2 md:grid-cols-2">{dimensions.map(item => <div key={item.name} className="rounded-lg bg-[#f6f9fd] px-3 py-2"><div><strong className="block text-xs text-[#36537e]">{item.name}{item.required && <i className="ml-1 text-[9px] not-italic text-[#d05d2f]">必须项</i>}</strong><small className="text-[10px] text-[#8191a8]">{item.description}</small></div><p className="mb-0 mt-2 text-xs">权重 {item.weight}% · {item.missingPolicy === "REVIEW" ? "缺失时人工复核" : item.missingPolicy === "NEGOTIABLE" ? "缺失时可协商" : "缺失时忽略"}</p></div>)}</div><p className="mb-0 mt-2 text-xs text-[#168573]">权重合计：{totalWeight}%</p></> : <p className="mt-2 rounded-lg bg-[#fff7ed] px-3 py-2 text-sm text-[#9a5b13]">该职位没有可用的运营筛选方案，暂不能开始筛选。</p>}</section>
        <section><div className="flex flex-wrap items-center justify-between gap-3"><div><h3 className="section-title">候选人</h3><p className="mb-0 mt-1 text-xs text-[#7185a3]">请明确选择本次要筛选的候选人。</p></div><button type="button" className="outline-button" onClick={() => setPickerOpen(true)}><UsersRound size={14}/>人才库筛选（已选 {selected.size} 人）</button></div><div className="mt-4 rounded-lg bg-[#f6f9fd] px-4 py-3 text-sm">本次将筛选 {selected.size} 份简历，所需积分将由企业共享积分池按实际智能调用扣除。</div><button type="button" className="primary-button mt-4 w-full justify-center" disabled={busy || !activePlan || !jobId || !selected.size || totalWeight !== 100} onClick={() => void start()}>{busy ? <Loader2 className="animate-spin" size={15}/> : <Filter size={15}/>}开始筛选</button></section>
      </div>}</section>}
    {!resultMode && runs.length > 0 && <section className="mt-4 rounded-xl border border-[#d6e5f5] bg-white p-5"><h2 className="m-0 text-sm text-[#173568]">历史筛选记录</h2><div className="mt-3 flex flex-wrap gap-2">{runs.slice(0, 8).map(run => <button key={run.id} type="button" onClick={() => void openRun(run.id)} className="rounded-lg border border-[#dce6f0] px-3 py-2 text-left text-xs"><strong className="block text-[#345277]">{run.jobTitle}</strong><span className="text-[#8292a8]">{run.succeededItems}/{run.totalItems} 成功</span></button>)}</div></section>}
    {pickerOpen && <CandidatePicker candidates={visibleCandidates} selected={selected} filter={candidateFilter} onFilter={setCandidateFilter} onToggle={toggleCandidate} onAll={() => setSelected(new Set(visibleCandidates.map(item => item.id)))} onClear={() => setSelected(new Set())} onClose={() => setPickerOpen(false)}/>}
  </>;
  return embedded ? body : <AppShell activeItem="简历筛选" pageHeader={<section><h1 className="m-0 text-[25px] font-bold text-[#09245d]">简历筛选</h1><p className="mb-0 mt-1 text-sm text-[#60799f]">批量调用 智能评估候选人与职位的匹配程度</p></section>}>{body}</AppShell>;
}

function ResultList({ result }: { result: ScreeningRun }) {
  const summary = screeningRunSettlementSummary(result);
  return <div className="mt-4 space-y-3"><p className="text-xs text-[#7185a3]">{summary}</p>{result.items.map(item => <article key={item.id} className="rounded-xl border border-[#dde8f2] p-4"><div className="flex justify-between gap-3"><div className="flex gap-3"><strong className="text-sm text-[#26466f]">{item.candidateName}</strong><ItemStatus status={item.status} errorCode={item.errorCode}/></div>{item.score !== null && <strong className="text-2xl text-[#1676d2]">{item.score}<small className="text-xs font-normal">/100</small></strong>}</div>{["SUCCEEDED","HARD_FILTERED","NOT_EVALUATED","RECONCILIATION_REQUIRED"].includes(item.status) && <div className="mt-3 grid gap-3 md:grid-cols-2"><ResultGroup label="匹配点" values={item.matchedPoints}/><ResultGroup label="不符点" values={item.unmatchedPoints}/><ResultGroup label="可协商项" values={item.negotiablePoints}/><ResultGroup label="缺失信息" values={item.missingInformation}/><ResultGroup label="风险与提示" values={item.risks}/><EvidenceGroup values={item.evidence}/></div>}</article>)}</div>;
}
function CandidatePicker({ candidates, selected, filter, onFilter, onToggle, onAll, onClear, onClose }: { candidates: CandidateSummary[]; selected: Set<string>; filter: string; onFilter(value: string): void; onToggle(id: string): void; onAll(): void; onClear(): void; onClose(): void }) { return <div className="fixed inset-0 z-50 grid place-items-center bg-[#11274a]/35 p-4"><section className="flex max-h-[80vh] w-full max-w-2xl flex-col rounded-2xl bg-white p-5 shadow-2xl"><div className="flex justify-between"><div><h2 className="m-0 text-base text-[#173568]">从人才库选择候选人</h2><p className="mb-0 mt-1 text-xs">已选 {selected.size} 人</p></div><button type="button" className="outline-button" onClick={onClose}><X size={15}/>关闭</button></div><input value={filter} onChange={event => onFilter(event.target.value)} placeholder="按姓名、职位或技能筛选" className="field-control mt-4"/><div className="mt-3 flex gap-2"><button type="button" className="outline-button" onClick={onAll}>选择当前结果</button><button type="button" className="outline-button" onClick={onClear}>清空选择</button></div><div className="mt-3 min-h-0 flex-1 space-y-2 overflow-y-auto">{candidates.map(item => <label key={item.id} className="flex cursor-pointer items-center gap-3 rounded-lg border border-[#e2eaf3] p-3"><input type="checkbox" checked={selected.has(item.id)} onChange={() => onToggle(item.id)}/><span><strong className="block text-xs text-[#29486f]">{item.displayNameMasked}</strong><small className="text-[10px] text-[#8292a8]">{item.headline || item.skills.join("、")}</small></span></label>)}</div></section></div>; }
function ItemStatus({ status, errorCode }: { status: string; errorCode: string | null }) { const label = getDisplayLabel("screening.itemStatus", status, "筛选状态暂未识别"); if (status === "SUCCEEDED") return <span className="text-xs text-[#108760]"><CheckCircle2 className="inline" size={14}/>{label}</span>; if (status === "HARD_FILTERED" || status === "NOT_EVALUATED" || status === "RECONCILIATION_REQUIRED") return <span className="text-xs text-[#9a6b00]">{label}</span>; if (status === "CANCELLED") return <span className="text-xs text-[#7185a3]">{label}</span>; if (status === "PENDING" || status === "PROCESSING") return <span className="text-xs text-[#1676d2]">{label}</span>; return <span className="text-xs text-[#c24b3f]">{status === "FAILED" ? `失败${errorCode ? ` · ${getDisplayLabel("error.common", errorCode, "系统暂时无法完成操作")}` : ""}` : label}</span>; }
function ResultGroup({ label, values }: { label: string; values: string[] }) { return <div className="rounded-lg bg-[#f5f8fc] p-3 text-xs text-[#5f7492]"><strong>{label}</strong><p className="mb-0 mt-1">{values.length ? values.join("；") : "无"}</p></div>; }
function EvidenceGroup({ values }: { values: ScreeningRun["items"][number]["evidence"] }) { return <div className="rounded-lg bg-[#f5f8fc] p-3 text-xs text-[#5f7492]"><strong>证据</strong><ul className="mb-0 mt-1 list-disc space-y-1 pl-4">{values.map((value, index) => <li key={`${value.sourceRef}-${index}`}>{value.excerpt || "来源记录"}{value.section ? ` · ${value.section}` : ""}{value.page ? ` · 第 ${value.page} 页` : ""}</li>)}{values.length === 0 && <li className="list-none pl-0">当前没有可展示的摘录</li>}</ul></div>; }
function Loading({ text }: { text: string }) { return <div className="grid h-64 place-items-center text-sm text-[#7085a4]">{text}</div>; }
function PageState({ text }: { text: string }) { return <AppShell activeItem="简历筛选"><Loading text={text}/></AppShell>; }
function messageOf(cause: unknown) { return cause instanceof ApiError ? getSafeErrorMessage(cause.body.code, cause.body.request_id) : "操作暂未完成，请稍后重试。"; }
