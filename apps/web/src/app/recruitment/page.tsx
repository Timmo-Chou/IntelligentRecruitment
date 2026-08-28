"use client";

import {
  AlertCircle, Bot, BriefcaseBusiness, CheckCircle2, CircleDollarSign, Pencil,
  FileText, Filter, ListChecks, Loader2, MessageSquareText, Plus, Save, Send, Sparkles,
  TriangleAlert, UsersRound, Search, X, Upload, FolderOpen, File, Copy,
} from "lucide-react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import {
  confirmJdDraft, createTask, fetchTask, fetchTasks, fetchScreeningDimensions, generateJd, sendMessage,
  streamJdRunEvents, updateJdDraft, updateScreeningDimensions,
  type JdDraft, type ScreeningDimension, type TaskDetail, type TaskSummary,
} from "@/lib/recruitment-api";
import { fetchJobs, type Job } from "@/lib/job-api";
import { useWorkspace } from "@/lib/workspace-context";

type WorkspaceSection = "home" | "jd" | "candidates" | "screening" | "interviews";
type SelectedFeature = "JD_GENERATION" | "CANDIDATE_SCREENING" | "INTERVIEW_KIT" | "RESUME_PARSING" | null;

export default function RecruitmentPage() {
  const searchParams = useSearchParams();
  const requestedTaskId = searchParams.get("task");
  const { workspaceId, workspace, loading: workspaceLoading, notAuthenticated, error: workspaceError } = useWorkspace();
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [draft, setDraft] = useState<JdDraft | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [newTitle, setNewTitle] = useState("");
  const [newRequirement, setNewRequirement] = useState("");
  const [message, setMessage] = useState("");
  const [chatSubmitting, setChatSubmitting] = useState(false);
  const [editingPublishedJd, setEditingPublishedJd] = useState(false);
  const [selectedFeature, setSelectedFeature] = useState<SelectedFeature>(null);
  const [streamText, setStreamText] = useState("");
  const [section, setSection] = useState<WorkspaceSection>("home");
  const streamTaskId = detail?.task.id;
  const streamRunId = detail?.latestAiRun?.id;
  const streamRunStatus = detail?.latestAiRun?.status;

  async function loadTasks(selectId?: string) {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const list = await fetchTasks(workspaceId);
      setTasks(list);
      const targetId = selectId ?? detail?.task.id ?? list[0]?.id;
      if (targetId) {
        const next = await fetchTask(workspaceId, targetId);
        setDetail(next);
        setDraft(next.jdDraft);
      } else {
        setDetail(null);
        setDraft(null);
      }
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (!workspaceId) return;
    let cancelled = false;
    setTimeout(() => {
      if (cancelled) return;
      setLoading(true);
      setError(null);
      void fetchTasks(workspaceId)
        .then(async (list) => {
          if (cancelled) return;
          setTasks(list);
          const targetId = requestedTaskId;
          if (!targetId) {
            setDetail(null);
            setDraft(null);
            setSection("home");
            return;
          }
          const next = await fetchTask(workspaceId, targetId);
          if (!cancelled) {
            setDetail(next);
            setDraft(next.jdDraft);
          }
        })
        .catch((cause) => {
          if (!cancelled) setError(messageOf(cause));
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    }, 0);
    return () => { cancelled = true; };
  }, [workspaceId, requestedTaskId]);

  useEffect(() => {
    if (notAuthenticated) window.location.replace("/login");
  }, [notAuthenticated]);

  useEffect(() => {
    const taskId = streamTaskId;
    if (!workspaceId || !taskId || !streamRunId || !streamRunStatus || !["QUEUED", "RUNNING"].includes(streamRunStatus)) return;
    const controller = new AbortController();
    const cursorKey = `jd-stream:${workspaceId}:${taskId}`;
    let cursor = Number(sessionStorage.getItem(cursorKey) ?? "0");
    let stopped = false;
    void (async () => {
      while (!stopped) {
        try {
          await streamJdRunEvents(workspaceId, taskId, cursor, event => {
            cursor = event.id;
            sessionStorage.setItem(cursorKey, String(cursor));
            if (event.data.delta) setStreamText(current => current ? `${current}\n${event.data.delta}` : event.data.delta!);
            if (event.data.progress !== undefined) setDetail(current => current?.latestAiRun ? {
              ...current, latestAiRun: { ...current.latestAiRun, progress: event.data.progress!, status: (event.data.status ?? current.latestAiRun.status) as typeof current.latestAiRun.status },
            } : current);
            if (event.type === "completed" || event.type === "failed") stopped = true;
          }, controller.signal);
        } catch (cause) {
          if (controller.signal.aborted) return;
          if (!stopped) setError(messageOf(cause));
        }
        if (stopped) {
          const next = await fetchTask(workspaceId, taskId);
          setDetail(next);
          setDraft(next.jdDraft);
          const list = await fetchTasks(workspaceId);
          setTasks(list);
          sessionStorage.removeItem(cursorKey);
          return;
        }
        await new Promise(resolve => window.setTimeout(resolve, 1_000));
      }
    })();
    return () => { stopped = true; controller.abort(); };
  }, [workspaceId, streamTaskId, streamRunId, streamRunStatus]);

  async function handleCreate() {
    if (!workspaceId || !newRequirement.trim()) return;
    await run(async () => {
      const created = await createTask(workspaceId, resolveTaskTitle(newTitle, newRequirement, selectedFeature), newRequirement);
      setNewTitle("");
      setNewRequirement("");
      setDetail(created);
      setDraft(created.jdDraft);
      window.dispatchEvent(new Event("recruitment-tasks-changed"));
      if (selectedFeature === "JD_GENERATION") {
        setSection("jd");
        await startJdGeneration(created);
      } else if (selectedFeature === "CANDIDATE_SCREENING") {
        // AI筛简历：发送后进入简历筛选主页（左右布局）
        setSection("screening");
        await loadTasks(created.task.id);
      } else {
        setSection("jd");
        await loadTasks(created.task.id);
      }
    });
  }

  async function handleMessage() {
    if (!workspaceId || !detail || !message.trim()) return;
    await run(async () => {
      setChatSubmitting(true);
      try {
        await sendMessage(workspaceId, detail.task.id, message);
        // 对话调用包含模型生成，完成后以服务端持久化结果为准重新读取，避免旧 AI run 状态覆盖新消息。
        const next = await fetchTask(workspaceId, detail.task.id);
        setMessage("");
        setDetail(next);
        setDraft(next.jdDraft);
        await refreshTaskList(next.task.id);
      } finally {
        setChatSubmitting(false);
      }
    });
  }

  function handleConversationKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (event.nativeEvent.isComposing) return;
    if (event.ctrlKey && event.key.toLowerCase() === "a") {
      event.preventDefault();
      insertLineBreak(event.currentTarget, update => setMessage(update));
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      if (!busy && message.trim()) void handleMessage();
    }
  }

  async function startJdGeneration(taskDetail: TaskDetail) {
    if (!workspaceId) return;
    setStreamText("");
    const queued = await generateJd(workspaceId, taskDetail.task.id, { scenario: "NORMAL" });
    setDetail(queued);
    setDraft(queued.jdDraft);
    await refreshTaskList(queued.task.id);

    // SSE 用于逐步反馈；轮询是连接中断时的兜底，确保最终 JD 一落库就刷新左侧编辑器。
    for (let attempt = 0; attempt < 80; attempt += 1) {
      await new Promise(resolve => window.setTimeout(resolve, 350));
      const latest = await fetchTask(workspaceId, queued.task.id);
      setDetail(latest);
      setDraft(latest.jdDraft);
      if (["COMPLETED", "FAILED"].includes(latest.latestAiRun?.status ?? "")) {
        await refreshTaskList(latest.task.id);
        return;
      }
    }
  }

  async function handleSave() {
    if (!workspaceId || !detail || !draft) return;
    await run(async () => {
      const next = await updateJdDraft(workspaceId, detail.task.id, draft);
      setDetail(next);
      setDraft(next.jdDraft);
      setEditingPublishedJd(false);
    });
  }

  async function handleConfirm() {
    if (!workspaceId || !detail || !draft) return;
    await run(async () => {
      await confirmJdDraft(workspaceId, detail.task.id);
      const next = await fetchTask(workspaceId, detail.task.id);
      setDetail(next);
      setDraft(next.jdDraft);
      await refreshTaskList(next.task.id);
    });
  }

  async function refreshTaskList(selectedId: string) {
    if (!workspaceId) return;
    const list = await fetchTasks(workspaceId);
    setTasks(list);
    const summary = list.find((item) => item.id === selectedId);
    if (summary) setDetail((current) => current ? { ...current, task: summary } : current);
  }

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  }

  if (workspaceLoading) return <StatePage icon={<Loader2 className="animate-spin" />} text="正在加载工作空间..." />;
  if (workspaceError && !workspaceId) return <StatePage icon={<AlertCircle />} text={`加载工作空间失败：${workspaceError}`} />;
  if (!workspaceId || notAuthenticated) return <StatePage icon={<AlertCircle />} text="请先登录并创建或加入一个可访问的工作空间" />;
  if (loading && tasks.length === 0) return <StatePage icon={<Loader2 className="animate-spin" />} text="正在加载招聘任务..." />;

  if (!requestedTaskId && section === "home" && !detail) {
    return <AppShell activeItem="智能招聘"><RecruitmentEmptyState workspaceId={workspaceId} requirement={newRequirement} selectedFeature={selectedFeature} busy={busy} error={error} onTitle={setNewTitle} onRequirement={setNewRequirement} onCreate={() => void handleCreate()} onFeature={setSelectedFeature} onSection={setSection}/></AppShell>;
  }

  return <AppShell activeItem="智能招聘" pageHeader={
    <section className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="m-0 text-[25px] font-bold tracking-tight text-[#09245d]">智能招聘</h1>
        <p className="mb-0 mt-1 text-sm text-[#55709d]">{workspace?.name ?? "当前工作空间"} · 从需求对话生成并确认可追溯的 JD 版本</p>
      </div>
      <div className="flex items-center gap-2 rounded-lg border border-[#cfe4f5] bg-white px-3 py-2 text-xs text-[#53709a]">
        <CircleDollarSign size={16} className="text-[#0a9a66]" /> JD 生成临时价 ¥0.80/次
      </div>
    </section>
  }>
    {error && <div className="flex items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}

    <div className="mt-4 grid min-h-[680px] gap-4 lg:h-[calc(100dvh-190px)] lg:min-h-0 lg:grid-cols-[minmax(0,1fr)_360px]">
      <main className="min-w-0 rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)] lg:min-h-0 lg:overflow-y-auto lg:overscroll-contain">
        {section === "candidates" ? <WorkflowSection title="上传并解析简历" description="请在此上传 PDF、DOCX 简历，系统将解析候选人信息并在当前工作空间保存。"/> : section === "screening" ? <ScreeningEvaluationPanel task={detail?.task ?? null} workspaceId={workspaceId} initialScreeningDimsJson={detail?.screeningDimsJson ?? null} /> : section === "interviews" ? <WorkflowSection title="AI 面试出题" description="选择候选人，生成可编辑、可确认的结构化面试题包。"/> : !detail ? <CreateTaskPanel title={newTitle} requirement={newRequirement} busy={busy} onTitle={setNewTitle} onRequirement={setNewRequirement} onCreate={() => void handleCreate()} /> : draft ? <JdEditor draft={draft} busy={busy} confirmed={draft.status === "CONFIRMED"} editing={editingPublishedJd} jobId={detail.task.jobId} onEdit={() => setEditingPublishedJd(true)} onChange={setDraft} onSave={() => void handleSave()} onConfirm={() => void handleConfirm()} /> : <JdWaitingState task={detail.task} running={busy || ["QUEUED", "RUNNING"].includes(detail.latestAiRun?.status ?? "")} />}
      </main>

      <aside className="flex min-h-0 flex-col rounded-xl border border-[#d6e5f5] bg-white shadow-[0_6px_20px_rgba(30,92,160,0.04)] lg:h-[calc(100dvh-190px)] lg:overflow-hidden">
        <div className="flex items-center justify-between border-b border-[#e2ebf5] px-4 py-3"><span className="flex items-center gap-2 text-sm font-bold text-[#173568]"><Bot size={17} className="text-[#1478e8]"/>AI 招聘助手</span>{detail?.latestAiRun && <span className={`rounded-full px-2 py-1 text-[10px] font-semibold ${detail.latestAiRun.status === "FAILED" ? "bg-[#fff0f0] text-[#cf3030]" : "bg-[#e7f8f1] text-[#07885b]"}`}>{detail.latestAiRun.status === "FAILED" ? "生成失败" : "Mock AI"}</span>}</div>
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto overscroll-contain p-4">
          {!detail && <div className="rounded-lg bg-[#eef7ff] p-3 text-xs leading-5 text-[#56749f]">创建招聘任务后，我会协助补充需求并生成结构化 JD 草稿。</div>}
          {detail?.messages.map((item) => <article key={item.id} className={`max-w-[92%] rounded-xl px-3 py-2.5 text-xs leading-5 ${item.role === "USER" ? "ml-auto bg-[#176ce5] text-white" : item.role === "SYSTEM" ? "bg-[#f3f5f8] text-[#657996]" : "bg-[#eef8ff] text-[#35577f]"}`}><p className="m-0 whitespace-pre-wrap">{item.content}</p></article>)}
          {chatSubmitting && <article className="max-w-[92%] rounded-xl bg-[#eef8ff] px-3 py-2.5 text-xs leading-5 text-[#55709d]"><span className="flex items-center gap-2"><Loader2 className="animate-spin" size={14}/>AI 正在理解修改需求并更新当前 JD…</span></article>}
          {!chatSubmitting && ["QUEUED", "RUNNING"].includes(detail?.latestAiRun?.status ?? "") && (detail?.latestAiRun?.progress ?? 0) < 100 && <article className="max-w-[92%] rounded-xl bg-[#eef8ff] px-3 py-2.5 text-xs leading-5 text-[#55709d]"><span className="flex items-center gap-2"><Loader2 className="animate-spin" size={14}/>AI 正在生成 JD · {detail?.latestAiRun?.progress ?? 0}%</span>{streamText && <p className="mb-0 mt-2 whitespace-pre-wrap">{streamText}</p>}</article>}
        </div>
        {detail && <div className="border-t border-[#e2ebf5] p-3"><div className="flex items-end gap-2"><textarea value={message} onChange={(event) => setMessage(event.target.value)} onKeyDown={handleConversationKeyDown} className="min-h-[72px] flex-1 resize-none rounded-lg border border-[#cbdced] px-3 py-2 text-xs outline-none focus:border-[#4a8be8]" placeholder="继续沟通或直接说明要修改的 JD 内容（回车发送，Ctrl+A 换行）"/><button type="button" className="grid h-10 w-10 place-items-center rounded-lg bg-[#176ce5] text-white disabled:opacity-50" disabled={busy || !message.trim()} onClick={() => void handleMessage()} aria-label="发送消息"><Send size={16}/></button></div></div>}
      </aside>
    </div>
  </AppShell>;
}

