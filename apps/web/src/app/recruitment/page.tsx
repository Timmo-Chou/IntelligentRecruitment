"use client";

import {
  AlertCircle, Bot, BriefcaseBusiness, CheckCircle2, CircleDollarSign,
  FileText, Filter, ListChecks, Loader2, MessageSquareText, Plus, Save, Send, Sparkles,
  TriangleAlert, UsersRound,
} from "lucide-react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import {
  confirmJdDraft, createTask, fetchTask, fetchTasks, generateJd, sendMessage, streamJdRunEvents, updateJdDraft,
  type JdDraft, type TaskDetail, type TaskSummary,
} from "@/lib/recruitment-api";
import { useWorkspace } from "@/lib/workspace-context";

type WorkspaceSection = "home" | "jd" | "candidates" | "screening" | "interviews";

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
  const [scenario, setScenario] = useState<"NORMAL" | "TIMEOUT" | "INVALID_SCHEMA">("NORMAL");
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
      const created = await createTask(workspaceId, newTitle.trim() || "智能招聘任务", newRequirement);
      setNewTitle("");
      setNewRequirement("");
      setDetail(created);
      setDraft(created.jdDraft);
      setSection("jd");
      window.dispatchEvent(new Event("recruitment-tasks-changed"));
      await loadTasks(created.task.id);
    });
  }

  async function handleMessage() {
    if (!workspaceId || !detail || !message.trim()) return;
    await run(async () => {
      const next = await sendMessage(workspaceId, detail.task.id, message);
      setMessage("");
      setDetail(next);
      setDraft(next.jdDraft);
      await refreshTaskList(next.task.id);
    });
  }

  async function handleGenerate() {
    if (!workspaceId || !detail) return;
    await run(async () => {
      setStreamText("");
      const next = await generateJd(workspaceId, detail.task.id, { scenario });
      setDetail(next);
      setDraft(next.jdDraft);
      await refreshTaskList(next.task.id);
    });
  }

  async function handleSave() {
    if (!workspaceId || !detail || !draft) return;
    await run(async () => {
      const next = await updateJdDraft(workspaceId, detail.task.id, draft);
      setDetail(next);
      setDraft(next.jdDraft);
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
    return <AppShell activeItem="智能招聘"><RecruitmentEmptyState requirement={newRequirement} busy={busy} error={error} onTitle={setNewTitle} onRequirement={setNewRequirement} onCreate={() => void handleCreate()} onSection={setSection}/></AppShell>;
  }

  return <AppShell activeItem="智能招聘">
    <section className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="m-0 text-[25px] font-bold tracking-tight text-[#09245d]">智能招聘</h1>
        <p className="mb-0 mt-1 text-sm text-[#55709d]">{workspace?.name} · 从需求对话生成并确认可追溯的 JD 版本</p>
      </div>
      <div className="flex items-center gap-2 rounded-lg border border-[#cfe4f5] bg-white px-3 py-2 text-xs text-[#53709a]">
        <CircleDollarSign size={16} className="text-[#0a9a66]" /> JD 生成临时价 ¥0.80/次
      </div>
    </section>

    {error && <div className="mt-4 flex items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}

    <div className="mt-4 grid min-h-[680px] gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <main className="min-w-0 rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        {section === "candidates" ? <WorkflowSection title="上传并解析简历" description="请在此上传 PDF、DOCX 简历，系统将解析候选人信息并在当前工作空间保存。"/> : section === "screening" ? <WorkflowSection title="帮我筛简历" description="选择已确认职位与已解析候选人，确认费用后执行可解释的人岗匹配。"/> : section === "interviews" ? <WorkflowSection title="AI 面试出题" description="选择候选人，生成可编辑、可确认的结构化面试题包。"/> : !detail ? <CreateTaskPanel title={newTitle} requirement={newRequirement} busy={busy} onTitle={setNewTitle} onRequirement={setNewRequirement} onCreate={() => void handleCreate()} /> : draft ? <JdEditor draft={draft} busy={busy} confirmed={draft.status === "CONFIRMED"} jobId={detail.task.jobId} onChange={setDraft} onSave={() => void handleSave()} onConfirm={() => void handleConfirm()} /> : <EmptyDraft task={detail.task} busy={busy} scenario={scenario} onScenario={setScenario} onGenerate={() => void handleGenerate()} />}
      </main>

      <aside className="flex min-h-0 flex-col rounded-xl border border-[#d6e5f5] bg-white shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        <div className="flex items-center justify-between border-b border-[#e2ebf5] px-4 py-3"><span className="flex items-center gap-2 text-sm font-bold text-[#173568]"><Bot size={17} className="text-[#1478e8]"/>AI 招聘助手</span>{detail?.latestAiRun && <span className={`rounded-full px-2 py-1 text-[10px] font-semibold ${detail.latestAiRun.status === "FAILED" ? "bg-[#fff0f0] text-[#cf3030]" : "bg-[#e7f8f1] text-[#07885b]"}`}>{detail.latestAiRun.status === "FAILED" ? "生成失败" : "Mock AI"}</span>}</div>
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4">
          {!detail && <div className="rounded-lg bg-[#eef7ff] p-3 text-xs leading-5 text-[#56749f]">创建招聘任务后，我会协助补充需求并生成结构化 JD 草稿。</div>}
          {detail?.messages.map((item) => <article key={item.id} className={`max-w-[92%] rounded-xl px-3 py-2.5 text-xs leading-5 ${item.role === "USER" ? "ml-auto bg-[#176ce5] text-white" : item.role === "SYSTEM" ? "bg-[#f3f5f8] text-[#657996]" : "bg-[#eef8ff] text-[#35577f]"}`}><p className="m-0 whitespace-pre-wrap">{item.content}</p></article>)}
          {(busy || ["QUEUED", "RUNNING"].includes(detail?.latestAiRun?.status ?? "")) && <article className="max-w-[92%] rounded-xl bg-[#eef8ff] px-3 py-2.5 text-xs leading-5 text-[#55709d]"><span className="flex items-center gap-2"><Loader2 className="animate-spin" size={14}/>AI 正在异步生成 · {detail?.latestAiRun?.progress ?? 0}%</span>{streamText && <p className="mb-0 mt-2 whitespace-pre-wrap">{streamText}</p>}</article>}
        </div>
        {detail && detail.task.currentStage !== "JD_CONFIRMED" && <div className="border-t border-[#e2ebf5] p-3"><div className="flex items-end gap-2"><textarea value={message} onChange={(event) => setMessage(event.target.value)} className="min-h-[72px] flex-1 resize-none rounded-lg border border-[#cbdced] px-3 py-2 text-xs outline-none focus:border-[#4a8be8]" placeholder="补充职责、经验、地点、技能等要求"/><button type="button" className="grid h-10 w-10 place-items-center rounded-lg bg-[#176ce5] text-white disabled:opacity-50" disabled={busy || !message.trim()} onClick={() => void handleMessage()} aria-label="发送消息"><Send size={16}/></button></div></div>}
      </aside>
    </div>
  </AppShell>;
}

type RecruitmentStarter = { icon: typeof BriefcaseBusiness; title: string; description: string; taskTitle?: string; requirement?: string; section?: WorkspaceSection };
const recruitmentStarters: RecruitmentStarter[] = [
  {
    icon: BriefcaseBusiness,
    title: "我要发布新岗位",
    description: "帮我生成一份高质量的岗位JD",
    taskTitle: "高级 Java 开发工程师招聘",
    requirement: "我们需要招聘一名高级 Java 开发工程师，请协助我梳理业务背景、岗位职责、工作地点、经验学历和核心技能要求。",
  },
  {
    icon: UsersRound,
    title: "我要筛选简历",
    description: "根据岗位JD匹配合适的候选人",
    section: "screening",
  },
  {
    icon: Filter,
    title: "我要准备面试",
    description: "为候选人生成结构化面试问题",
    section: "interviews",
  },
  {
    icon: ListChecks,
    title: "上传并解析简历",
    description: "批量导入 PDF、DOCX 并自动解析人才信息",
    section: "candidates",
  },
];

function RecruitmentEmptyState({ requirement, busy, error, onTitle, onRequirement, onCreate, onSection }: {
  requirement: string;
  busy: boolean;
  error: string | null;
  onTitle: (value: string) => void;
  onRequirement: (value: string) => void;
  onCreate: () => void;
  onSection: (section: WorkspaceSection) => void;
}) {
  const chooseStarter = (starter: typeof recruitmentStarters[number]) => {
    if (starter.section) { onSection(starter.section); return; }
    if (!starter.taskTitle || !starter.requirement) return;
    onTitle(starter.taskTitle);
    onRequirement(starter.requirement);
  };

  return <div className="relative mx-auto flex min-h-[calc(100vh-108px)] max-w-[1180px] flex-col px-3 pb-5 pt-[clamp(24px,4vh,60px)]">
    {error && <div className="mx-auto mb-5 flex w-full max-w-3xl items-center gap-2 rounded-xl border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]"><AlertCircle size={17}/>{error}</div>}

    <section className="mx-auto w-full max-w-4xl text-center">
      <div className="mx-auto grid h-[68px] w-[68px] place-items-center rounded-[22px] bg-gradient-to-br from-[#1d78f2] via-[#18a7d4] to-[#17bd91] text-white shadow-[0_15px_35px_rgba(29,120,242,0.22)]">
        <Bot size={35} strokeWidth={1.8}/>
      </div>
      <h1 className="mb-0 mt-2 text-[clamp(30px,3.5vw,32px)] font-bold tracking-tight text-[#102d64]">AI智能招聘助手</h1>
      <p className="mx-auto mb-0 mt-4 max-w-2xl text-[15px] leading-7 text-[#60789d]">
        我可以帮你生成 JD、筛选简历、出面试题、编排招聘工作流，还可以回答招聘相关问题。
      </p>
    </section>

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

    <section className="mx-auto mt-[clamp(28px,7vh,76px)] w-full max-w-4xl">
      <div className="rounded-[30px] border border-[#dbe6f0] bg-white px-6 pb-5 pt-5 shadow-[0_18px_55px_rgba(38,72,116,0.13)] focus-within:border-[#8fcfca] focus-within:shadow-[0_20px_60px_rgba(28,130,126,0.15)]">
        <textarea value={requirement} onChange={(event) => onRequirement(event.target.value)} maxLength={20000} className="min-h-[86px] w-full resize-none border-0 bg-transparent text-[16px] leading-7 text-[#344f75] outline-none placeholder:text-[#98a9bd] focus-visible:outline-white" placeholder="告诉我你的招聘需求，或上传 JD / 简历开始智能招聘" aria-label="招聘需求"/>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[#edf1f5] pt-4">
          <div className="flex flex-wrap items-center gap-2 text-sm text-[#536d91]">
            <button type="button" onClick={()=>onTitle("高级 Java 开发工程师招聘")} className="rounded-full border border-[#e0e8f0] px-3 py-2 hover:bg-[#f5faff]"><BriefcaseBusiness className="mr-1 inline text-[#15b6b3]" size={16}/>找职位</button>
            <button type="button" onClick={()=>onRequirement("请帮我生成一份招聘 JD，包含岗位职责、任职要求和人才画像。")} className="rounded-full border border-[#e0e8f0] px-3 py-2 hover:bg-[#f5faff]"><FileText className="mr-1 inline text-[#15b6b3]" size={16}/>JD生成</button>
            <button type="button" onClick={()=>onSection("screening")} className="rounded-full border border-[#e0e8f0] px-3 py-2 hover:bg-[#f5faff]"><UsersRound className="mr-1 inline text-[#15b6b3]" size={16}/>AI筛简历</button>
            <button type="button" onClick={()=>onSection("interviews")} className="rounded-full border border-[#e0e8f0] px-3 py-2 hover:bg-[#f5faff]"><ListChecks className="mr-1 inline text-[#15b6b3]" size={16}/>AI面试题</button>
            <button type="button" onClick={()=>onRequirement("请帮我规划从 JD 到面试的招聘工作流。")} className="rounded-full border border-[#e0e8f0] px-3 py-2 hover:bg-[#f5faff]"><Bot className="mr-1 inline text-[#15b6b3]" size={16}/>工作流</button>
            <button type="button" onClick={()=>onSection("screening")} className="rounded-full border border-[#e0e8f0] px-3 py-2 hover:bg-[#f5faff]"><Sparkles className="mr-1 inline text-[#15b6b3]" size={16}/>职位匹配度</button>
          </div>
          <div className="flex items-center gap-3"><button type="button" className="grid h-11 w-11 place-items-center rounded-full border-2 border-[#24456f] text-[#24456f]" aria-label="添加附件"><Plus size={23}/></button><button type="button" onClick={onCreate} disabled={busy || !requirement.trim()} className="grid h-12 w-12 shrink-0 place-items-center rounded-full bg-gradient-to-br from-[#1fbec3] to-[#0aa6a2] text-white shadow-[0_7px_18px_rgba(22,172,168,0.25)] transition hover:scale-105 disabled:cursor-not-allowed disabled:from-[#dce3ea] disabled:to-[#dce3ea] disabled:shadow-none" aria-label="开始智能招聘">{busy ? <Loader2 className="animate-spin" size={19}/> : <Send size={20}/>}</button></div>
        </div>
      </div>
    </section>
  </div>;
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

function EmptyDraft({ task, busy, scenario, onScenario, onGenerate }: { task: TaskSummary; busy: boolean; scenario: "NORMAL" | "TIMEOUT" | "INVALID_SCHEMA"; onScenario: (value: "NORMAL" | "TIMEOUT" | "INVALID_SCHEMA") => void; onGenerate: () => void }) {
  return <div className="mx-auto max-w-xl py-16 text-center">
    <span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-[#e9f8ff] text-[#1778df]"><MessageSquareText size={27}/></span>
    <h2 className="mb-0 mt-5 text-xl font-bold text-[#173568]">{task.title}</h2><p className="mx-auto mt-3 max-w-md text-sm leading-6 text-[#60799f]">需求已保存。你可以继续在右侧补充信息，准备好后生成结构化 JD 草稿。</p>
    <div className="mx-auto mt-6 flex max-w-sm items-center justify-between rounded-lg border border-[#d7e5f3] bg-[#f8fbff] px-3 py-2 text-xs text-[#59749a]"><span>Mock 故障场景</span><select value={scenario} onChange={(event) => onScenario(event.target.value as typeof scenario)} className="rounded border border-[#cbdced] bg-white px-2 py-1"><option value="NORMAL">正常生成</option><option value="TIMEOUT">模拟超时</option><option value="INVALID_SCHEMA">模拟非法结构</option></select></div>
    <button type="button" className="primary-button mt-5" disabled={busy} onClick={onGenerate}>{busy ? <Loader2 className="animate-spin" size={16}/> : <Sparkles size={16}/>}确认生成并预占 ¥0.80</button>
  </div>;
}

function JdEditor({ draft, busy, confirmed, jobId, onChange, onSave, onConfirm }: { draft: JdDraft; busy: boolean; confirmed: boolean; jobId: string | null; onChange: (draft: JdDraft) => void; onSave: () => void; onConfirm: () => void }) {
  const update = (key: keyof JdDraft, value: string) => onChange({ ...draft, [key]: value });
  return <div>
    <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#e1ebf5] pb-4"><div><span className={`inline-flex items-center gap-1 rounded-full px-2 py-1 text-[11px] font-semibold ${confirmed ? "bg-[#ddf8ed] text-[#07875b]" : "bg-[#fff6d8] text-[#9a6b00]"}`}>{confirmed ? <CheckCircle2 size={13}/> : <TriangleAlert size={13}/>} {confirmed ? "已确认版本" : `草稿 revision ${draft.revision}`}</span><h2 className="mb-0 mt-2 text-xl font-bold text-[#102d64]">结构化 JD</h2></div><div className="flex gap-2">{confirmed && jobId ? <Link href="/jobs" className="primary-button"><BriefcaseBusiness size={15}/>查看职位库</Link> : <><button type="button" className="outline-button" disabled={busy} onClick={onSave}><Save size={15}/>保存草稿</button><button type="button" className="primary-button" disabled={busy} onClick={onConfirm}><CheckCircle2 size={15}/>确认并发布</button></>}</div></div>
    {draft.warnings.length > 0 && <div className="mt-4 rounded-lg border border-[#f6d58a] bg-[#fffaf0] px-4 py-3"><p className="m-0 flex items-center gap-2 text-xs font-semibold text-[#8d6200]"><TriangleAlert size={15}/>发布前待确认</p><ul className="mb-0 mt-2 list-disc space-y-1 pl-5 text-xs text-[#8a6a28]">{draft.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul></div>}
    <div className="mt-4 grid gap-3 md:grid-cols-2"><EditorField label="职位名称" value={draft.title} disabled={confirmed} onChange={(value) => update("title", value)}/><EditorField label="企业名称" value={draft.companyName} disabled={confirmed} onChange={(value) => update("companyName", value)}/><EditorField label="工作地点" value={draft.location} disabled={confirmed} onChange={(value) => update("location", value)}/><EditorField label="经验要求" value={draft.experienceLevel} disabled={confirmed} onChange={(value) => update("experienceLevel", value)}/><EditorField label="学历要求" value={draft.education} disabled={confirmed} onChange={(value) => update("education", value)}/><EditorField label="用工类型" value={draft.jobType} disabled={confirmed} onChange={(value) => update("jobType", value)}/></div>
    <EditorArea label="岗位职责" value={draft.responsibilities} disabled={confirmed} onChange={(value) => update("responsibilities", value)}/><EditorArea label="任职要求" value={draft.requirements} disabled={confirmed} onChange={(value) => update("requirements", value)}/><EditorArea label="关键技能" value={draft.skills} disabled={confirmed} onChange={(value) => update("skills", value)}/><EditorArea label="人才画像" value={draft.talentProfile} disabled={confirmed} onChange={(value) => update("talentProfile", value)}/>
  </div>;
}

function EditorField({ label, value, disabled, onChange }: { label: string; value: string; disabled: boolean; onChange: (value: string) => void }) { return <label className="block text-xs font-semibold text-[#36527f]">{label}<input value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)} className="mt-1.5 h-10 w-full rounded-lg border border-[#cbdced] px-3 text-sm font-normal outline-none disabled:bg-[#f5f8fb]"/></label>; }
function EditorArea({ label, value, disabled, onChange }: { label: string; value: string; disabled: boolean; onChange: (value: string) => void }) { return <label className="mt-4 block text-xs font-semibold text-[#36527f]">{label}<textarea value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)} className="mt-1.5 min-h-[110px] w-full rounded-lg border border-[#cbdced] px-3 py-2 text-sm font-normal leading-6 outline-none disabled:bg-[#f5f8fb]"/></label>; }

function WorkflowSection({ title, description }: { title: string; description: string }) { return <div className="grid min-h-[520px] place-items-center text-center"><div className="max-w-md"><span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-[#eaf8f5] text-[#169b83]"><Sparkles size={26}/></span><h2 className="mb-0 mt-5 text-xl font-bold text-[#173568]">{title}</h2><p className="mt-3 text-sm leading-6 text-[#60799f]">{description}</p><p className="mt-5 text-xs text-[#7187a8]">该功能正在此工作台分区中展开；右侧 AI 助手会持续提供操作提示。</p></div></div>; }
function StatePage({ icon, text }: { icon: React.ReactNode; text: string }) { return <AppShell activeItem="智能招聘"><div className="flex h-64 flex-col items-center justify-center gap-3 text-[#6780a3]"><span className="text-[#2878da]">{icon}</span><p className="text-sm">{text}</p></div></AppShell>; }
function messageOf(cause: unknown) { return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试"; }
