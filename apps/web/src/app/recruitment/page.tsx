"use client";

import {
  AlertCircle, ArrowUp, Bot, BriefcaseBusiness, CheckCircle2, ChevronRight, CircleDollarSign,
  FileText, Filter, ListChecks, Loader2, MessageSquareText, Plus, Save, Send, Sparkles,
  TriangleAlert, UsersRound,
} from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import {
  confirmJdDraft, createTask, fetchTask, fetchTasks, generateJd, sendMessage, updateJdDraft,
  type JdDraft, type TaskDetail, type TaskSummary,
} from "@/lib/recruitment-api";
import { useWorkspace } from "@/lib/workspace-context";

const stageLabels: Record<string, string> = {
  COLLECTING_REQUIREMENTS: "需求收集中",
  AWAITING_JD_CONFIRMATION: "JD 待确认",
  JD_GENERATION_FAILED: "生成失败",
  JD_CONFIRMED: "JD 已确认",
};

export default function RecruitmentPage() {
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
          const targetId = list[0]?.id;
          if (!targetId) {
            setDetail(null);
            setDraft(null);
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
  }, [workspaceId]);

  useEffect(() => {
    if (notAuthenticated) window.location.replace("/login");
  }, [notAuthenticated]);

  async function handleCreate() {
    if (!workspaceId || !newTitle.trim() || !newRequirement.trim()) return;
    await run(async () => {
      const created = await createTask(workspaceId, newTitle, newRequirement);
      setNewTitle("");
      setNewRequirement("");
      setDetail(created);
      setDraft(created.jdDraft);
      await loadTasks(created.task.id);
    });
  }

  async function handleSelect(taskId: string) {
    if (!workspaceId) return;
    await run(async () => {
      const selected = await fetchTask(workspaceId, taskId);
      setDetail(selected);
      setDraft(selected.jdDraft);
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

  if (tasks.length === 0 && !detail) {
    return <AppShell activeItem="智能招聘">
      <RecruitmentEmptyState
        workspaceName={workspace?.name ?? "当前工作空间"}
        title={newTitle}
        requirement={newRequirement}
        busy={busy}
        error={error}
        onTitle={setNewTitle}
        onRequirement={setNewRequirement}
        onCreate={() => void handleCreate()}
      />
    </AppShell>;
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

    <div className="mt-4 grid min-h-[680px] gap-4 xl:grid-cols-[270px_minmax(480px,1fr)_360px]">
      <aside className="rounded-xl border border-[#d6e5f5] bg-white p-3 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        <div className="flex items-center justify-between px-1 pb-3">
          <div><h2 className="m-0 text-sm font-bold text-[#173568]">招聘任务</h2><p className="mb-0 mt-1 text-[11px] text-[#7b8fac]">仅显示当前 Workspace</p></div>
          <button type="button" className="grid h-8 w-8 place-items-center rounded-lg border border-[#cfe0f3] text-[#176ce5]" onClick={() => { setDetail(null); setDraft(null); }} aria-label="新建招聘任务"><Plus size={16}/></button>
        </div>
        <div className="space-y-2">
          {loading && tasks.length === 0 && <p className="py-8 text-center text-xs text-[#7187a8]">加载任务中...</p>}
          {tasks.map((task) => <button key={task.id} type="button" onClick={() => void handleSelect(task.id)} className={`w-full rounded-lg border p-3 text-left transition ${detail?.task.id === task.id ? "border-[#78cdb0] bg-[#eafff7]" : "border-[#e1eaf5] hover:bg-[#f6faff]"}`}>
            <span className="block truncate text-sm font-semibold text-[#173568]">{task.title}</span>
            <span className="mt-2 flex items-center justify-between text-[11px] text-[#6d83a5]"><span>{stageLabels[task.currentStage] ?? task.currentStage}</span><ChevronRight size={13}/></span>
          </button>)}
          {!loading && tasks.length === 0 && <p className="rounded-lg bg-[#f5f9fe] px-3 py-8 text-center text-xs leading-5 text-[#7187a8]">还没有招聘任务<br/>从右侧填写需求开始</p>}
        </div>
      </aside>

      <main className="min-w-0 rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        {!detail ? <CreateTaskPanel title={newTitle} requirement={newRequirement} busy={busy} onTitle={setNewTitle} onRequirement={setNewRequirement} onCreate={() => void handleCreate()} /> : draft ? <JdEditor draft={draft} busy={busy} confirmed={draft.status === "CONFIRMED"} jobId={detail.task.jobId} onChange={setDraft} onSave={() => void handleSave()} onConfirm={() => void handleConfirm()} /> : <EmptyDraft task={detail.task} busy={busy} scenario={scenario} onScenario={setScenario} onGenerate={() => void handleGenerate()} />}
      </main>

      <aside className="flex min-h-0 flex-col rounded-xl border border-[#d6e5f5] bg-white shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
        <div className="flex items-center justify-between border-b border-[#e2ebf5] px-4 py-3"><span className="flex items-center gap-2 text-sm font-bold text-[#173568]"><Bot size={17} className="text-[#1478e8]"/>AI 招聘助手</span>{detail?.latestAiRun && <span className={`rounded-full px-2 py-1 text-[10px] font-semibold ${detail.latestAiRun.status === "FAILED" ? "bg-[#fff0f0] text-[#cf3030]" : "bg-[#e7f8f1] text-[#07885b]"}`}>{detail.latestAiRun.status === "FAILED" ? "生成失败" : "Mock AI"}</span>}</div>
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4">
          {!detail && <div className="rounded-lg bg-[#eef7ff] p-3 text-xs leading-5 text-[#56749f]">创建招聘任务后，我会协助补充需求并生成结构化 JD 草稿。</div>}
          {detail?.messages.map((item) => <article key={item.id} className={`max-w-[92%] rounded-xl px-3 py-2.5 text-xs leading-5 ${item.role === "USER" ? "ml-auto bg-[#176ce5] text-white" : item.role === "SYSTEM" ? "bg-[#f3f5f8] text-[#657996]" : "bg-[#eef8ff] text-[#35577f]"}`}><p className="m-0 whitespace-pre-wrap">{item.content}</p></article>)}
          {busy && <article className="flex max-w-[92%] items-center gap-2 rounded-xl bg-[#eef8ff] px-3 py-2.5 text-xs text-[#55709d]"><Loader2 className="animate-spin" size={14}/>AI 正在处理并校验结构化结果...</article>}
        </div>
        {detail && detail.task.currentStage !== "JD_CONFIRMED" && <div className="border-t border-[#e2ebf5] p-3"><div className="flex items-end gap-2"><textarea value={message} onChange={(event) => setMessage(event.target.value)} className="min-h-[72px] flex-1 resize-none rounded-lg border border-[#cbdced] px-3 py-2 text-xs outline-none focus:border-[#4a8be8]" placeholder="补充职责、经验、地点、技能等要求"/><button type="button" className="grid h-10 w-10 place-items-center rounded-lg bg-[#176ce5] text-white disabled:opacity-50" disabled={busy || !message.trim()} onClick={() => void handleMessage()} aria-label="发送消息"><Send size={16}/></button></div></div>}
      </aside>
    </div>
  </AppShell>;
}

const recruitmentStarters = [
  {
    icon: BriefcaseBusiness,
    title: "帮我生成 JD",
    description: "对话澄清需求并生成人才画像",
    taskTitle: "高级 Java 开发工程师招聘",
    requirement: "我们需要招聘一名高级 Java 开发工程师，请协助我梳理业务背景、岗位职责、工作地点、经验学历和核心技能要求。",
  },
  {
    icon: UsersRound,
    title: "上传并解析简历",
    description: "批量导入 PDF、DOCX 到人才库",
    href: "/candidates",
  },
  {
    icon: Filter,
    title: "帮我筛简历",
    description: "设置六维方案并执行人岗匹配",
    href: "/screening",
  },
  {
    icon: ListChecks,
    title: "AI 面试出题",
    description: "根据职位和候选人生成面试题 · Phase 6",
    disabled: true,
  },
];

function RecruitmentEmptyState({ workspaceName, title, requirement, busy, error, onTitle, onRequirement, onCreate }: {
  workspaceName: string;
  title: string;
  requirement: string;
  busy: boolean;
  error: string | null;
  onTitle: (value: string) => void;
  onRequirement: (value: string) => void;
  onCreate: () => void;
}) {
  const chooseStarter = (starter: typeof recruitmentStarters[number]) => {
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
      <p className="mb-0 mt-5 text-xs font-semibold tracking-[0.18em] text-[#189b91]">AI 招聘工作台</p>
      <h1 className="mb-0 mt-2 text-[clamp(26px,3vw,38px)] font-bold tracking-tight text-[#102d64]">你好，我是 AI 招聘助手</h1>
      <p className="mx-auto mb-0 mt-4 max-w-2xl text-[15px] leading-7 text-[#60789d]">
        告诉我你想招聘什么岗位，我会协助补齐需求、生成结构化 JD 和人才画像，并在你确认后写入职位库。
      </p>
      <span className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-[#eef8f8] px-3 py-1.5 text-xs text-[#4c7c84]">
        <span className="h-1.5 w-1.5 rounded-full bg-[#13ad85]"/>{workspaceName}
      </span>
    </section>

    <section className="mx-auto mt-7 grid w-full max-w-5xl gap-3 sm:grid-cols-2 xl:grid-cols-4">
      {recruitmentStarters.map((starter) => {
        const Icon = starter.icon;
        const content = <><span className="grid h-9 w-9 place-items-center rounded-xl bg-[#edf8f7] text-[#159d8e] transition group-hover:bg-[#dcf5ef]"><Icon size={18}/></span>
          <span className="mt-3 block text-sm font-bold text-[#203b68]">{starter.title}</span>
          <span className="mt-1.5 block text-xs leading-5 text-[#7083a1]">{starter.description}</span></>;
        const className = `group rounded-2xl border border-[#e5edf5] bg-white px-5 py-4 text-left shadow-[0_5px_18px_rgba(38,82,130,0.04)] transition ${starter.disabled ? "cursor-not-allowed opacity-60" : "hover:-translate-y-0.5 hover:border-[#9ddfce] hover:shadow-[0_10px_28px_rgba(32,137,131,0.10)]"}`;
        if (starter.href) return <Link key={starter.title} href={starter.href} className={className}>{content}</Link>;
        return <button key={starter.title} type="button" disabled={starter.disabled} onClick={() => chooseStarter(starter)} className={className}>
          {content}
        </button>;
      })}
    </section>

    <section className="mx-auto mt-[clamp(28px,7vh,76px)] w-full max-w-4xl">
      <div className="rounded-[22px] border border-[#dbe6f0] bg-white px-5 pb-4 pt-3 shadow-[0_18px_55px_rgba(38,72,116,0.13)] focus-within:border-[#8fcfca] focus-within:shadow-[0_20px_60px_rgba(28,130,126,0.15)]">
        <input value={title} onChange={(event) => onTitle(event.target.value)} maxLength={200} className="h-10 w-full border-0 border-b border-[#edf1f5] bg-transparent text-sm font-semibold text-[#23406e] outline-none placeholder:font-normal placeholder:text-[#a8b5c6]" placeholder="任务名称，例如：高级 Java 开发工程师招聘" aria-label="招聘任务名称"/>
        <textarea value={requirement} onChange={(event) => onRequirement(event.target.value)} maxLength={20000} className="min-h-[82px] w-full resize-none border-0 bg-transparent py-3 text-sm leading-6 text-[#344f75] outline-none placeholder:text-[#b0bac8]" placeholder="描述岗位、业务背景、工作地点、经验学历、核心技能或招聘偏好……" aria-label="招聘需求"/>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[#edf1f5] pt-3">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-[#637795]">
            <span className="flex items-center gap-1.5 font-semibold text-[#129b8b]"><MessageSquareText size={15}/>需求采集</span>
            <span className="flex items-center gap-1.5"><FileText size={15}/>JD 草稿</span>
            <span className="flex items-center gap-1.5"><UsersRound size={15}/>人才画像</span>
            <span className="flex items-center gap-1.5"><CheckCircle2 size={15}/>人工确认</span>
          </div>
          <button type="button" onClick={onCreate} disabled={busy || !title.trim() || !requirement.trim()} className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-gradient-to-br from-[#1676ee] to-[#12b98c] text-white shadow-[0_7px_18px_rgba(22,118,238,0.23)] transition hover:scale-105 disabled:cursor-not-allowed disabled:from-[#dce3ea] disabled:to-[#dce3ea] disabled:shadow-none" aria-label="创建招聘任务">
            {busy ? <Loader2 className="animate-spin" size={18}/> : <ArrowUp size={19}/>}
          </button>
        </div>
      </div>
      <p className="mb-0 mt-3 text-center text-[11px] text-[#91a0b4]">AI 生成内容仅作为草稿，确认后才会进入当前工作空间的职位库 · JD 生成预计 ¥0.80/次</p>
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
    <button type="button" className="primary-button mt-5" disabled={busy} onClick={onGenerate}>{busy ? <Loader2 className="animate-spin" size={16}/> : <Sparkles size={16}/>}生成 JD（预计 ¥0.80）</button>
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

function StatePage({ icon, text }: { icon: React.ReactNode; text: string }) { return <AppShell activeItem="智能招聘"><div className="flex h-64 flex-col items-center justify-center gap-3 text-[#6780a3]"><span className="text-[#2878da]">{icon}</span><p className="text-sm">{text}</p></div></AppShell>; }
function messageOf(cause: unknown) { return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试"; }