type RecruitmentStarter = { icon: typeof BriefcaseBusiness; title: string; description: string; requirement?: string; section?: WorkspaceSection; feature?: Exclude<SelectedFeature, null> };
const recruitmentStarters: RecruitmentStarter[] = [
  {
    icon: BriefcaseBusiness,
    title: "我要发布新岗位",
    description: "帮我生成一份高质量的岗位JD",
    requirement: "我们需要招聘一名高级 Java 开发工程师，请协助我梳理业务背景、岗位职责、工作地点、经验学历和核心技能要求。",
    feature: "JD_GENERATION",
  },
  {
    icon: UsersRound,
    title: "我要筛选简历",
    description: "根据岗位JD匹配合适的候选人",
    section: "screening", feature: "CANDIDATE_SCREENING",
  },
  {
    icon: Filter,
    title: "我要准备面试",
    description: "为候选人生成结构化面试问题",
    section: "interviews", feature: "INTERVIEW_KIT",
  },
  {
    icon: ListChecks,
    title: "上传并解析简历",
    description: "批量导入 PDF、DOCX 并自动解析人才信息",
    section: "candidates", feature: "RESUME_PARSING",
  },
];

/** JD 参考模版内容 */
const JD_TEMPLATE_TEXT = `1. 岗位名称：大模型算法工程师
2. 薪资：35K–60K · 14–16薪
3. 工作地点：北京 / 上海 / 深圳，可根据业务情况调整
4. 工作年限：3年以上算法或机器学习相关经验，具备大模型项目经验优先
5. 学历：本科及以上，计算机、人工智能、数学、统计学、电子信息等相关专业
6. 岗位职责：
- 负责大语言模型相关算法的研发、优化与业务落地。
- 负责大模型微调、Prompt 优化、RAG、Agent 等核心能力建设。
- 结合业务场景设计模型方案，并持续提升准确率、稳定性与用户体验。
- 建设大模型评测体系，对模型效果、幻觉、安全性、成本和性能进行持续评估。
- 优化模型训练与推理效率，降低延迟、Token 消耗及算力成本。
- 与产品、工程及业务团队协作，推动大模型能力在实际场景中落地。
7. 任职要求：
- 具备扎实的机器学习、深度学习和自然语言处理基础。
- 深入理解主流大语言模型技术原理及应用方式。
- 具备独立完成模型实验、数据处理、训练、评测和优化的能力。
- 具备良好的代码能力和工程化意识，能够将算法方案转化为稳定服务。
- 具备较强的问题分析、实验设计和数据分析能力。
- 对大模型技术保持持续关注，具备较强的学习能力和技术探索能力。
- 良好的沟通协作能力和责任意识。
8. 关键技能：
- 熟悉PyTorch 等深度学习框架， 熟练掌握Python，具备良好的算法与工程实现能力
- 熟悉 Transformer、Attention、预训练语言模型等核心原理
- 熟悉大模型微调技术，如 SFT、LoRA、QLoRA 等
- 熟悉 RAG、Embedding、向量检索、Rerank 等技术
- 熟悉 Prompt Engineering、Function Calling、Agent 等大模型应用技术
- 具备模型评测、效果优化、推理性能优化相关经验

9. 加分项：
- 有 LLM 预训练、持续预训练、RLHF / DPO 等经验
- 有多模态大模型、Agent、企业知识库项目经验
- 熟悉 vLLM、TensorRT-LLM、DeepSpeed 等推理或训练优化框架
- 有大模型平台、AI SaaS 或企业级 AI 产品落地经验
- 有高质量论文、开源项目或算法竞赛经验

10. 福利待遇：
- 五险一金
- 年终奖 / 项目奖金
- 带薪年假
- 年度体检
- 餐饮及交通补贴
- 学习培训及技术大会支持
- 弹性办公
- 团队建设及节日福利`;

