"use client";

import {
  AlertCircle, Bot, BriefcaseBusiness, CheckCircle2, CircleDollarSign, Copy, Pencil,
  FileText, Filter, ListChecks, Loader2, MessageSquareText, Plus, Save, Send, Sparkles,
  TriangleAlert, UsersRound, Search, X, Upload, FolderOpen, File, ChevronDown,
} from "lucide-react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import { ScreeningWorkspace } from "@/components/screening/screening-workspace";
import {
  confirmJdDraft, createTask, fetchTask, fetchTasks, generateJd, sendMessage,
  streamJdRunEvents, updateJdDraft,
  type JdDraft, type TaskDetail, type TaskSummary,
} from "@/lib/recruitment-api";
import { fetchJobs, type Job } from "@/lib/job-api";
import { useWorkspace } from "@/lib/workspace-context";

type WorkspaceSection = "home" | "jd" | "candidates" | "screening" | "interviews";
type SelectedFeature = "JD_GENERATION" | "CANDIDATE_SCREENING" | "INTERVIEW_KIT" | "RESUME_PARSING" | null;
type RecruitmentAgent = "RECRUITMENT_ASSISTANT" | "TALENT_PLANNER";
type JdGenerationSource = "TEMPLATE" | "JOB_LIBRARY" | "UPLOAD" | null;

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
        await sendMessage(workspaceId, detail.task.id, message, draft?.id);
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

  async function handleSave(nextDraft = draft) {
    if (!workspaceId || !detail || !nextDraft) return;
    await run(async () => {
      const next = await updateJdDraft(workspaceId, detail.task.id, nextDraft);
      setDetail(next);
      setDraft(next.jdDrafts.find(item => item.id === nextDraft.id) ?? next.jdDraft);
      setEditingPublishedJd(false);
    });
  }

  async function handleConfirm(targetDraft = draft) {
    if (!workspaceId || !detail || !targetDraft) return;
    await run(async () => {
      await confirmJdDraft(workspaceId, detail.task.id, targetDraft.id);
      const next = await fetchTask(workspaceId, detail.task.id);
      setDetail(next);
      setDraft(next.jdDrafts.find(item => item.id === targetDraft.id) ?? next.jdDraft);
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
        {section === "candidates" ? <WorkflowSection title="上传并解析简历" description="请在此上传 PDF、DOCX 简历，系统将解析候选人信息并在当前工作空间保存。"/> : section === "screening" ? detail ? <ScreeningWorkspace embedded recruitmentTaskId={detail.task.id} initialJobId={detail.task.jobId} /> : <WorkflowSection title="AI 简历筛选" description="请先创建筛选任务后再配置方案与候选人范围。"/> : section === "interviews" ? <WorkflowSection title="AI 面试出题" description="选择候选人，生成可编辑、可确认的结构化面试题包。"/> : !detail ? <CreateTaskPanel title={newTitle} requirement={newRequirement} busy={busy} onTitle={setNewTitle} onRequirement={setNewRequirement} onCreate={() => void handleCreate()} /> : detail.jdDrafts?.length ? <div className="space-y-5">{detail.jdDrafts.map((panel) => <section key={panel.id} className="rounded-xl border border-[#d6e5f5] bg-[#fbfdff] p-4"><JdEditor draft={panel} busy={busy} confirmed={panel.status === "CONFIRMED"} editing={editingPublishedJd && draft?.id === panel.id} jobId={detail.task.jobId} onEdit={() => { setDraft(panel); setEditingPublishedJd(true); }} onSave={(next) => void handleSave(next)} onConfirm={() => void handleConfirm(panel)} /></section>)}</div> : <JdWaitingState task={detail.task} running={busy || ["QUEUED", "RUNNING"].includes(detail.latestAiRun?.status ?? "")} />}
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
  const [agent, setAgent] = useState<RecruitmentAgent>("RECRUITMENT_ASSISTANT");
  const [agentMenuOpen, setAgentMenuOpen] = useState(false);
  const [jdGenerationSource, setJdGenerationSource] = useState<JdGenerationSource>(null);

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
      <h1 className="mb-0 mt-2 text-[clamp(30px,3.5vw,28px)] font-bold tracking-tight text-[#102d64]">AI智能招聘助手</h1>
      <p className="mx-auto mb-0 mt-4 max-w-2xl text-[15px] leading-7 text-[#60789d]">
        我可以帮你生成 JD、筛选简历、出面试题、编排招聘工作流，还可以回答招聘相关问题。
      </p>
    </section>

    <section className="relative mx-auto mt-7 flex w-full max-w-4xl items-center justify-between gap-4 px-1">
      <div className="flex min-w-0 items-center gap-3"><span className={`grid h-12 w-12 shrink-0 place-items-center rounded-2xl text-white shadow-[0_8px_20px_rgba(27,103,220,0.18)] ${agent === "RECRUITMENT_ASSISTANT" ? "bg-gradient-to-br from-[#1d78f2] to-[#17bd91]" : "bg-gradient-to-br from-[#8b5cf6] to-[#d946ef]"}`}>{agent === "RECRUITMENT_ASSISTANT" ? <Bot size={24}/> : <Sparkles size={23}/>}</span><div><h2 className="m-0 text-lg font-bold text-[#203b68]">{agent === "RECRUITMENT_ASSISTANT" ? "智能招聘助手" : "人才需求规划官"}</h2><p className="mb-0 mt-1 text-xs text-[#7083a1]">{agent === "RECRUITMENT_ASSISTANT" ? "生成 JD、筛选简历、设计面试问题" : "规划企业和项目的人才需求与人才方案"}</p></div></div>
      <button type="button" onClick={() => setAgentMenuOpen(open => !open)} className="inline-flex shrink-0 items-center gap-2 rounded-full border border-[#dbe6f0] bg-white px-4 py-2.5 text-sm font-semibold text-[#36557f] shadow-sm hover:bg-[#f7fbff]"><Sparkles size={16} className="text-[#176ce5]"/>切换智能体<ChevronDown size={16} className={`transition ${agentMenuOpen ? "rotate-180" : ""}`}/></button>
      {agentMenuOpen && <div className="absolute right-0 top-[58px] z-20 w-52 rounded-2xl border border-[#dce7f1] bg-white p-2 shadow-xl"><button type="button" onClick={() => { setAgent("RECRUITMENT_ASSISTANT"); onFeature(null); setAgentMenuOpen(false); }} className={`flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left text-sm ${agent === "RECRUITMENT_ASSISTANT" ? "bg-[#eef7ff] font-semibold text-[#176ce5]" : "text-[#36557f] hover:bg-[#f7fbff]"}`}><Bot size={16}/>智能招聘助手</button><button type="button" onClick={() => { setAgent("TALENT_PLANNER"); onFeature(null); setAgentMenuOpen(false); }} className={`mt-1 flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left text-sm ${agent === "TALENT_PLANNER" ? "bg-[#f8f2ff] font-semibold text-[#7c3aed]" : "text-[#36557f] hover:bg-[#f7fbff]"}`}><Sparkles size={16}/>人才需求规划官</button></div>}
    </section>

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
            {agent === "RECRUITMENT_ASSISTANT" ? <><button type="button" onClick={()=>onFeature("JD_GENERATION")} className={`rounded-full border px-3 py-2 ${selectedFeature === "JD_GENERATION" ? "border-[#15b6b3] bg-[#e9fbf9] text-[#0a8f8b]" : "border-[#e0e8f0] hover:bg-[#f5faff]"}`}><FileText className="mr-1 inline text-[#15b6b3]" size={16}/>JD生成</button><button type="button" onClick={()=>onFeature("CANDIDATE_SCREENING")} className={`rounded-full border px-3 py-2 ${selectedFeature === "CANDIDATE_SCREENING" ? "border-[#15b6b3] bg-[#e9fbf9] text-[#0a8f8b]" : "border-[#e0e8f0] hover:bg-[#f5faff]"}`}><UsersRound className="mr-1 inline text-[#15b6b3]" size={16}/>AI简历筛选</button><button type="button" onClick={()=>{onFeature("INTERVIEW_KIT");onSection("interviews")}} className="rounded-full border border-[#e0e8f0] px-3 py-2 hover:bg-[#f5faff]"><ListChecks className="mr-1 inline text-[#15b6b3]" size={16}/>AI面试出题</button></> : <><button type="button" onClick={()=>onRequirement("请协助我制定企业人才需求规划，包含业务目标、关键岗位、人数、时间节奏和优先级。")} className="rounded-full border border-[#e4d9fb] bg-[#fbf9ff] px-3 py-2 text-[#7044bf] hover:bg-[#f4efff]"><BriefcaseBusiness className="mr-1 inline" size={16}/>企业人才需求规划</button><button type="button" onClick={()=>onRequirement("请协助我构建企业人才画像，包含核心岗位能力、经验背景、文化匹配和人才来源。")} className="rounded-full border border-[#e4d9fb] bg-[#fbf9ff] px-3 py-2 text-[#7044bf] hover:bg-[#f4efff]"><UsersRound className="mr-1 inline" size={16}/>企业人才画像构建</button><button type="button" onClick={()=>onRequirement("请协助我制定业务人才方案，结合业务目标、项目阶段、组织分工和关键人才配置。")} className="rounded-full border border-[#e4d9fb] bg-[#fbf9ff] px-3 py-2 text-[#7044bf] hover:bg-[#f4efff]"><Sparkles className="mr-1 inline" size={16}/>业务人才方案制定</button></>}
          </div>
          <div className="flex items-center gap-3">
            <span className="min-w-24 text-right text-xs font-semibold text-[#0a9a66]" aria-live="polite">{quoteFor(selectedFeature, requirement)}</span>
            {/* 添加附件按钮：点击打开文件选择 */}
            <button type="button" onClick={handleUploadClick} className="grid h-9 w-9 place-items-center rounded-full border-2 border-[#24456f] text-[#24456f] hover:bg-[#f5faff]" aria-label="添加附件"><Plus size={18}/></button>
            <button type="button" onClick={onCreate} disabled={busy || !requirement.trim()} className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-gradient-to-br from-[#1fbec3] to-[#0aa6a2] text-white shadow-[0_5px_14px_rgba(22,172,168,0.25)] transition hover:scale-105 disabled:cursor-not-allowed disabled:from-[#dce3ea] disabled:to-[#dce3ea] disabled:shadow-none" aria-label="发送并开始执行">{busy ? <Loader2 className="animate-spin" size={16}/> : <Send size={17}/>}</button>
          </div>
        </div>
      </div>

      {agent === "RECRUITMENT_ASSISTANT" && selectedFeature === "JD_GENERATION" && <div className="mt-3 divide-y divide-[#e6edf5]">
        <button type="button" onClick={() => { setJdGenerationSource("TEMPLATE"); handleUseTemplate(); }} className={`flex w-full items-center gap-3 px-4 py-3 text-left transition ${jdGenerationSource === "TEMPLATE" ? "bg-[#f0fbf8]" : "hover:bg-[#f8fbff]"}`}><span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-[#eef5ff] text-[#176ce5]"><Copy size={17}/></span><span className="min-w-0"><span className="block text-sm font-bold text-[#203b68]">参考 JD 模版生成</span><span className="mt-0.5 block text-xs text-[#7083a1]">一键填入完整模版示例，可在此基础上直接修改</span></span>{jdGenerationSource === "TEMPLATE" && <CheckCircle2 className="ml-auto shrink-0 text-[#0a9a66]" size={18}/>}</button>
        <button type="button" onClick={() => { setJdGenerationSource("JOB_LIBRARY"); void handleOpenJobPicker(); }} className={`flex w-full items-center gap-3 px-4 py-3 text-left transition ${jdGenerationSource === "JOB_LIBRARY" ? "bg-[#f0fbf8]" : "hover:bg-[#f8fbff]"}`}><span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-[#f2fbf6] text-[#0f996a]"><FolderOpen size={17}/></span><span className="min-w-0"><span className="block text-sm font-bold text-[#203b68]">从职位库复制生成</span><span className="mt-0.5 block text-xs text-[#7083a1]">参考职位库中已有职位，编辑新的 JD 内容</span></span>{jdGenerationSource === "JOB_LIBRARY" && <CheckCircle2 className="ml-auto shrink-0 text-[#0a9a66]" size={18}/>}</button>
        <button type="button" onClick={() => { setJdGenerationSource("UPLOAD"); handleUploadClick(); }} className={`flex w-full items-center gap-3 px-4 py-3 text-left transition ${jdGenerationSource === "UPLOAD" ? "bg-[#f0fbf8]" : "hover:bg-[#f8fbff]"}`}><span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-[#fff6ee] text-[#df7d2b]"><Upload size={17}/></span><span className="min-w-0"><span className="block text-sm font-bold text-[#203b68]">根据上传文件生成</span><span className="mt-0.5 block text-xs text-[#7083a1]">根据本地 PDF、Word、TXT 文档内容生成 JD</span></span>{jdGenerationSource === "UPLOAD" && <CheckCircle2 className="ml-auto shrink-0 text-[#0a9a66]" size={18}/>}</button>
      </div>}

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

function JdEditor({ draft, busy, confirmed, editing, jobId, onEdit, onSave, onConfirm }: { draft: JdDraft; busy: boolean; confirmed: boolean; editing: boolean; jobId: string | null; onEdit: () => void; onSave: (draft: JdDraft) => void; onConfirm: () => void }) {
  const editable = !confirmed || editing;
  const text = `职位名称：${draft.title}\n企业名称：${draft.companyName}\n工作地点：${draft.location}\n经验要求：${draft.experienceLevel}\n学历要求：${draft.education}\n用工类型：${draft.jobType}\n\n岗位职责\n${draft.responsibilities}\n\n任职要求\n${draft.requirements}\n\n关键技能\n${draft.skills}\n\n人才画像\n${draft.talentProfile}`;
  const [rawText, setRawText] = useState(text);
  useEffect(() => setRawText(text), [draft.id, draft.revision, text]);
  return <div>
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[#e1ebf5] pb-4"><h2 className="m-0 text-xl font-bold text-[#102d64]">{draft.title}</h2><span className={`inline-flex items-center gap-1 rounded-full px-2 py-1 text-[11px] font-semibold ${confirmed ? "bg-[#ddf8ed] text-[#07875b]" : "bg-[#fff6d8] text-[#9a6b00]"}`}>{confirmed ? <CheckCircle2 size={13}/> : <TriangleAlert size={13}/>} {confirmed ? "已发布" : "草稿"}</span></div>
    {!confirmed && draft.warnings.length > 0 && <div className="mt-4 rounded-lg border border-[#f6d58a] bg-[#fffaf0] px-4 py-3"><p className="m-0 flex items-center gap-2 text-xs font-semibold text-[#8d6200]"><TriangleAlert size={15}/>发布前待确认</p><ul className="mb-0 mt-2 list-disc space-y-1 pl-5 text-xs text-[#8a6a28]">{draft.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul></div>}
    <textarea readOnly={!editable} value={rawText} onChange={(event) => setRawText(event.target.value)} className="mt-4 min-h-[500px] w-full resize-y rounded-lg border border-[#cbdced] bg-white px-4 py-3 text-sm leading-6 text-[#28476f] outline-none read-only:bg-[#fafdff] focus:border-[#4a8be8]" aria-label={`${draft.title} JD 全文`}/>
    <div className="mt-4 flex flex-wrap gap-2">{confirmed && !editing && <button type="button" className="outline-button" disabled={busy} onClick={onEdit}><Pencil size={15}/>编辑</button>}{editable && <button type="button" className="outline-button" disabled={busy} onClick={() => onSave(parseJdText(rawText, draft))}><Save size={15}/>保存草稿</button>}<button type="button" className="outline-button" onClick={() => void navigator.clipboard.writeText(rawText)}><Copy size={15}/>复制</button>{!confirmed && <button type="button" className="primary-button" disabled={busy} onClick={onConfirm}><CheckCircle2 size={15}/>确认并发布</button>}{confirmed && jobId && <Link href="/jobs" className="primary-button"><BriefcaseBusiness size={15}/>查看职位库</Link>}</div>
  </div>;
}

function parseJdText(text: string, fallback: JdDraft): JdDraft {
  const fields: Record<string, keyof JdDraft> = { "职位名称": "title", "企业名称": "companyName", "工作地点": "location", "经验要求": "experienceLevel", "学历要求": "education", "用工类型": "jobType" };
  const sections: Record<string, keyof JdDraft> = { "岗位职责": "responsibilities", "任职要求": "requirements", "关键技能": "skills", "人才画像": "talentProfile" };
  const result = { ...fallback }; let current: keyof JdDraft | null = null;
  for (const line of text.split("\n")) { const pair = line.match(/^([^：]+)：(.*)$/); if (pair && fields[pair[1]]) { result[fields[pair[1]]] = pair[2].trim() as never; current = null; } else if (sections[line.trim()]) { current = sections[line.trim()]; result[current] = "" as never; } else if (current) result[current] = `${String(result[current] ?? "")}${String(result[current] ?? "").trim() ? "\n" : ""}${line}` as never; }
  return result;
}

function WorkflowSection({ title, description }: { title: string; description: string }) { return <div className="grid min-h-[520px] place-items-center text-center"><div className="max-w-md"><span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-[#eaf8f5] text-[#169b83]"><Sparkles size={26}/></span><h2 className="mb-0 mt-5 text-xl font-bold text-[#173568]">{title}</h2><p className="mt-3 text-sm leading-6 text-[#60799f]">{description}</p><p className="mt-5 text-xs text-[#7187a8]">该功能正在此工作台分区中展开；右侧 AI 助手会持续提供操作提示。</p></div></div>; }
function StatePage({ icon, text }: { icon: React.ReactNode; text: string }) { return <AppShell activeItem="智能招聘"><div className="flex h-64 flex-col items-center justify-center gap-3 text-[#6780a3]"><span className="text-[#2878da]">{icon}</span><p className="text-sm">{text}</p></div></AppShell>; }
function messageOf(cause: unknown) { return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试"; }
