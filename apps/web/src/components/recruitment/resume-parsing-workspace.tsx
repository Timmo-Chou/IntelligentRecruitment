"use client";

import { AlertCircle, BriefcaseBusiness, CircleDollarSign, File, Loader2, Pencil, Save, Upload, Eye, X, Sparkles, ExternalLink, User, UserPlus, CheckCircle2, Download, AlertTriangle } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ApiError } from "@/lib/api-client";
import { fetchJobs, type Job } from "@/lib/job-api";
import { fetchCandidate, type CandidateDetail } from "@/lib/candidate-api";
import {
  confirmResumeParseDraft, generateResumeParse, getResumeSourceFileDownload,
  updateResumeParseDraft, uploadResumeSourceFile,
  type ResumeParseDraft, type ResumeSourceFile, type TaskDetail,
} from "@/lib/recruitment-api";
import { useWorkspace } from "@/lib/workspace-context";
// docx 文件前端渲染：用于弹窗内预览 Word 简历
import { renderAsync as renderDocxAsync } from "docx-preview";

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
  // 发布到人才库请求中（后端会同步建人才库档案，耗时略长）
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 关联职位（从职位库查询完整详情，只读）
  const [linkedJob, setLinkedJob] = useState<Job | null>(null);
  // 关联人才（从人才库查询完整详情，只读）
  const [linkedCandidate, setLinkedCandidate] = useState<CandidateDetail | null>(null);
  const [candidateLoading, setCandidateLoading] = useState(false);
  // 解析草稿：可编辑副本
  const [draftContent, setDraftContent] = useState("");
  const [draftDirty, setDraftDirty] = useState(false);
  // 预览弹窗
  const [preview, setPreview] = useState<ResumeSourceFile | null>(null);
  // 预览文件的预签名URL（iframe / docx fetch 用）
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  // docx 渲染错误信息
  const [previewDocxError, setPreviewDocxError] = useState<string | null>(null);
  // docx 渲染目标容器（docx-preview 直接把内容写入此 DOM）
  const docxContainerRef = useRef<HTMLDivElement | null>(null);
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

  // 根据 linkedCandidateId 拉人才基本信息（选人才库人才时触发）
  useEffect(() => {
    let cancelled = false;
    const candidateId = detail.task.linkedCandidateId;
    if (workspaceId && candidateId) {
      setCandidateLoading(true);
      void fetchCandidate(workspaceId, candidateId)
        .then((item) => { if (!cancelled) setLinkedCandidate(item); })
        .catch((cause) => { if (!cancelled) { setLinkedCandidate(null); setError(`人才加载失败：${messageOf(cause)}`); } })
        .finally(() => { if (!cancelled) setCandidateLoading(false); });
    } else {
      setLinkedCandidate(null);
    }
    return () => { cancelled = true; };
  }, [workspaceId, detail.task.linkedCandidateId]);

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

  /**
   * 发布到人才库：把解析结果对应的简历创建为人才库候选人（与 JD「确认发布」对称）。
   * 后端幂等：已发布或任务本就关联人才库人才时，仅把草稿置为 CONFIRMED。
   */
  async function handleConfirmToPool() {
    if (!workspaceId || busy || confirming) return;
    // 发布前若有未保存修改，先落盘，避免人才库拿到旧内容
    if (draftDirty) {
      const revision = detail.resumeParseDraft?.revision ?? 1;
      try {
        const saved = await updateResumeParseDraft(workspaceId, detail.task.id, { revision, content: draftContent });
        onDetailUpdated?.(saved);
        setDraftDirty(false);
      } catch (cause) { setError(messageOf(cause)); return; }
    }
    setConfirming(true); setError(null);
    try {
      const next = await confirmResumeParseDraft(workspaceId, detail.task.id);
      onDetailUpdated?.(next);
    } catch (cause) { setError(messageOf(cause)); }
    finally { setConfirming(false); }
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
        if (status === "COMPLETED" || status === "FAILED") {
          break;
        }
      }
    } catch (cause) { setError(messageOf(cause));
    }
    finally { setBusy(false); }
  }

  /** 预览按钮：调用下载接口获取预签名 URL，在当前页面弹窗内联预览 */
  async function handlePreview(item: ResumeSourceFile) {
    if (!workspaceId) return;
    setBusy(true); setError(null); setPreviewUrl(null); setPreviewDocxError(null);
    // 先打开弹窗（显示加载中状态），再异步获取URL
    setPreview(item);
    try {
      const data = await getResumeSourceFileDownload(workspaceId, detail.task.id, item.id);
      if (!data?.url) { setError("预览链接生成失败，请稍后再试"); return; }
      setPreviewUrl(data.url);
    } catch (cause) {
      setError(messageOf(cause));
    } finally { setBusy(false); }
  }

  /** 判断预览类型，决定采用哪种渲染内核 */
  function detectPreviewKind(item: ResumeSourceFile): "pdf" | "txt" | "docx" | "image" | "unsupported" {
    const name = (item.filename || "").toLowerCase();
    const mime = (item.mediaType || "").toLowerCase();
    if (name.endsWith(".pdf") || mime.includes("pdf")) return "pdf";
    if (name.endsWith(".txt") || mime === "text/plain") return "txt";
    if (name.endsWith(".docx") || mime.includes("wordprocessingml")) return "docx";
    if (name.endsWith(".doc")) return "unsupported"; // .doc 老格式前端无法渲染
    if (/^image\//.test(mime) || /\.(png|jpe?g|gif|webp|bmp|svg)$/.test(name)) return "image";
    return "unsupported";
  }

  /**
   * 当预览类型为 docx 且 URL 就绪时：
   *  1) fetch 预签名URL拿 ArrayBuffer；
   *  2) 调 docx-preview.renderAsync 把 Word 内容写进 docxContainerRef；
   *  3) 失败则走错误态，提示用户下载。
   */
  useEffect(() => {
    if (!preview || !previewUrl || !docxContainerRef.current) return;
    const kind = detectPreviewKind(preview);
    if (kind !== "docx") return;
    let cancelled = false;
    const container = docxContainerRef.current;
    // 每次重新渲染前清空容器
    container.innerHTML = "";
    setPreviewDocxError(null);
    (async () => {
      try {
        const resp = await fetch(previewUrl);
        if (!resp.ok) throw new Error(`下载失败：HTTP ${resp.status}`);
        const buf = await resp.arrayBuffer();
        await renderDocxAsync(buf, container, undefined, {
          inWrapper: false,        // 不包 docx-preview 自己的 wrapper，减少样式冲突
          ignoreWidth: false,      // 保留 Word 原始页面宽度
          ignoreHeight: true,      // 高度由内容撑开，外层 div 滚动
          ignoreFonts: false,
          breakPages: true,
          useBase64URL: true,      // 图片转 base64，避免外链失效
        } as Parameters<typeof renderDocxAsync>[3]);
        if (cancelled) container.innerHTML = "";
      } catch (cause) {
        if (cancelled) return;
        const msg = cause instanceof Error ? cause.message : String(cause);
        setPreviewDocxError(`Word 文档解析失败：${msg}`);
      }
    })();
    return () => { cancelled = true; };
  }, [preview, previewUrl]);

  const files = detail.resumeSourceFiles ?? [];
  const parseStatus = detail.latestAiRun?.status;
  const running = parseStatus === "RUNNING" || parseStatus === "QUEUED";
  const progress = detail.latestAiRun?.progress ?? 0;
  // 已发布到人才库：草稿已 CONFIRMED，或任务本就关联了人才库人才（含从人才库选择解析 / 已发布）
  const published = detail.resumeParseDraft?.status === "CONFIRMED" || Boolean(detail.task.linkedCandidateId);

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
                : linkedCandidate ? `已从人才库选择候选人「${linkedCandidate.displayNameMasked}」，AI 将基于其简历内容进行解析${Boolean(detail.task.linkedJobId) ? "并结合所选职位匹配" : ""}。`
                  : "请先上传简历文件或从人才库选择候选人，AI 将自动提取简历内容并可与所选职位进行匹配分析。"}
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
          {/* 允许 AI 解析的条件：至少有上传文件，或关联了职位/人才（即使未重传文件也能从 job / candidate 视角进行匹配或摘要） */}
          {(() => {
            const canAiParse = files.length > 0 || Boolean(detail.task.linkedJobId) || Boolean(detail.task.linkedCandidateId);
            return (
              <button
                type="button"
                className="primary-button inline-flex items-center gap-2"
                disabled={busy || running || !canAiParse}
                onClick={() => void handleAiParse()}
                title={canAiParse ? "" : "请先上传简历或选择职位/人才后再解析"}
              >
                {busy || running ? <Loader2 className="animate-spin" size={15}/> : <Sparkles size={15}/>}
                {running ? `AI 解析中 ${progress}%` : detail.latestAiRun ? "重新解析" : "AI 解析"}
              </button>
            );
          })()}
          {/* 保存草稿：只要解析结果内容非空就允许点击（首次保存、内容未修改但想落盘、手动修改后三种场景都覆盖） */}
          <button type="button" className="outline-button" disabled={busy || confirming || !draftContent.trim()} onClick={() => void handleSave()}>
            {busy ? <Loader2 className="animate-spin" size={15}/> : <Save size={15}/>}保存草稿
          </button>
          {/* 发布到人才库：与 JD「确认发布」对称，把简历创建为人才库候选人；已发布后变为跳转入口 */}
          {published ? (
            <Link href="/candidates" className="primary-button inline-flex items-center gap-2" style={{ backgroundColor: "#0ca58c" }}>
              <CheckCircle2 size={15}/>已发布到人才库 · 查看
            </Link>
          ) : (
            <button type="button" className="primary-button inline-flex items-center gap-2"
              disabled={busy || confirming || running || !draftContent.trim()}
              onClick={() => void handleConfirmToPool()}
              title={draftContent.trim() ? "把该简历发布到人才库" : "请先完成 AI 解析再发布到人才库"}>
              {confirming ? <Loader2 className="animate-spin" size={15}/> : <UserPlus size={15}/>}
              {confirming ? "发布中…" : "发布到人才库"}
            </button>
          )}
        </div>
      </div>

      {loading ? <Loading text="正在加载职位信息..."/> : <div className="mt-5 space-y-6">
        {/* 1. 职位信息卡片（只读，无修改入口）；未选职位时不渲染。
           「来自职位库」放在标题同一行的最右侧：第一行 flex 内标题+badge 左右对齐，
           第二行单独展示公司/地点/技能详情，避免详情内容过长时 badge 被挤到底部折行。 */}
        {linkedJob && (
          <section>
            <h3 className="section-title">职位基本信息</h3>
            <div className="space-y-2 rounded-lg bg-[#f6f9fd] px-4 py-3">
              {/* 标题行：左侧职位标题，右侧「来自职位库」badge —— 强制同一行 */}
              <div className="flex items-start justify-between gap-3">
                <strong className="text-sm text-[#29486f] truncate">{linkedJob.title}</strong>
                <span className="inline-flex shrink-0 items-center gap-1 rounded-full border border-[#cfe4f5] bg-[#f0faff] px-2.5 py-1 text-[10px] font-semibold text-[#176ce5]">
                  <BriefcaseBusiness size={12}/>来自职位库
                </span>
              </div>
              {/* 详情行：公司/地点/经验/学历 与 关键技能，在标题下独立区块 */}
              <div>
                <p className="mb-0 text-xs text-[#7185a3]">
                  {[linkedJob.companyName, linkedJob.location, linkedJob.experienceLevel, linkedJob.education]
                    .filter(Boolean).join(" · ") || "职位库关联信息"}
                </p>
                {linkedJob.skills && <p className="mb-0 mt-1 text-[11px] text-[#8292a8]">关键技能：{linkedJob.skills}</p>}
              </div>
            </div>
          </section>
        )}

        {/* 1b. 关联人才信息卡片（只读，从人才库加载）；未选人才时不渲染 */}
        {linkedCandidate && !candidateLoading && (
          <section>
            <h3 className="section-title">关联人才</h3>
            <div className="flex flex-wrap items-start justify-between gap-3 rounded-lg bg-[#fff8f2] px-4 py-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <strong className="text-sm text-[#29486f]">{linkedCandidate.displayNameMasked}</strong>
                  {(() => {
                    const s = linkedCandidate.parseStatus;
                    const color = s === "PARSED" ? "border-[#c4ead8] bg-[#eafbf2] text-[#098a51]"
                      : s === "PROCESSING" || s === "QUEUED" ? "border-[#cfe4f5] bg-[#f0faff] text-[#176ce5]"
                        : s === "FAILED" ? "border-[#fecaca] bg-[#fff1f2] text-[#b42318]"
                          : "border-[#e4dcd2] bg-[#fbf5ed] text-[#8b6d47]";
                    const label = s === "PARSED" ? "已解析"
                      : s === "PROCESSING" || s === "QUEUED" ? "解析中"
                        : s === "FAILED" ? "解析失败"
                          : s || "待解析";
                    return <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold ${color}`}>{label}</span>;
                  })()}
                </div>
                {linkedCandidate.headline && (
                  <p className="mb-0 mt-1 text-xs text-[#7185a3]">{linkedCandidate.headline}</p>
                )}
                <p className="mb-0 mt-1 text-[11px] text-[#8292a8]">
                  {[
                    linkedCandidate.yearsExperience ? `${linkedCandidate.yearsExperience} 年经验` : null,
                    linkedCandidate.highestEducation || null,
                    linkedCandidate.originalFilename ? `简历源文件：${linkedCandidate.originalFilename}` : null,
                  ].filter(Boolean).join(" · ") || "人才库关联信息"}
                </p>
                {Array.isArray(linkedCandidate.skills) && linkedCandidate.skills.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {linkedCandidate.skills.slice(0, 10).map((skill, idx) => (
                      <span key={`${skill}-${idx}`} className="inline-flex items-center rounded-md border border-[#f5d8c2] bg-[#fff1e3] px-2 py-0.5 text-[10px] text-[#9e5517]">{skill}</span>
                    ))}
                    {linkedCandidate.skills.length > 10 && (
                      <span className="text-[10px] text-[#8292a8] self-center">+{linkedCandidate.skills.length - 10} 项</span>
                    )}
                  </div>
                )}
                {linkedCandidate.summary && (
                  <p className="mb-0 mt-2 line-clamp-3 text-[11px] leading-5 text-[#6e82a0]">
                    <span className="font-semibold text-[#5c708e]">简历摘要：</span>{linkedCandidate.summary}
                  </p>
                )}
              </div>
              <span className="inline-flex items-center gap-1 rounded-full border border-[#f5d8c2] bg-[#fff1e3] px-2.5 py-1 text-[10px] font-semibold text-[#b85f11] shrink-0">
                <User size={12}/>来自人才库（只读）
              </span>
            </div>
          </section>
        )}

        {/* 2. 简历源文件列表：点击可预览 — 无上传文件时完全不展示（人才库来源直接看人才卡片） */}
        {files.length > 0 && (
          <section>
            <div className="flex items-center justify-between">
              <h3 className="section-title">原始简历文件</h3>
              <span className="text-xs text-[#7085a4]">共 {files.length} 份</span>
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              {files.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => void handlePreview(item)}
                  className="group flex max-w-[280px] items-center gap-2 rounded-lg border border-[#e0e8f0] bg-[#f5faff] px-3 py-2 text-left text-xs text-[#344f75] hover:border-[#176ce5] hover:bg-[#edf6ff]"
                  title="点击预览该简历文件（在当前页面弹窗查看）"
                >
                  <span className="grid h-7 w-7 shrink-0 place-items-center rounded bg-white text-[#15b6b3]"><File size={13}/></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">{item.filename}</span>
                    <span className="block truncate text-[10px] text-[#8292a8]">{formatSize(item.sizeBytes)} · {formatDate(item.createdAt)}</span>
                  </span>
                  <Eye size={13} className="text-[#7085a4] opacity-60 group-hover:opacity-100"/>
                </button>
              ))}
            </div>
          </section>
        )}

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

    {/* 简历预览弹窗：按文件类型分支渲染（PDF/TXT/图片→iframe, DOCX→docx-preview, 不支持→提示下载） */}
    {preview && (() => {
      const kind = detectPreviewKind(preview);
      // 头部显示当前渲染方式，方便用户理解
      const kindLabel = kind === "pdf" ? "PDF 预览"
        : kind === "txt" ? "文本预览"
          : kind === "docx" ? "Word 预览"
            : kind === "image" ? "图片预览"
              : "暂不支持预览";
      return (
        <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" onClick={() => { setPreview(null); setPreviewUrl(null); setPreviewDocxError(null); }}>
          <div
            className="flex h-[90vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-[#e2ebf5] px-5 py-4">
              <div>
                <h3 className="m-0 text-lg font-bold text-[#173568]">简历文件预览 · <span className="text-[13px] font-medium text-[#3d83e8]">{kindLabel}</span></h3>
                <p className="mb-0 mt-1 text-xs text-[#657996]">{preview.filename} · {formatSize(preview.sizeBytes)} · 上传于 {formatDate(preview.createdAt)}</p>
              </div>
              <div className="flex items-center gap-2">
                {/* 下载按钮：所有格式都能触发浏览器下载（a.download），作为兜底能力 */}
                <a
                  href={previewUrl ?? "#"}
                  download={preview.filename}
                  className={`outline-button inline-flex items-center gap-2 text-xs ${!previewUrl ? "pointer-events-none opacity-50" : ""}`}
                  aria-disabled={!previewUrl}
                >
                  <Download size={13}/>下载文件
                </a>
                <button type="button" className="outline-button inline-flex items-center gap-2 text-xs" onClick={() => previewUrl && window.open(previewUrl, "_blank", "noopener,noreferrer")} disabled={!previewUrl}>
                  <ExternalLink size={13}/>新标签页打开
                </button>
                <button type="button" onClick={() => { setPreview(null); setPreviewUrl(null); setPreviewDocxError(null); }}
                  className="grid h-8 w-8 place-items-center rounded-lg text-[#657996] hover:bg-[#f3f5f8] hover:text-[#173568]"
                  aria-label="关闭"><X size={18}/></button>
              </div>
            </div>
            <div className="relative flex-1 overflow-hidden bg-[#f5f8fc]">
              {!previewUrl && (
                <div className="absolute inset-0 grid place-items-center text-sm text-[#657996]">
                  <div className="flex flex-col items-center gap-3">
                    <Loader2 className="animate-spin text-[#176ce5]" size={28}/>
                    <span>正在生成预览链接…</span>
                  </div>
                </div>
              )}

              {/* 1. PDF / TXT / 图片 → 浏览器原生 iframe 即可渲染 */}
              {previewUrl && (kind === "pdf" || kind === "txt" || kind === "image") && (
                <iframe
                  src={previewUrl}
                  title={preview.filename}
                  className="h-full w-full border-0 bg-white"
                />
              )}

              {/* 2. DOCX → docx-preview 把内容写入 ref 容器；额外展示 loading 与错误态 */}
              {previewUrl && kind === "docx" && (
                <div className="h-full w-full overflow-auto bg-white p-5">
                  {previewDocxError ? (
                    <div className="grid h-full place-items-center">
                      <div className="max-w-md rounded-xl border border-[#fecaca] bg-[#fff1f2] p-5 text-center">
                        <AlertTriangle size={28} className="mx-auto mb-2 text-[#b42318]"/>
                        <p className="mb-2 text-sm font-semibold text-[#b42318]">预览失败</p>
                        <p className="mb-4 text-xs text-[#7185a3]">{previewDocxError}</p>
                        <div className="flex items-center justify-center gap-2">
                          <a href={previewUrl} download={preview.filename} className="primary-button inline-flex items-center gap-2 text-xs">
                            <Download size={13}/>下载到本地查看
                          </a>
                          <button type="button" className="outline-button inline-flex items-center gap-2 text-xs" onClick={() => window.open(previewUrl!, "_blank", "noopener,noreferrer")}>
                            <ExternalLink size={13}/>新标签页打开
                          </button>
                        </div>
                      </div>
                    </div>
                  ) : (
                    <>
                      {/* docx 渲染完成前的骨架提示；renderAsync 会把内容写入 ref */}
                      {docxContainerRef.current && docxContainerRef.current.childElementCount === 0 && (
                        <div className="grid h-full place-items-center text-sm text-[#657996]">
                          <div className="flex flex-col items-center gap-3">
                            <Loader2 className="animate-spin text-[#176ce5]" size={28}/>
                            <span>正在解析 Word 文档…</span>
                          </div>
                        </div>
                      )}
                      <div ref={docxContainerRef} className="docx-preview-root min-h-full [&_*]:box-border [&_table]:w-full [&_table]:border-collapse" />
                    </>
                  )}
                </div>
              )}

              {/* 3. unsupported（.doc 等老格式/未知类型） → 提示只能下载或新标签页打开 */}
              {previewUrl && kind === "unsupported" && (
                <div className="grid h-full place-items-center p-6">
                  <div className="max-w-md rounded-xl border border-[#f5d8c2] bg-[#fff1e3] p-6 text-center">
                    <AlertTriangle size={32} className="mx-auto mb-2 text-[#b85f11]"/>
                    <p className="mb-2 text-base font-semibold text-[#9e5517]">该文件类型暂不支持在线预览</p>
                    <p className="mb-4 text-xs text-[#8b6d47]">
                      {/\.doc$/i.test(preview.filename)
                        ? ".doc 是早期 Word 二进制格式，目前仅支持 .docx（Word 2007+）、PDF、TXT、图片的在线预览。"
                        : "目前支持 .docx、PDF、TXT、图片（png/jpg/gif/webp/svg）的在线预览。"}
                    </p>
                    <div className="flex items-center justify-center gap-2">
                      <a href={previewUrl} download={preview.filename} className="primary-button inline-flex items-center gap-2 text-xs">
                        <Download size={13}/>下载到本地查看
                      </a>
                      <button type="button" className="outline-button inline-flex items-center gap-2 text-xs" onClick={() => window.open(previewUrl!, "_blank", "noopener,noreferrer")}>
                        <ExternalLink size={13}/>尝试新标签页打开
                      </button>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      );
    })()}
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