/** 上传文件类型定义 */
type UploadedFile = {
  id: string;
  name: string;
  size: number;
  file: File;
};

function RecruitmentEmptyState({ workspaceId, requirement, selectedFeature, busy, error, onTitle, onRequirement, onCreate, onFeature, onSection }: {
  workspaceId: string;
  requirement: string;
  selectedFeature: SelectedFeature;
  busy: boolean;
  error: string | null;
  onTitle: (value: string) => void;
  onRequirement: (value: string) => void;
  onCreate: () => void;
  onFeature: (feature: SelectedFeature) => void;
  onSection: (section: WorkspaceSection) => void;
}) {
  // 职位库弹窗相关状态
  const [showJobPicker, setShowJobPicker] = useState(false);
  const [jobSearch, setJobSearch] = useState("");
  const [jobList, setJobList] = useState<Job[]>([]);
  const [jobLoading, setJobLoading] = useState(false);
  // 上传文件相关状态
  const [uploadedFiles, setUploadedFiles] = useState<UploadedFile[]>([]);
  const fileInputRef = useState<HTMLInputElement | null>(null);
  // AI筛简历：已选职位（单选）
  const [selectedScreeningJob, setSelectedScreeningJob] = useState<Job | null>(null);

  const inputKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.nativeEvent.isComposing) return;
    if (event.ctrlKey && event.key.toLowerCase() === "a") {
      event.preventDefault();
      insertLineBreak(event.currentTarget, update => onRequirement(update(requirement)));
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      if (!busy && requirement.trim()) onCreate();
    }
  };
  const chooseStarter = (starter: typeof recruitmentStarters[number]) => {
    onFeature(starter.feature ?? null);
    if (starter.section) { onSection(starter.section); return; }
    if (!starter.requirement) return;
    onTitle("");
    onRequirement(starter.requirement);
  };

  /** 点击参考JD模版生成：把模版文本填入输入框 */
  const handleUseTemplate = () => {
    onRequirement(JD_TEMPLATE_TEXT);
  };

  /** 点击从职位库复制：打开弹窗并加载职位列表 */
  const handleOpenJobPicker = async () => {
    setShowJobPicker(true);
    setJobSearch("");
    await loadJobList("");
  };

  /** 加载职位列表（支持搜索） */
  const loadJobList = async (keyword: string) => {
    setJobLoading(true);
    try {
      const result = await fetchJobs(workspaceId, { search: keyword || undefined, pageSize: 20 });
      setJobList(result.items);
    } catch {
      setJobList([]);
    } finally {
      setJobLoading(false);
    }
  };

  /** 选中职位：根据当前 feature 执行不同动作 */
  const handleSelectJob = (job: Job) => {
    if (selectedFeature === "CANDIDATE_SCREENING") {
      // AI筛简历：单选职位，保存到状态
      setSelectedScreeningJob(job);
    } else {
      // JD生成模式：把结构化职位内容直接带入输入框
      const lines: string[] = [];
      if (job.title) lines.push(`1. 岗位名称：${job.title}`);
      if (job.companyName) lines.push(`2. 企业名称：${job.companyName}`);
      if (job.location) lines.push(`3. 工作地点：${job.location}`);
      if (job.experienceLevel) lines.push(`4. 工作年限：${job.experienceLevel}`);
      if (job.education) lines.push(`5. 学历：${job.education}`);
      if (job.jobType) lines.push(`6. 用工类型：${job.jobType}`);
      if (job.description) {
        lines.push(`7. 岗位职责：`);
        job.description.split(/\r?\n/).forEach((line) => {
          const text = line.trim();
          if (text) lines.push(`- ${text.replace(/^[-•·\d、.）)]+\s*/, "")}`);
        });
      }
      if (job.requirements) {
        lines.push(`8. 任职要求：`);
        job.requirements.split(/\r?\n/).forEach((line) => {
          const text = line.trim();
          if (text) lines.push(`- ${text.replace(/^[-•·\d、.）)]+\s*/, "")}`);
        });
      }
      if (job.skills) {
        lines.push(`9. 关键技能：`);
        job.skills.split(/\r?\n/).forEach((line) => {
          const text = line.trim();
          if (text) lines.push(`- ${text.replace(/^[-•·\d、.）)]+\s*/, "")}`);
        });
      }
      const block = lines.join("\n");
      const currentText = requirement ? `${requirement}\n\n` : "";
      onRequirement(`${currentText}${block}`);
    }
    setShowJobPicker(false);
  };

  /** AI筛简历：打开职位选择弹窗（与JD生成复用同一个弹窗组件） */
  const handleOpenScreeningJobPicker = async () => {
    setShowJobPicker(true);
    setJobSearch("");
    await loadJobList("");
  };

  /** AI筛简历：移除已选职位，重新显示选择按钮 */
  const handleRemoveScreeningJob = () => {
    setSelectedScreeningJob(null);
  };

  /** 点击根据上传文件生成：触发文件选择 */
  const handleUploadClick = () => {
    fileInputRef[1](null);
    const el = document.getElementById("jd-file-upload-input") as HTMLInputElement | null;
    if (el) el.click();
  };

  /** 处理文件选择 */
  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = event.target.files;
    if (!files || files.length === 0) return;
    const newFiles: UploadedFile[] = [];
    for (let i = 0; i < files.length; i += 1) {
      const f = files[i];
      newFiles.push({ id: `${Date.now()}-${i}-${f.name}`, name: f.name, size: f.size, file: f });
    }
    setUploadedFiles((prev) => [...prev, ...newFiles]);
    // 清空 input，确保下次还能选择相同文件
    event.target.value = "";
  };

  /** 删除上传的文件 */
  const handleRemoveFile = (id: string) => {
    setUploadedFiles((prev) => prev.filter((item) => item.id !== id));
  };

  /** 格式化文件大小显示 */
  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  };

  return <div className="relative mx-auto flex min-h-[calc(100vh-108px)] max-w-[1180px] flex-col px-3 pb-5 pt-[clamp(24px,4vh,60px)]">
    {error && <div className="mx-auto mb-5 flex w-full max-w-3xl items-center gap-2 rounded-xl border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}

    <section className="mx-auto w-full max-w-4xl text-center">
      <div className="mx-auto grid h-[68px] w-[68px] place-items-center rounded-[22px] bg-gradient-to-br from-[#1d78f2] via-[#18a7d4] to-[#17bd91] text-white shadow-[0_15px_35px_rgba(29,120,242,0.22)]">
        <Bot size={35} strokeWidth={1.8}/>
      </div>
      <h1 className="mb-0 mt-2 text-[clamp(30px,3.5vw,28px)] font-bold tracking-tight text-[#102d64]">AI智能招聘助手</h1>
      <p className="mx-auto mb-0 mt-4 max-w-2xl text-[15px] leading-7 text-[#60789d]">
        我可以帮你生成 JD、筛选简历、出面试题、编排招聘工作流，还可以回答招聘相关问题。
      </p>
    </section>

    {/* 卡片区域：根据选中的底部按钮动态变化；AI筛简历模式下隐藏 */}
    {selectedFeature !== "CANDIDATE_SCREENING" && (
      selectedFeature === "JD_GENERATION" ? (
        // JD生成时：3个JD选项卡
        <section className="mx-auto mt-7 grid w-full max-w-4xl gap-3 sm:grid-cols-3">
          {/* 选项卡1：参考JD模版生成 */}
          <button
            type="button"
            onClick={handleUseTemplate}
            className="group flex flex-col items-start rounded-2xl border border-[#e5edf5] bg-white p-4 text-left shadow-[0_5px_18px_rgba(38,82,130,0.04)] transition hover:-translate-y-0.5 hover:border-[#9ddfce] hover:shadow-[0_10px_28px_rgba(32,137,131,0.10)]"
          >
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-[#eef5ff] text-[#176ce5] transition group-hover:bg-[#dceaff]">
              <Copy size={17} />
            </span>
            <span className="mt-3 text-sm font-bold text-[#203b68]">参考JD模版生成</span>
            <span className="mt-1 text-xs leading-5 text-[#7083a1]">一键填入完整模版示例，可在此基础上直接修改</span>
          </button>

          {/* 选项卡2：从职位库复制生成 */}
          <button
            type="button"
            onClick={handleOpenJobPicker}
            className="group flex flex-col items-start rounded-2xl border border-[#e5edf5] bg-white p-4 text-left shadow-[0_5px_18px_rgba(38,82,130,0.04)] transition hover:-translate-y-0.5 hover:border-[#9ddfce] hover:shadow-[0_10px_28px_rgba(32,137,131,0.10)]"
          >
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-[#f2fbf6] text-[#0f996a] transition group-hover:bg-[#dcf5e8]">
              <FolderOpen size={17} />
            </span>
            <span className="mt-3 text-sm font-bold text-[#203b68]">从职位库复制生成</span>
            <span className="mt-1 text-xs leading-5 text-[#7083a1]">参考职位库中已有职位，编辑新的 JD 内容</span>
          </button>

          {/* 选项卡3：根据上传文件生成 */}
          <button
            type="button"
            onClick={handleUploadClick}
            className="group flex flex-col items-start rounded-2xl border border-[#e5edf5] bg-white p-4 text-left shadow-[0_5px_18px_rgba(38,82,130,0.04)] transition hover:-translate-y-0.5 hover:border-[#9ddfce] hover:shadow-[0_10px_28px_rgba(32,137,131,0.10)]"
          >
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-[#fff6ee] text-[#df7d2b] transition group-hover:bg-[#ffe7d2]">
              <Upload size={17} />
            </span>
            <span className="mt-3 text-sm font-bold text-[#203b68]">根据上传文件生成</span>
            <span className="mt-1 text-xs leading-5 text-[#7083a1]">根据本地 PDF/Word/TXT 文档内容生成 JD</span>
          </button>
        </section>
      ) : (
        // 未选功能时：原来的4个引导卡片
        <section className="mx-auto mt-7 grid w-full max-w-4xl gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {recruitmentStarters.map((starter) => {
            const Icon = starter.icon;
            const content = <><span className="grid h-8 w-8 place-items-center rounded-xl bg-[#edf8f7] text-[#159d8e] transition group-hover:bg-[#dcf5ef]"><Icon size={16}/></span>
              <span className="mt-2 block text-sm font-bold text-[#203b68]">{starter.title}</span>
              <span className="mt-1 block text-xs leading-4 text-[#7083a1]">{starter.description}</span></>;
            const className = "group rounded-2xl border border-[#e5edf5] bg-white px-4 py-3 text-left shadow-[0_5px_18px_rgba(38,82,130,0.04)] transition hover:-translate-y-0.5 hover:border-[#9ddfce] hover:shadow-[0_10px_28px_rgba(32,137,131,0.10)]";
            return <button key={starter.title} type="button" onClick={() => chooseStarter(starter)} className={className}>
              {content}
            </button>;
          })}
        </section>
      )
    )}

    <section className="mx-auto mt-[clamp(28px,7vh,76px)] w-full max-w-4xl">
      <div className="rounded-[30px] border border-[#dbe6f0] bg-white px-6 pb-5 pt-5 shadow-[0_18px_55px_rgba(38,72,116,0.13)] focus-within:border-[#8fcfca] focus-within:shadow-[0_20px_60px_rgba(28,130,126,0.15)]">
        {/* AI筛简历：输入框紧挨着上方的职位选择区（单选） */}
        {selectedFeature === "CANDIDATE_SCREENING" && (
          <div className="mb-3 flex flex-wrap items-center gap-2 border-b border-[#edf1f5] pb-3">
            {selectedScreeningJob ? (
              <div className="flex items-center gap-2 rounded-lg border border-[#cfe4f5] bg-[#f0faff] px-3 py-2">
                <BriefcaseBusiness className="text-[#15b6b3]" size={15} />
                <Link
                  href="/jobs"
                  className="text-sm font-semibold text-[#176ce5] underline-offset-2 hover:underline"
                  target="_blank"
                  rel="noreferrer"
                >
                  {selectedScreeningJob.title}
                  <span className="ml-1 text-xs font-normal text-[#657996]">
                    {selectedScreeningJob.companyName ? ` · ${selectedScreeningJob.companyName}` : ""}
                    {selectedScreeningJob.location ? ` · ${selectedScreeningJob.location}` : ""}
                  </span>
                </Link>
                <button
                  type="button"
                  onClick={handleRemoveScreeningJob}
                  className="ml-1 grid h-5 w-5 place-items-center rounded-full text-[#98a9bd] hover:bg-white hover:text-[#cf3030]"
                  aria-label="移除所选职位"
                >
                  <X size={12} />
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => void handleOpenScreeningJobPicker()}
                className="flex items-center gap-2 rounded-lg border border-dashed border-[#9ddfce] bg-[#f5fbf9] px-4 py-2 text-sm font-semibold text-[#0a8f8b] hover:bg-[#e9fbf9]"
              >
                <FolderOpen size={15} /> 选择职位（职位库）
              </button>
            )}
          </div>
        )}

        {/* 已上传文件展示区域：输入框上方 */}
        {uploadedFiles.length > 0 && (
          <div className="mb-3 flex flex-wrap gap-2 border-b border-[#edf1f5] pb-3">
            {uploadedFiles.map((item) => (
              <div key={item.id} className="flex items-center gap-2 rounded-lg border border-[#e0e8f0] bg-[#f5faff] px-3 py-2 text-xs text-[#344f75]">
                <span className="grid h-6 w-6 place-items-center rounded bg-white text-[#15b6b3]"><File size={13} /></span>
                <span className="max-w-[180px] truncate">{item.name}</span>
                <span className="text-[#98a9bd]">{formatFileSize(item.size)}</span>
                <button
                  type="button"
                  onClick={() => handleRemoveFile(item.id)}
                  className="ml-1 grid h-5 w-5 place-items-center rounded-full text-[#98a9bd] hover:bg-white hover:text-[#cf3030]"
                  aria-label="删除文件"
                >
                  <X size={12} />
                </button>
              </div>
            ))}
          </div>
        )}

        <textarea
          value={requirement}
          onChange={(event) => onRequirement(event.target.value)}
          onKeyDown={inputKeyDown}
          maxLength={20000}
          className="min-h-[86px] w-full resize-none border-0 bg-transparent text-[16px] leading-7 text-[#344f75] outline-none placeholder:text-[#98a9bd] focus-visible:outline-white"
          placeholder={
            selectedFeature === "CANDIDATE_SCREENING"
              ? "告诉我你的筛选条件，硬性条件、软性条件。（回车发送，Ctrl+A 换行）"
              : "告诉我你的招聘需求，或上传 JD / 简历开始智能招聘（回车发送，Ctrl+A 换行）"
          }
          aria-label="招聘需求"
        />
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[#edf1f5] pt-4">
          <div className="flex flex-wrap items-center gap-2 text-sm text-[#536d91]">
            <button type="button" onClick={()=>{onFeature("JD_GENERATION");}} className={`rounded-full border px-3 py-2 ${selectedFeature === "JD_GENERATION" ? "border-[#15b6b3] bg-[#e9fbf9] text-[#0a8f8b]" : "border-[#e0e8f0] hover:bg-[#f5faff]"}`}><FileText className="mr-1 inline text-[#15b6b3]" size={16}/>JD生成</button>
            <button type="button" onClick={()=>{onFeature("CANDIDATE_SCREENING");}} className={`rounded-full border px-3 py-2 ${selectedFeature === "CANDIDATE_SCREENING" ? "border-[#15b6b3] bg-[#e9fbf9] text-[#0a8f8b]" : "border-[#e0e8f0] hover:bg-[#f5faff]"}`}><UsersRound className="mr-1 inline text-[#15b6b3]" size={16}/>AI筛简历</button>
            <button type="button" onClick={()=>{onFeature("INTERVIEW_KIT");onSection("interviews")}} className="rounded-full border border-[#e0e8f0] px-3 py-2 hover:bg-[#f5faff]"><ListChecks className="mr-1 inline text-[#15b6b3]" size={16}/>AI面试题</button>
          </div>
          <div className="flex items-center gap-3">
            <span className="min-w-24 text-right text-xs font-semibold text-[#0a9a66]" aria-live="polite">{quoteFor(selectedFeature, requirement)}</span>
            {/* 添加附件按钮：点击打开文件选择 */}
            <button type="button" onClick={handleUploadClick} className="grid h-9 w-9 place-items-center rounded-full border-2 border-[#24456f] text-[#24456f] hover:bg-[#f5faff]" aria-label="添加附件"><Plus size={18}/></button>
            <button type="button" onClick={onCreate} disabled={busy || !requirement.trim()} className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-gradient-to-br from-[#1fbec3] to-[#0aa6a2] text-white shadow-[0_5px_14px_rgba(22,172,168,0.25)] transition hover:scale-105 disabled:cursor-not-allowed disabled:from-[#dce3ea] disabled:to-[#dce3ea] disabled:shadow-none" aria-label="发送并开始执行">{busy ? <Loader2 className="animate-spin" size={16}/> : <Send size={17}/>}</button>
          </div>
        </div>
      </div>

      {/* 隐藏的文件上传 input */}
      <input
        id="jd-file-upload-input"
        ref={fileInputRef[0] ? (el) => { fileInputRef[1](el); } : undefined}
        type="file"
        multiple
        accept=".pdf,.doc,.docx,.txt,.md"
        className="hidden"
        onChange={handleFileChange}
      />
    </section>

    {/* 职位库选择弹窗 */}
    {showJobPicker && (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
        <div className="w-full max-w-2xl overflow-hidden rounded-2xl bg-white shadow-2xl">
          {/* 弹窗头部 */}
          <div className="flex items-center justify-between border-b border-[#e2ebf5] px-5 py-4">
            <div>
              <h3 className="m-0 text-lg font-bold text-[#173568]">从职位库选择</h3>
              <p className="mb-0 mt-1 text-xs text-[#657996]">搜索并选中一个职位，将其链接与信息带入输入框</p>
            </div>
            <button
              type="button"
              onClick={() => setShowJobPicker(false)}
              className="grid h-8 w-8 place-items-center rounded-lg text-[#657996] hover:bg-[#f3f5f8] hover:text-[#173568]"
              aria-label="关闭"
            >
              <X size={18} />
            </button>
          </div>

          {/* 搜索框 */}
          <div className="border-b border-[#e2ebf5] px-5 py-3">
            <div className="flex items-center gap-2 rounded-lg border border-[#cbdced] px-3 py-2 focus-within:border-[#4a8be8]">
              <Search size={15} className="text-[#98a9bd]" />
              <input
                value={jobSearch}
                onChange={(event) => { setJobSearch(event.target.value); void loadJobList(event.target.value); }}
                placeholder="搜索职位名称、公司或地点..."
                className="flex-1 border-0 bg-transparent text-sm outline-none placeholder:text-[#98a9bd]"
              />
              {jobLoading && <Loader2 className="animate-spin text-[#176ce5]" size={15} />}
            </div>
          </div>

          {/* 职位列表 */}
          <div className="max-h-[420px] min-h-[240px] overflow-y-auto">
            {jobLoading && jobList.length === 0 ? (
              <div className="flex h-60 items-center justify-center text-sm text-[#657996]">
                <Loader2 className="mr-2 animate-spin" size={16} />正在加载职位列表...
              </div>
            ) : jobList.length === 0 ? (
              <div className="flex h-60 flex-col items-center justify-center text-center text-sm text-[#657996]">
                <FolderOpen size={26} className="mb-2 text-[#cbdced]" />
                暂无职位数据，请先在职位库创建职位
              </div>
            ) : (
              <ul className="divide-y divide-[#edf1f5]">
                {jobList.map((job) => (
                  <li key={job.id}>
                    <button
                      type="button"
                      onClick={() => handleSelectJob(job)}
                      className="flex w-full items-start gap-3 px-5 py-3 text-left transition hover:bg-[#f5faff]"
                    >
                      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-[#edf8f7] text-[#159d8e]">
                        <BriefcaseBusiness size={16} />
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="truncate text-sm font-bold text-[#203b68]">{job.title}</span>
                          <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${job.status === "ACTIVE" ? "bg-[#e7f8f1] text-[#07885b]" : job.status === "CLOSED" ? "bg-[#f3f5f8] text-[#657996]" : "bg-[#fff6d8] text-[#9a6b00]"}`}>
                            {job.status === "ACTIVE" ? "招聘中" : job.status === "CLOSED" ? "已关闭" : "草稿"}
                          </span>
                        </div>
                        <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-[#7083a1]">
                          <span>{job.companyName || "-"}</span>
                          <span>{job.location || "-"}</span>
                          <span>{job.experienceLevel || ""}</span>
                          <span>{job.education || ""}</span>
                        </div>
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* 弹窗底部 */}
          <div className="flex items-center justify-between border-t border-[#e2ebf5] bg-[#f9fbfe] px-5 py-3">
            <span className="text-xs text-[#7083a1]">共 {jobList.length} 条职位</span>
            <button
              type="button"
              onClick={() => setShowJobPicker(false)}
              className="rounded-lg border border-[#cbdced] bg-white px-4 py-2 text-sm text-[#36527f] hover:bg-[#f3f5f8]"
            >取消</button>
          </div>
        </div>
      </div>
    )}
  </div>;
}

/** 简历筛选六维评估默认值（与后端 RecruitmentService.defaultScreeningDimsJson 保持一致） */
const DEFAULT_SCREENING_DIMENSIONS: ScreeningDimension[] = [
  {
    id: "basic_info",
    name: "基本信息",
    weight: 10,
    description: "年龄、性别、所在地、期望薪资、到岗时间等基本信息，是否满足岗位硬性门槛与到岗节奏。",
  },
  {
    id: "education",
    name: "教育背景",
    weight: 15,
    description: "学历层次、学校等级、专业相关性，在校期间的成绩、奖学金、科研与项目经历。",
  },
  {
    id: "career",
    name: "职业履历",
    weight: 25,
    description: "工作年限、行业/岗位匹配度、公司平台层级、岗位稳定性、晋升速度与管理经验。",
  },
  {
    id: "skills",
    name: "专业技能",
    weight: 25,
    description: "岗位所需的技术栈、工具、方法论、语言与证书，熟练度与实战落地经验的匹配程度。",
  },
  {
    id: "projects",
    name: "项目经验",
    weight: 15,
    description: "主导或核心参与的项目规模、复杂度、业务结果，以及与目标岗位的职责相似性。",
  },
  {
    id: "motivation",
    name: "求职动机",
    weight: 10,
    description: "求职原因、稳定性、薪酬期望、文化契合度、团队配合意愿与长期发展潜力。",
  },
];

/** 解析后端 screening_dims_json（JSON 字符串），失败时返回默认值。 */
function parseDimensionsOrDefaults(raw: string | null | undefined): ScreeningDimension[] {
  if (!raw) return DEFAULT_SCREENING_DIMENSIONS;
  try {
    const parsed = JSON.parse(raw) as ScreeningDimension[];
    if (!Array.isArray(parsed)) return DEFAULT_SCREENING_DIMENSIONS;
    // 以默认 id 集合为基准合并，避免缺列 / 非法字段
    return DEFAULT_SCREENING_DIMENSIONS.map((defRow) => {
      const saved = parsed.find((p) => p.id === defRow.id);
      if (!saved) return defRow;
      const weight = Number.isFinite(+saved.weight) ? Math.max(0, Math.min(100, +saved.weight)) : defRow.weight;
      const description = typeof saved.description === "string" && saved.description.length > 0
        ? saved.description : defRow.description;
      return { ...defRow, weight, description };
    });
  } catch {
    return DEFAULT_SCREENING_DIMENSIONS;
  }
}

/** AI 简历筛选主页：左侧六维评估列表（可编辑 + 后端持久化），右侧仍是 AI 招聘助手对话框（父级布局已提供两列） */
function ScreeningEvaluationPanel({
  task,
  workspaceId,
  initialScreeningDimsJson,
}: {
  task: TaskSummary | null;
  workspaceId: string;
  /** 父组件从 detail.screeningDimsJson 直接带入的后端初始值，可省去首次拉取 */
  initialScreeningDimsJson: string | null;
}) {
  // 六维评估项列表
  const [dimensions, setDimensions] = useState<ScreeningDimension[]>(() =>
    parseDimensionsOrDefaults(initialScreeningDimsJson),
  );
  // 正在编辑的行 id，null 表示全部只读
  const [editingId, setEditingId] = useState<string | null>(null);
  // 编辑中的临时值
  const [draft, setDraft] = useState<ScreeningDimension | null>(null);
  // 保存成功提示（短暂显示）
  const [saveFlash, setSaveFlash] = useState(false);
  // 正在保存的行（用于展示 loading + 禁用按钮）
  const [saving, setSaving] = useState(false);
  // 保存失败横幅
  const [error, setError] = useState<string | null>(null);

  const taskId = task?.id ?? null;

  /** 组件挂载后：若父级没带初始 screeningDimsJson，则首次从后端拉取保证与 DB 一致 */
  useEffect(() => {
    if (!workspaceId || !taskId) return;
    // 父级已经传入过合法初始值（不是空数组默认占位）就不重复拉
    if (initialScreeningDimsJson && initialScreeningDimsJson !== "[]") return;

    let cancelled = false;
    setError(null);
    fetchScreeningDimensions(workspaceId, taskId)
      .then((res) => {
        if (cancelled) return;
        setDimensions(parseDimensionsOrDefaults(res.dimensionsJson));
      })
      .catch((err) => {
        if (cancelled) return;
        setDimensions(DEFAULT_SCREENING_DIMENSIONS);
        const message = err instanceof ApiError ? err.message : "读取筛选配置失败";
        setError(message);
      });
    return () => { cancelled = true; };
  }, [workspaceId, taskId, initialScreeningDimsJson]);

  const totalWeight = dimensions.reduce((sum, item) => sum + item.weight, 0);

  const startEdit = (row: ScreeningDimension) => {
    setEditingId(row.id);
    setDraft({ ...row });
  };

  const cancelEdit = () => {
    setEditingId(null);
    setDraft(null);
  };

  /** 点击行内「保存」：先夹紧 weight，再调后端 API，成功后整体回写 state + 闪提示 */
  const saveEdit = async () => {
    if (!draft || !workspaceId || !taskId) return;
    setSaving(true);
    setError(null);
    try {
      const weight = Math.max(0, Math.min(100, Number.isFinite(+draft.weight) ? +draft.weight : 0));
      const next = dimensions.map((item) => (item.id === draft.id ? { ...draft, weight } : item));
      const saved = await updateScreeningDimensions(workspaceId, taskId, next);
      setDimensions(parseDimensionsOrDefaults(saved.dimensionsJson));
      setEditingId(null);
      setDraft(null);
      setSaveFlash(true);
      window.setTimeout(() => setSaveFlash(false), 1200);
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "保存失败，请稍后再试";
      setError(message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div>
      {/* 头部：任务名 + 权重合计提示 + 保存提示 + 错误横幅 */}
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#e1ebf5] pb-4">
        <div>
          <span className="inline-flex items-center gap-1 rounded-full bg-[#e9f8ff] px-2 py-1 text-[11px] font-semibold text-[#1778df]">
            <UsersRound size={13} /> 简历筛选
          </span>
          <h2 className="mb-0 mt-2 text-xl font-bold text-[#102d64]">
            {task?.title ?? "AI 简历筛选评估"}
          </h2>
          <p className="mb-0 mt-2 text-sm leading-5 text-[#60799f]">
            下方为 AI 生成的六维人岗匹配评估项，点击每行右侧「编辑」可调整权重与说明；修改会保存到后端，跨设备打开本任务都可恢复。
          </p>
        </div>
        <div className="flex items-center gap-2">
          {saveFlash && (
            <span className="inline-flex items-center gap-1 rounded-lg bg-[#eefbf5] px-2.5 py-1.5 text-xs font-semibold text-[#087a54]">
              <CheckCircle2 size={13} /> 已保存
            </span>
          )}
          <div className={`rounded-lg border px-3 py-2 text-xs font-semibold ${
            totalWeight === 100 ? "border-[#c3e9d9] bg-[#eefbf5] text-[#087a54]" : "border-[#ffe1b3] bg-[#fff8ec] text-[#a86b10]"
          }`}>
            权重合计 {totalWeight}%（建议 100%）
          </div>
        </div>
      </div>

      {error && (
        <div className="mt-4 flex items-start gap-2 rounded-lg border border-[#fccccc] bg-[#fff5f5] px-3 py-2 text-xs font-semibold text-[#b12323]">
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          <span className="min-w-0">{error}</span>
        </div>
      )}

      {/* 表格 */}
      <div className="mt-5 overflow-hidden rounded-xl border border-[#e2ebf5]">
        <div className="grid grid-cols-[minmax(140px,1.2fr)_96px_minmax(0,2.5fr)_92px] bg-[#f5faff] px-4 py-3 text-xs font-semibold text-[#3b5580]">
          <span>评估项</span>
          <span className="text-center">评估权重</span>
          <span>评估说明</span>
          <span className="text-right">操作</span>
        </div>
        <ul className="divide-y divide-[#edf1f5]">
          {dimensions.map((row) => {
            const isEditing = editingId === row.id;
            const editingRow = isEditing ? draft! : row;
            return (
              <li key={row.id} className="grid grid-cols-[minmax(140px,1.2fr)_96px_minmax(0,2.5fr)_92px] items-center gap-2 px-4 py-3 hover:bg-[#fafcff]">
                {/* 评估项名称（只读） */}
                <div className="flex items-center gap-2">
                  <span className="grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-[#edf8f7] text-[#159d8e]">
                    <Filter size={13} />
                  </span>
                  <span className="text-sm font-semibold text-[#173568]">{row.name}</span>
                </div>

                {/* 评估权重 */}
                <div className="justify-self-center">
                  {isEditing ? (
                    <div className="flex items-center">
                      <input
                        type="number"
                        min={0}
                        max={100}
                        value={editingRow.weight}
                        onChange={(event) => setDraft({ ...editingRow, weight: +event.target.value })}
                        disabled={saving}
                        className="h-8 w-14 rounded-md border border-[#cbdced] px-2 text-center text-sm outline-none focus:border-[#4a8be8] disabled:bg-[#f3f6fb]"
                      />
                      <span className="ml-1 text-xs text-[#657996]">%</span>
                    </div>
                  ) : (
                    <span className="inline-flex items-center justify-center rounded-full bg-[#f2fbf6] px-2.5 py-1 text-xs font-bold text-[#0a8f6a]">
                      {row.weight}%
                    </span>
                  )}
                </div>

                {/* 评估说明 */}
                <div className="min-w-0">
                  {isEditing ? (
                    <textarea
                      value={editingRow.description}
                      onChange={(event) => setDraft({ ...editingRow, description: event.target.value })}
                      rows={3}
                      maxLength={500}
                      disabled={saving}
                      className="w-full rounded-md border border-[#cbdced] px-2.5 py-2 text-xs leading-5 text-[#203b68] outline-none focus:border-[#4a8be8] disabled:bg-[#f3f6fb]"
                    />
                  ) : (
                    <p className="mb-0 line-clamp-3 whitespace-pre-wrap text-xs leading-5 text-[#55709d]">
                      {row.description}
                    </p>
                  )}
                </div>

                {/* 操作列 */}
                <div className="justify-self-end">
                  {isEditing ? (
                    <div className="flex items-center gap-1">
                      <button
                        type="button"
                        onClick={saveEdit}
                        disabled={saving}
                        className="rounded-md bg-[#159d8e] px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-[#128a7d] disabled:bg-[#a0d2cc]"
                      >
                        {saving ? "保存中…" : "保存"}
                      </button>
                      <button
                        type="button"
                        onClick={cancelEdit}
                        disabled={saving}
                        className="rounded-md border border-[#cbdced] bg-white px-2.5 py-1.5 text-xs font-semibold text-[#3b5580] hover:bg-[#f5faff] disabled:bg-[#f5faff] disabled:text-[#9fb2cf]"
                      >
                        取消
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      onClick={() => startEdit(row)}
                      className="flex items-center gap-1 rounded-md border border-[#d2e4f5] bg-[#f5faff] px-2.5 py-1.5 text-xs font-semibold text-[#176ce5] hover:bg-[#eaf3ff]"
                    >
                      <Pencil size={12} /> 编辑
                    </button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}

function quoteFor(feature: SelectedFeature, content: string) {
  if (feature === "JD_GENERATION") return "预计 ¥0.80 / 次";
  if (feature === "CANDIDATE_SCREENING") return "¥0.80 / 成功候选人";
  if (feature === "INTERVIEW_KIT") return "预计 ¥0.80 / 题包";
  if (feature === "RESUME_PARSING") return "预计 ¥0.80 / 份";
  let tokens = 0;
  for (const character of content) tokens += /[\u4e00-\u9fff]/.test(character) ? 0.6 : 0.3;
  const yuan = Math.max(0.01, tokens * 0.2 / 1_000_000);
  return `对话预估 ¥${yuan.toFixed(2)}`;
}

function resolveTaskTitle(inputTitle: string, requirement: string, feature: SelectedFeature = null) {
  const explicit = inputTitle.trim();
  if (explicit) return explicit;
  const match = requirement.match(/(?:招聘|招募|招)(?:一名|1名)?\s*([^，。；、,.!?！？]{2,40})/);
  const role = match?.[1]?.trim();
  if (role) return `${role}招聘`;
  // 根据所选功能返回对应默认任务名
  if (feature === "CANDIDATE_SCREENING") return "简历筛选任务";
  if (feature === "INTERVIEW_KIT") return "AI面试题任务";
  if (feature === "RESUME_PARSING") return "简历解析任务";
  return "智能招聘任务";
}

function insertLineBreak(target: HTMLTextAreaElement, apply: (update: (current: string) => string) => void) {
  const start = target.selectionStart;
  const end = target.selectionEnd;
  apply(current => `${current.slice(0, start)}\n${current.slice(end)}`);
  requestAnimationFrame(() => target.setSelectionRange(start + 1, start + 1));
}

function CreateTaskPanel({ title, requirement, busy, onTitle, onRequirement, onCreate }: { title: string; requirement: string; busy: boolean; onTitle: (value: string) => void; onRequirement: (value: string) => void; onCreate: () => void }) {
  return <div className="mx-auto max-w-2xl py-8">
    <span className="grid h-12 w-12 place-items-center rounded-2xl bg-gradient-to-br from-[#247aff] to-[#17bd91] text-white"><Sparkles size={23}/></span>
    <h2 className="mb-0 mt-5 text-2xl font-bold text-[#102d64]">描述本次招聘需求</h2><p className="mt-2 text-sm leading-6 text-[#60799f]">先创建招聘任务，需求和后续对话会完整保存；AI 结果仅作为草稿，必须由你确认后才进入职位库。</p>
    <label className="mt-6 block text-sm font-semibold text-[#294a7e]">任务名称<input className="mt-2 h-11 w-full rounded-lg border border-[#c7d9eb] px-3 font-normal outline-none focus:border-[#3d83e8]" value={title} onChange={(event) => onTitle(event.target.value)} placeholder="例如：高级 Java 开发工程师招聘" maxLength={200}/></label>
    <label className="mt-4 block text-sm font-semibold text-[#294a7e]">招聘需求<textarea className="mt-2 min-h-[190px] w-full rounded-lg border border-[#c7d9eb] px-3 py-3 font-normal leading-6 outline-none focus:border-[#3d83e8]" value={requirement} onChange={(event) => onRequirement(event.target.value)} placeholder="请描述职位、业务背景、工作地点、经验学历、核心技能和招聘偏好..." maxLength={20000}/></label>
    <button type="button" className="primary-button mt-5" disabled={busy || !title.trim() || !requirement.trim()} onClick={onCreate}>{busy ? <Loader2 className="animate-spin" size={16}/> : <Plus size={16}/>}创建招聘任务</button>
  </div>;
}

function JdWaitingState({ task, running }: { task: TaskSummary; running: boolean }) {
  return <div className="mx-auto max-w-xl py-16 text-center">
    <span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-[#e9f8ff] text-[#1778df]"><MessageSquareText size={27}/></span>
    <h2 className="mb-0 mt-5 text-xl font-bold text-[#173568]">{task.title}</h2><p className="mx-auto mt-3 max-w-md text-sm leading-6 text-[#60799f]">{running ? "AI 正在生成结构化 JD，结果完成后会自动填入此处。" : "可在右侧继续补充招聘需求；从首页选择 JD 生成功能并发送后，系统会直接开始生成。"}</p>
  </div>;
}

function JdEditor({ draft, busy, confirmed, editing, jobId, onEdit, onChange, onSave, onConfirm }: { draft: JdDraft; busy: boolean; confirmed: boolean; editing: boolean; jobId: string | null; onEdit: () => void; onChange: (draft: JdDraft) => void; onSave: () => void; onConfirm: () => void }) {
  const update = (key: keyof JdDraft, value: string) => onChange({ ...draft, [key]: value });
  const editable = !confirmed || editing;
  return <div>
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#e1ebf5] pb-4"><div><span className={`inline-flex items-center gap-1 rounded-full px-2 py-1 text-[11px] font-semibold ${confirmed ? "bg-[#ddf8ed] text-[#07875b]" : "bg-[#fff6d8] text-[#9a6b00]"}`}>{confirmed ? <CheckCircle2 size={13}/> : <TriangleAlert size={13}/>} {confirmed ? "已确认版本" : `草稿 revision ${draft.revision}`}</span><h2 className="mb-0 mt-2 text-xl font-bold text-[#102d64]">结构化 JD</h2></div><div className="flex gap-2">{confirmed ? <>{editing && <button type="button" className="outline-button" disabled={busy} onClick={onSave}><Save size={15}/>保存修改</button>} {!editing && <button type="button" className="outline-button" disabled={busy} onClick={onEdit}><Pencil size={15}/>编辑 JD</button>}{jobId && <Link href="/jobs" className="primary-button"><BriefcaseBusiness size={15}/>查看职位库</Link>}</> : <><button type="button" className="outline-button" disabled={busy} onClick={onSave}><Save size={15}/>保存草稿</button><button type="button" className="primary-button" disabled={busy} onClick={onConfirm}><CheckCircle2 size={15}/>确认并发布</button></>}</div></div>
    {!confirmed && draft.warnings.length > 0 && <div className="mt-4 rounded-lg border border-[#f6d58a] bg-[#fffaf0] px-4 py-3"><p className="m-0 flex items-center gap-2 text-xs font-semibold text-[#8d6200]"><TriangleAlert size={15}/>发布前待确认</p><ul className="mb-0 mt-2 list-disc space-y-1 pl-5 text-xs text-[#8a6a28]">{draft.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul></div>}
    <div className="mt-4 grid gap-3 md:grid-cols-2"><EditorField label="职位名称" value={draft.title} disabled={!editable} onChange={(value) => update("title", value)}/><EditorField label="企业名称" value={draft.companyName} disabled={!editable} onChange={(value) => update("companyName", value)}/><EditorField label="工作地点" value={draft.location} disabled={!editable} onChange={(value) => update("location", value)}/><EditorField label="经验要求" value={draft.experienceLevel} disabled={!editable} onChange={(value) => update("experienceLevel", value)}/><EditorField label="学历要求" value={draft.education} disabled={!editable} onChange={(value) => update("education", value)}/><EditorField label="用工类型" value={draft.jobType} disabled={!editable} onChange={(value) => update("jobType", value)}/></div>
    <EditorArea label="岗位职责" value={draft.responsibilities} disabled={!editable} onChange={(value) => update("responsibilities", value)}/><EditorArea label="任职要求" value={draft.requirements} disabled={!editable} onChange={(value) => update("requirements", value)}/><EditorArea label="关键技能" value={draft.skills} disabled={!editable} onChange={(value) => update("skills", value)}/><EditorArea label="人才画像" value={draft.talentProfile} disabled={!editable} onChange={(value) => update("talentProfile", value)}/>
  </div>;
}

function EditorField({ label, value, disabled, onChange }: { label: string; value: string; disabled: boolean; onChange: (value: string) => void }) { return <label className="block text-xs font-semibold text-[#36527f]">{label}<input value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)} className="mt-1.5 h-10 w-full rounded-lg border border-[#cbdced] px-3 text-sm font-normal outline-none disabled:bg-[#f5f8fb]"/></label>; }
function EditorArea({ label, value, disabled, onChange }: { label: string; value: string; disabled: boolean; onChange: (value: string) => void }) { return <label className="mt-4 block text-xs font-semibold text-[#36527f]">{label}<textarea value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)} className="mt-1.5 min-h-[110px] w-full rounded-lg border border-[#cbdced] px-3 py-2 text-sm font-normal leading-6 outline-none disabled:bg-[#f5f8fb]"/></label>; }

function WorkflowSection({ title, description }: { title: string; description: string }) { return <div className="grid min-h-[520px] place-items-center text-center"><div className="max-w-md"><span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-[#eaf8f5] text-[#169b83]"><Sparkles size={26}/></span><h2 className="mb-0 mt-5 text-xl font-bold text-[#173568]">{title}</h2><p className="mt-3 text-sm leading-6 text-[#60799f]">{description}</p><p className="mt-5 text-xs text-[#7187a8]">该功能正在此工作台分区中展开；右侧 AI 助手会持续提供操作提示。</p></div></div>; }
function StatePage({ icon, text }: { icon: React.ReactNode; text: string }) { return <AppShell activeItem="智能招聘"><div className="flex h-64 flex-col items-center justify-center gap-3 text-[#6780a3]"><span className="text-[#2878da]">{icon}</span><p className="text-sm">{text}</p></div></AppShell>; }
function messageOf(cause: unknown) { return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试"; }
