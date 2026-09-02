"use client";

import { AlertCircle, BriefcaseBusiness, CircleDollarSign, File, Loader2, Pencil, Save, Upload, Eye, X, Sparkles, ExternalLink } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import { fetchJobs, type Job } from "@/lib/job-api";
import {
  generateResumeParse, getResumeSourceFileDownload,
  updateResumeParseDraft, uploadResumeSourceFile,
  type ResumeParseDraft, type ResumeSourceFile, type TaskDetail,
} from "@/lib/recruitment-api";
import { useWorkspace } from "@/lib/workspace-context";

/**
 * AI简历解析承接页面（左侧主体，嵌入式，类似JdEditor与ScreeningWorkspace的组合）
 *  - 顶部：职位信息卡片（如linkedJobId有值，只读不可修改）
 *  - 中部：原始简历文件列表，点击可预览
 *  - 底部：解析结果大文本框（可编辑保存）
 * 右侧 AI招聘助手 由外层布局负责。
 */
export function ResumeParsingWorkspace({
  embedded = false,
  detail,
  onDetailUpdated,
}: {
  embedded?: boolean;
  detail: TaskDetail;
  onDetailUpdated?: (next: TaskDetail) => void;
}) {
  const { workspaceId, workspace, loading: workspaceLoading, notAuthenticated } = useWorkspace();
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 关联职位（从职位库查询完整详情，只读）
  const [linkedJob, setLinkedJob] = useState<Job | null>(null);
  // 解析草稿：可编辑副本
  const [draftContent, setDraftContent] = useState("");
  const [draftDirty, setDraftDirty] = useState(false);
  // 预览弹窗
  const [preview, setPreview] = useState<ResumeSourceFile | null>(null);
  // 上传简历input id
  const uploadInputId = `resume-upload-${detail.task.id}`;

  // 初始化：根据linkedJobId拉职位信息、初始化草稿文本
  useEffect(() => {
    let cancelled = false;
    const jobId = detail.task.linkedJobId;
    if (workspaceId && jobId) {
      setLoading(true);
      void fetchJobs(workspaceId, { page: 1, pageSize: 50 })
        .then((result) => {
          if (cancelled) return;
          const found = result.items.find((item) => item.id === jobId) ?? null;
          setLinkedJob(found);
        })
        .catch((cause) => { if (!cancelled) setError(messageOf(cause)); })
        .finally(() => { if (!cancelled) setLoading(false); });
    } else {
      setLinkedJob(null);
    }
    // 草稿初始化
    const current: ResumeParseDraft | null = detail.resumeParseDraft;
    setDraftContent(current?.content ?? "");
    setDraftDirty(false);
    return () => { cancelled = true; };
  }, [workspaceId, detail.task.linkedJobId, detail.task.id, detail.resumeParseDraft?.id, detail.resumeParseDraft?.revision, detail.resumeParseDraft?.updatedAt]);

  useEffect(() => { if (notAuthenticated) window.location.replace("/login"); }, [notAuthenticated]);

  /** 保存解析草稿 */
  async function handleSave() {
    if (!workspaceId || busy) return;
    setBusy(true); setError(null);
    try {
      const revision = detail.resumeParseDraft?.revision ?? 1;
      const next = await updateResumeParseDraft(workspaceId, detail.task.id, { revision, content: draftContent });
      setDraftDirty(false);
      onDetailUpdated?.(next);
    } catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  /** 上传新的简历文件（补充更多简历或重试） */
  async function handleUploadFiles(files: FileList | null) {
    if (!workspaceId || !files || files.length === 0) return;
    setBusy(true); setError(null);
    try {
      let current = detail;
      for (let i = 0; i < files.length; i += 1) {
        const file = files[i];
        await uploadResumeSourceFile(workspaceId, current.task.id, file);
      }
      // 重新拉取一次详情，保证文件列表与草稿刷新
      const { fetchTask } = await import("@/lib/recruitment-api");
      const refreshed = await fetchTask(workspaceId, current.task.id);
      onDetailUpdated?.(refreshed);
    } catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  /**
   * 点击「AI解析 / 重新解析」：
   *   1) 调 POST resume-parse-runs 创建 ai_runs + outbox；
   *   2) 后端 worker 会自动 claim 并跑 provider，解析完成写回 resume_parse_drafts 新版本；
   *   3) 每 1.5s 轮询一次 TaskDetail 最新进度，直到 latestAiRun.status ∈ {COMPLETED, FAILED}。
   */
  async function handleAiParse() {
    if (!workspaceId || busy) return;
    setBusy(true); setError(null);
    try {
      const first = await generateResumeParse(workspaceId, detail.task.id);
      onDetailUpdated?.(first);
      // 简易轮询：最多 60 秒；也可以用 SSE（当前 jd-runs/events 不区分 capability）。
      let attempts = 0;
      const MAX_ATTEMPTS = 40;
      const { fetchTask } = await import("@/lib/recruitment-api");
      while (attempts < MAX_ATTEMPTS) {
        attempts += 1;
        await new Promise((resolve) => setTimeout(resolve, 1500));
        const refreshed = await fetchTask(workspaceId, detail.task.id);
        onDetailUpdated?.(refreshed);
        const status = refreshed.latestAiRun?.status;
        if (status === "COMPLETED" || status === "FAILED") break;
      }
    } catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  /** 预览按钮：调用下载接口获取预签名 URL，新标签页打开（PDF 浏览器原生可预览） */
  async function handlePreview(item: ResumeSourceFile) {
    if (!workspaceId) return;
    setBusy(true); setError(null);
    try {
      const data = await getResumeSourceFileDownload(workspaceId, detail.task.id, item.id);
      if (!data?.url) { setError("预览链接生成失败，请稍后再试"); return; }
      window.open(data.url, "_blank", "noopener,noreferrer");
    } catch (cause) { setError(messageOf(cause)); }
    finally { setBusy(false); }
  }

  const files = detail.resumeSourceFiles ?? [];
  const parseStatus = detail.latestAiRun?.status;
  const running = parseStatus === "RUNNING" || parseStatus === "QUEUED";
  const progress = detail.latestAiRun?.progress ?? 0;

  if (workspaceLoading) return <Loading text="正在加载工作空间..."/>;
  if (!workspaceId) return <Loading text="请先进入一个可访问的工作空间"/>;

  const body = <>
    {error && <div className="mb-4 flex items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}

    <section className="rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
      {/* 标题栏 */}
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#e4edf6] pb-4">
        <div>
          <h2 className="m-0 flex items-center gap-2 text-base text-[#173568]"><File className="text-[#13977e]" size={18}/>AI简历解析</h2>
          <p className="mb-0 mt-1 text-xs text-[#7185a3]">
            {running ? `AI 正在解析简历 · ${progress}%`
              : files.length ? `共 ${files.length} 份简历，下方为最新解析结果，可编辑保存。`
                : "请先上传简历文件，AI 将自动提取简历内容并可与所选职位进行匹配分析。"}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <label className="outline-button inline-flex cursor-pointer items-center gap-2">
            <Upload size={14}/>上传简历
            <input
              id={uploadInputId}
              type="file"
              multiple
              accept=".pdf,.doc,.docx,.txt"
              className="hidden"
              onChange={(event) => void handleUploadFiles(event.target.files)}
            />
          </label>
          <button
            type="button"
            className="primary-button inline-flex items-center gap-2"
            disabled={busy || running || files.length === 0}
            onClick={() => void handleAiParse()}
          >
            {busy || running ? <Loader2 className="animate-spin" size={15}/> : <Sparkles size={15}/>}
            {running ? `AI 解析中 ${progress}%` : detail.latestAiRun ? "重新解析" : "AI 解析"}
          </button>
          <button type="button" className="outline-button" disabled={busy || !draftDirty} onClick={() => void handleSave()}>
            {busy ? <Loader2 className="animate-spin" size={15}/> : <Save size={15}/>}保存草稿
          </button>
        </div>
      </div>

      {loading ? <Loading text="正在加载职位信息..."/> : <div className="mt-5 space-y-6">
        {/* 1. 职位信息卡片（只读，无修改入口）；未选职位时不渲染 */}
        {linkedJob && (
          <section>
            <h3 className="section-title">关联职位</h3>
            <div className="flex flex-wrap items-start justify-between gap-3 rounded-lg bg-[#f6f9fd] px-4 py-3">
              <div>
                <strong className="text-sm text-[#29486f]">{linkedJob.title}</strong>
                <p className="mb-0 mt-1 text-xs text-[#7185a3]">
                  {[linkedJob.companyName, linkedJob.location, linkedJob.experienceLevel, linkedJob.education]
                    .filter(Boolean).join(" · ") || "职位库关联信息"}
                </p>
                {linkedJob.skills && <p className="mb-0 mt-1 text-[11px] text-[#8292a8]">关键技能：{linkedJob.skills}</p>}
              </div>
              <span className="inline-flex items-center gap-1 rounded-full border border-[#cfe4f5] bg-[#f0faff] px-2.5 py-1 text-[10px] font-semibold text-[#176ce5]">
                <BriefcaseBusiness size={12}/>来自职位库（只读）
              </span>
            </div>
          </section>
        )}

        {/* 2. 简历源文件列表：点击可预览 */}
        <section>
          <div className="flex items-center justify-between">
            <h3 className="section-title">原始简历文件</h3>
            <span className="text-xs text-[#7085a4]">{files.length ? `共 ${files.length} 份` : "尚未上传简历文件"}</span>
          </div>
          {files.length === 0 ? (
            <div className="mt-3 rounded-lg border border-dashed border-[#cfdceb] bg-[#fbfdff] px-4 py-6 text-center text-xs text-[#7085a4]">
              点击右上角"上传简历"，补充 PDF / DOC / DOCX / TXT 格式的简历。
            </div>
          ) : (
            <div className="mt-3 flex flex-wrap gap-2">
              {files.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => void handlePreview(item)}
                  className="group flex max-w-[280px] items-center gap-2 rounded-lg border border-[#e0e8f0] bg-[#f5faff] px-3 py-2 text-left text-xs text-[#344f75] hover:border-[#176ce5] hover:bg-[#edf6ff]"
                  title="点击生成预览链接并在新标签页打开（10 分钟内有效）"
                >
                  <span className="grid h-7 w-7 shrink-0 place-items-center rounded bg-white text-[#15b6b3]"><File size={13}/></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">{item.filename}</span>
                    <span className="block truncate text-[10px] text-[#8292a8]">{formatSize(item.sizeBytes)} · {formatDate(item.createdAt)}</span>
                  </span>
                  <ExternalLink size={13} className="text-[#7085a4] opacity-60 group-hover:opacity-100"/>
                </button>
              ))}
            </div>
          )}
        </section>

        {/* 3. 解析结果大文本框（同 JdEditor 的大文本风格） */}
        <section>
          <div className="flex items-center justify-between">
            <h3 className="section-title">解析结果</h3>
            <div className="flex items-center gap-2">
              {running && <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-[#1676d2]"><Loader2 className="animate-spin" size={12}/>AI 解析中…稍后结果将自动填入</span>}
              {draftDirty && <span className="text-[11px] text-[#c45b1f]">已修改，记得保存</span>}
              {!draftDirty && detail.resumeParseDraft && <span className="text-[11px] text-[#7085a4]">版本 V{detail.resumeParseDraft.revision} · {formatDate(detail.resumeParseDraft.updatedAt)}</span>}
            </div>
          </div>
          <div className="mt-3">
            <textarea
              value={draftContent}
              onChange={(event) => { setDraftContent(event.target.value); setDraftDirty(true); }}
              onBlur={() => void 0}
              placeholder={running ? "AI 正在解析简历内容，请稍候…" : "AI 解析结果将展示在此，你可以直接手动编辑或补充，修改后点击『保存草稿』保存版本。"}
              className="min-h-[380px] w-full resize-y rounded-xl border border-[#d4e0ee] bg-white px-4 py-3 text-[14px] leading-7 text-[#26466f] outline-none transition focus:border-[#3d83e8] focus:ring-2 focus:ring-[#eaf2ff]"
              maxLength={200_000}
            />
            <div className="mt-2 flex items-center justify-between text-[11px] text-[#7085a4]">
              <span><Pencil size={11} className="mr-1 inline"/> 手动编辑后点击"保存草稿"确认修改，每次保存生成新版本。</span>
              <span>{draftContent.length} / 200000</span>
            </div>
          </div>
        </section>
      </div>}
    </section>

    {/* 简历预览弹窗：已改为新标签页打开对象存储 URL，保留此弹窗仅用于错误/空态提示（目前已不触发 setPreview，后续可删） */}
    {preview && (
      <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" onClick={() => setPreview(null)}>
        <div
          className="w-full max-w-3xl overflow-hidden rounded-2xl bg-white shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between border-b border-[#e2ebf5] px-5 py-4">
            <div>
              <h3 className="m-0 text-lg font-bold text-[#173568]">简历文件预览</h3>
              <p className="mb-0 mt-1 text-xs text-[#657996]">{preview.filename} · {formatSize(preview.sizeBytes)}</p>
            </div>
            <button type="button" onClick={() => setPreview(null)}
              className="grid h-8 w-8 place-items-center rounded-lg text-[#657996] hover:bg-[#f3f5f8] hover:text-[#173568]"
              aria-label="关闭"><X size={18}/></button>
          </div>
          <div className="space-y-3 px-5 py-4 text-sm text-[#476492]">
            <p className="mb-0 text-xs text-[#7085a4]">简历文件已通过对象存储预签名链接在新标签页打开；若浏览器未自动弹出，请点击下方按钮重试。</p>
            <div className="flex items-center gap-2">
              <button type="button" className="primary-button inline-flex items-center gap-2" onClick={() => void handlePreview(preview)}>
                <ExternalLink size={14}/>在新标签页打开预览
              </button>
            </div>
            <ul className="space-y-1 text-xs">
              <li><strong>文件 ID：</strong>{preview.id}</li>
              <li><strong>MIME：</strong>{preview.mediaType || "未知"}</li>
              <li><strong>上传时间：</strong>{formatDate(preview.createdAt)}</li>
            </ul>
          </div>
        </div>
      </div>
    )}
  </>;

  // 非嵌入式模式下独立页面壳（当前只走嵌入式外层）
  if (!embedded) {
    const { AppShell } = require("@/components/layout/app-shell");
    return <AppShell activeItem="智能招聘"
      pageHeader={
        <section className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="m-0 text-[25px] font-bold text-[#09245d]">AI简历解析</h1>
            <p className="mb-0 mt-1 text-sm text-[#60799f]">{workspace?.name ?? "当前工作空间"} · 上传简历后由 AI 提取关键内容，可与职位匹配度一起查看编辑</p>
          </div>
          <div className="flex items-center gap-2 rounded-lg border border-[#cfe4f5] bg-white px-3 py-2 text-xs text-[#53709a]">
            <CircleDollarSign size={16} className="text-[#0a9a66]"/>简历解析临时价 ¥0.80/份
          </div>
        </section>
      }
    >{body}</AppShell>;
  }
  return body;
}

function Loading({ text }: { text: string }) {
  return <div className="grid h-64 place-items-center text-sm text-[#7085a4]">{text}</div>;
}

function messageOf(cause: unknown) {
  return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试";
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(iso: string) {
  try {
    const d = new Date(iso);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  } catch {
    return iso;
  }
}
