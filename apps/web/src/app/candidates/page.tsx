"use client";

import { AlertCircle, Download, Eye, FileText, Loader2, RefreshCw, Search, ShieldCheck, Trash2, Upload, UserRound, UsersRound, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import { deleteCandidate, downloadResume, fetchCandidate, fetchCandidates, revealCandidate, retryResumeParse, uploadResume, type CandidateDetail, type CandidateSummary, type RevealedPii } from "@/lib/candidate-api";
import { useWorkspace } from "@/lib/workspace-context";

export default function CandidatesPage() { return <CandidatesWorkspace />; }

function CandidatesWorkspace({ embedded = false }: { embedded?: boolean }) {
  const { workspaceId, workspace, loading: workspaceLoading, notAuthenticated } = useWorkspace();
  const [items, setItems] = useState<CandidateSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<CandidateDetail | null>(null);
  const [revealed, setRevealed] = useState<RevealedPii | null>(null);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const [scenario, setScenario] = useState("NORMAL");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploadReport, setUploadReport] = useState<{ name: string; status: "SUCCESS" | "FAILED"; message: string }[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    if (!workspaceId) return;
    setLoading(true); setError(null);
    try {
      const result = await fetchCandidates(workspaceId, search, status);
      setItems(result.items); setTotal(result.total);
    } catch (cause) { setError(messageOf(cause)); }
    finally { setLoading(false); }
  }, [workspaceId, search, status]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);
  useEffect(() => { if (notAuthenticated) window.location.replace("/login"); }, [notAuthenticated]);

  async function handleFiles(files: FileList | null) {
    if (!workspaceId || !files?.length) return;
    setBusy(true); setError(null); setUploadReport([]);
    try {
      const selectedFiles = Array.from(files);
      const results = await Promise.allSettled(selectedFiles.map(file => uploadResume(workspaceId, file, scenario)));
      const report = results.map((result, index) => ({
        name: selectedFiles[index]?.name || "未知文件",
        status: result.status === "fulfilled" ? "SUCCESS" as const : "FAILED" as const,
        message: result.status === "fulfilled" ? (result.value.parseStatus === "PARSED" ? "解析完成" : "已上传，解析失败") : messageOf(result.reason),
      }));
      setUploadReport(report);
      await load();
      const successful = results.filter((result): result is PromiseFulfilledResult<CandidateDetail> => result.status === "fulfilled");
      const last = successful[successful.length - 1];
      if (last) setSelected(last.value);
      if (report.some(item => item.status === "FAILED")) setError("部分文件上传失败，其他成功文件已保留。请查看文件级结果。");
    } catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); if (inputRef.current) inputRef.current.value = ""; }
  }

  async function openDetail(candidateId: string) {
    if (!workspaceId) return;
    setBusy(true); setRevealed(null);
    try { setSelected(await fetchCandidate(workspaceId, candidateId)); }
    catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  async function handleReveal() {
    if (!workspaceId || !selected) return;
    setBusy(true);
    try { setRevealed(await revealCandidate(workspaceId, selected.id)); }
    catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  async function handleRetry() {
    if (!workspaceId || !selected) return;
    setBusy(true);
    try { const next = await retryResumeParse(workspaceId, selected.id); setSelected(next); await load(); }
    catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  async function handleDelete() {
    if (!workspaceId || !selected || !window.confirm("确定删除该候选人及原简历吗？已产生的最小审计记录仍会保留。")) return;
    setBusy(true);
    try { await deleteCandidate(workspaceId, selected.id); setSelected(null); setRevealed(null); await load(); }
    catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  if (workspaceLoading) return embedded ? <div className="grid h-64 place-items-center text-sm text-[#7085a4]">正在加载工作空间...</div> : <State text="正在加载工作空间..."/>;
  if (!workspaceId) return embedded ? <div className="grid h-64 place-items-center text-sm text-[#7085a4]">请先登录并进入一个可访问的工作空间</div> : <State text="请先登录并进入一个可访问的工作空间"/>;

  const content = <>
    <section className="flex flex-wrap items-end justify-between gap-3">
      <div><h1 className="m-0 text-[25px] font-bold text-[#09245d]">人才库</h1><p className="mb-0 mt-1 text-sm text-[#60799f]">{workspace?.name} · 简历文件、解析版本和候选人信息严格限定当前工作空间</p></div>
      <div className="flex items-center gap-2">
        <select value={scenario} onChange={(event) => setScenario(event.target.value)} className="h-10 rounded-lg border border-[#cfdeed] bg-white px-3 text-xs text-[#516c94]"><option value="NORMAL">正常解析</option><option value="INVALID_SCHEMA">模拟解析失败</option></select>
        <input ref={inputRef} type="file" multiple accept=".pdf,.docx" className="hidden" onChange={(event) => void handleFiles(event.target.files)}/>
        <button type="button" className="primary-button" disabled={busy} onClick={() => inputRef.current?.click()}>{busy ? <Loader2 size={16} className="animate-spin"/> : <Upload size={16}/>}上传简历</button>
      </div>
    </section>
    {error && <div className="mt-4 flex items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}
    {uploadReport.length > 0 && <section className="mt-4 rounded-xl border border-[#d7e6f3] bg-white p-3"><div className="flex flex-wrap gap-2">{uploadReport.map(item => <span key={item.name} className={`rounded-full px-3 py-1 text-[11px] ${item.status === "SUCCESS" ? "bg-[#e5f8f1] text-[#15785f]" : "bg-[#fff0ed] text-[#a64c40]"}`}>{item.name} · {item.message}</span>)}</div></section>}

    <button type="button" onClick={() => inputRef.current?.click()} onDragOver={(event) => event.preventDefault()} onDrop={(event) => { event.preventDefault(); void handleFiles(event.dataTransfer.files); }} className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl border border-dashed border-[#a9cee2] bg-[#f7fcff] px-4 py-3 text-xs text-[#52779a] transition hover:bg-[#eff9fd]"><Upload size={15}/>拖拽多份 PDF / DOCX 到这里，或点击选择文件（单文件最大 10MB）</button>

    <section className="mt-4 grid gap-3 sm:grid-cols-3">
      <Metric icon={<UsersRound/>} label="候选人总数" value={total}/><Metric icon={<ShieldCheck/>} label="已解析" value={items.filter(item => item.parseStatus === "PARSED").length}/><Metric icon={<AlertCircle/>} label="解析异常" value={items.filter(item => item.parseStatus === "PARSE_FAILED").length}/>
    </section>

    <div className="mt-4 grid min-h-[610px] gap-4 xl:grid-cols-[minmax(0,1fr)_390px]">
      <section className="rounded-xl border border-[#d6e5f5] bg-white shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        <div className="flex flex-wrap items-center gap-3 border-b border-[#e3ebf4] p-4">
          <label className="flex h-10 min-w-[240px] flex-1 items-center gap-2 rounded-lg border border-[#d1dfed] px-3 text-[#7185a3]"><Search size={16}/><input value={search} onChange={(event) => setSearch(event.target.value)} className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none" placeholder="搜索姓名、技能或人才画像"/></label>
          <select value={status} onChange={(event) => setStatus(event.target.value)} className="h-10 rounded-lg border border-[#d1dfed] bg-white px-3 text-sm text-[#506b93]"><option value="">全部解析状态</option><option value="PARSED">已解析</option><option value="PARSE_FAILED">解析失败</option></select>
          <button type="button" onClick={() => void load()} className="outline-button"><RefreshCw size={15}/>刷新</button>
        </div>
        {loading ? <div className="grid h-56 place-items-center text-sm text-[#7185a3]"><Loader2 className="animate-spin"/></div> : items.length === 0 ? <EmptyCandidates onUpload={() => inputRef.current?.click()}/> : <div className="divide-y divide-[#edf2f7]">
          {items.map(item => <button type="button" key={item.id} onClick={() => void openDetail(item.id)} className={`grid w-full gap-3 px-5 py-4 text-left transition hover:bg-[#f7fbff] md:grid-cols-[1.1fr_1.5fr_120px] ${selected?.id === item.id ? "bg-[#edfbf7]" : ""}`}>
            <span><strong className="block text-sm text-[#193866]">{item.displayNameMasked}</strong><small className="mt-1 block truncate text-[#8191a8]">{item.originalFilename}</small></span>
            <span><span className="block text-xs text-[#506d97]">{item.headline || "等待结构化解析"}</span><span className="mt-2 flex flex-wrap gap-1">{item.skills.slice(0, 4).map(skill => <i key={skill} className="rounded bg-[#edf5ff] px-2 py-0.5 text-[10px] not-italic text-[#3970ad]">{skill}</i>)}</span></span>
            <span className={`self-start justify-self-start rounded-full px-2 py-1 text-[11px] font-semibold ${item.parseStatus === "PARSED" ? "bg-[#ddf8ed] text-[#07875b]" : "bg-[#fff0e6] text-[#c35b17]"}`}>{item.parseStatus === "PARSED" ? "已解析" : "解析失败"}</span>
          </button>)}
        </div>}
      </section>

      <aside className="rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        {!selected ? <div className="flex h-full flex-col items-center justify-center text-center text-[#7185a3]"><UserRound size={38} className="text-[#87b9d6]"/><p className="mb-0 mt-3 text-sm">选择候选人查看解析结果</p></div> : <>
          <div className="flex items-start justify-between gap-3"><div><span className="text-[11px] text-[#8293aa]">候选人详情 · 默认脱敏</span><h2 className="mb-0 mt-1 text-xl text-[#163665]">{revealed?.fullName || selected.displayNameMasked}</h2></div><button type="button" onClick={() => { setSelected(null); setRevealed(null); }} className="text-[#7890ad]"><X size={18}/></button></div>
          <div className="mt-4 rounded-lg bg-[#f3f9ff] p-3 text-xs leading-6 text-[#56749a]"><p className="m-0 font-semibold">{selected.headline || "尚无人才画像"}</p><p className="m-0">{selected.yearsExperience || 0} 年经验 · {selected.highestEducation || "学历待确认"}</p>{revealed && <p className="m-0">{revealed.phone || "未识别手机号"} · {revealed.email || "未识别邮箱"}</p>}</div>
          <div className="mt-4 flex flex-wrap gap-2"><button type="button" className="outline-button" onClick={() => void handleReveal()} disabled={busy}><Eye size={14}/>查看实名信息</button><button type="button" className="outline-button" onClick={() => void downloadResume(workspaceId, selected)}><Download size={14}/>原简历</button>{selected.parseStatus === "PARSE_FAILED" && <button type="button" className="outline-button" onClick={() => void handleRetry()}><RefreshCw size={14}/>重试解析</button>}</div>
          {selected.warnings.length > 0 && <div className="mt-4 rounded-lg border border-[#f3d28a] bg-[#fffaf0] p-3 text-xs text-[#8b681f]">{selected.warnings.join("；")}</div>}
          <DetailBlock title="关键技能"><div className="flex flex-wrap gap-1.5">{selected.skills.map(skill => <span key={skill} className="rounded-full bg-[#e9f7f4] px-2 py-1 text-[11px] text-[#168573]">{skill}</span>)}</div></DetailBlock>
          <DetailBlock title="AI 解析摘要"><p>{selected.summary || "暂无解析摘要"}</p></DetailBlock>
          <DetailBlock title="解析版本"><p>Resume Schema V1 · version {selected.parseVersion || 0}</p></DetailBlock>
          <button type="button" onClick={() => void handleDelete()} className="mt-6 flex items-center gap-1.5 text-xs text-[#c43d3d]"><Trash2 size={14}/>删除候选人</button>
        </>}
      </aside>
    </div>
  </>;
  return embedded ? content : <AppShell activeItem="人才库">{content}</AppShell>;
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: number }) { return <article className="flex items-center gap-3 rounded-xl border border-[#dbe8f4] bg-white p-4"><span className="grid h-10 w-10 place-items-center rounded-xl bg-[#e8f8f4] text-[#109b82]">{icon}</span><span><small className="text-[#768aa7]">{label}</small><strong className="mt-1 block text-xl text-[#163665]">{value}</strong></span></article>; }
function DetailBlock({ title, children }: { title: string; children: React.ReactNode }) { return <section className="mt-5 border-t border-[#edf1f5] pt-4"><h3 className="m-0 text-xs font-bold text-[#36537e]">{title}</h3><div className="mt-2 text-xs leading-6 text-[#657b9c]">{children}</div></section>; }
function EmptyCandidates({ onUpload }: { onUpload: () => void }) { return <div className="flex h-[420px] flex-col items-center justify-center text-center"><span className="grid h-14 w-14 place-items-center rounded-2xl bg-[#eaf8f5] text-[#169b83]"><FileText size={27}/></span><h2 className="mb-0 mt-4 text-lg text-[#193866]">上传第一份简历</h2><p className="mt-2 text-sm text-[#7185a3]">支持批量 PDF、DOCX；文件会先校验并保存在当前工作空间。</p><button type="button" className="primary-button mt-4" onClick={onUpload}><Upload size={16}/>选择文件</button></div>; }
function State({ text }: { text: string }) { return <AppShell activeItem="人才库"><div className="grid h-64 place-items-center text-sm text-[#7085a4]">{text}</div></AppShell>; }
function messageOf(cause: unknown) { return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试"; }
