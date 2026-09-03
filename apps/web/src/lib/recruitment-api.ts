import { apiFetch, apiStream } from "@/lib/api-client";

export type TaskSummary = {
  id: string;
  companyId: string | null;
  workspaceId: string;
  title: string;
  status: string;
  currentStage: string;
  featureType: string | null;
  linkedJobId: string | null;
  linkedCandidateId: string | null;
  jobId: string | null;
  jobTitle: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

export type ConversationMessage = {
  id: string;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  capability: string;
  sequenceNumber: number;
  createdBy: string | null;
  createdAt: string;
};

export type JdDraft = {
  id: string;
  revision: number;
  title: string;
  companyName: string;
  location: string;
  experienceLevel: string;
  education: string;
  jobType: string;
  salaryRange: string;
  responsibilities: string;
  requirements: string;
  skills: string;
  niceToHaves: string;
  benefits: string;
  talentProfile: string;
  warnings: string[];
  status: "DRAFT" | "CONFIRMED";
  updatedAt: string;
};

export type AiRun = {
  id: string;
  providerTaskId: string | null;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  progress: number;
  attemptNumber: number;
  pricingVersion: string;
  estimatedAmountMinor: number;
  settledAmountMinor: number;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  completedAt: string | null;
};

export type ResumeSourceFile = { id: string; fileAssetId: string; filename: string; mediaType: string; sizeBytes: number; createdAt: string; downloadUrl?: string };

export type ResumeSourceDownload = { url: string; expiresAt: string; expiresMinutes: number };

export type ResumeParseDraft = {
  id: string;
  revision: number;
  sourceAiRunId: string | null;
  resumeSourceFileId: string | null;
  content: string;
  status: "DRAFT" | "CONFIRMED";
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
};

export type TaskDetail = {
  task: TaskSummary;
  conversationId: string;
  messages: ConversationMessage[];
  jdDraft: JdDraft | null;
  jdDrafts: JdDraft[];
  latestAiRun: AiRun | null;
  resumeSourceFiles: ResumeSourceFile[];
  resumeParseDrafts: ResumeParseDraft[];
  resumeParseDraft: ResumeParseDraft | null;
};

export type GenerateJdInput = {
  requirement?: string;
  title?: string;
  companyName?: string;
  location?: string;
  experienceLevel?: string;
  education?: string;
  jobType?: string;
  skills?: string;
  scenario?: "NORMAL" | "TIMEOUT" | "INVALID_SCHEMA";
};

export type JdSourceFile = { id: string; fileAssetId: string; filename: string; mediaType: string; sizeBytes: number; createdAt: string };

function idempotencyKey(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`;
}

export function fetchTasks(workspaceId: string) {
  return apiFetch<TaskSummary[]>(`/workspaces/${workspaceId}/recruitment-tasks`);
}

export function fetchTask(workspaceId: string, taskId: string) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}`);
}

export function createTask(workspaceId: string, title: string, initialRequirement: string, extra?: { featureType?: string | null; linkedJobId?: string | null; linkedCandidateId?: string | null }) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey("task") },
    body: JSON.stringify({
      title,
      initialRequirement,
      featureType: extra?.featureType ?? null,
      linkedJobId: extra?.linkedJobId ?? null,
      linkedCandidateId: extra?.linkedCandidateId ?? null,
    }),
  });
}

export function renameTask(workspaceId: string, taskId: string, title: string) {
  return apiFetch<TaskSummary>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}`, {
    method: "PUT",
    body: JSON.stringify({ title }),
  });
}

export function deleteTask(workspaceId: string, taskId: string) {
  return apiFetch<void>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}`, { method: "DELETE" });
}

export function sendMessage(workspaceId: string, taskId: string, content: string, jdDraftId?: string) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/messages`, {
    method: "POST",
    body: JSON.stringify({ content, jdDraftId }),
  });
}

export function generateJd(workspaceId: string, taskId: string, input: GenerateJdInput) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/jd-runs`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey("jd") },
    body: JSON.stringify({ ...input, scenario: input.scenario ?? "NORMAL" }),
  });
}

export function uploadJdSourceFile(workspaceId: string, taskId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<JdSourceFile>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/jd-source-files`, {
    method: "POST", body,
  });
}

export function uploadResumeSourceFile(workspaceId: string, taskId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<ResumeSourceFile>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/resume-source-files`, {
    method: "POST", body,
  });
}

export function updateResumeParseDraft(workspaceId: string, taskId: string, input: { revision: number; content: string }) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/resume-parse-draft`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/**
 * 触发一次 AI 简历解析 run。requirement 可空（空时走 Mock/LLM 兜底默认解析）。
 * 结果会异步写入 resume_parse_drafts 最新 revision，前端通过 TaskDetail.latestAiRun.progress 或 events SSE 跟踪进度。
 */
export function generateResumeParse(workspaceId: string, taskId: string, requirement?: string | null) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/resume-parse-runs`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey("resume-parse") },
    body: requirement == null || requirement.trim() === "" ? undefined : JSON.stringify({ requirement }),
  });
}

/**
 * 触发 AI 面试出题（同步 HTTP，~3-15 秒）：读取 recruitment_task 上 linked_job_id / linked_candidate_id，
 * 调 DeepSeek 生成面试题包并持久化，返回 TaskDetail（含最新 ai_run + 助手消息）。
 */
export function generateInterviewKit(workspaceId: string, taskId: string, questionCount?: number) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/interview-kit-runs`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey("interview-kit") },
    body: questionCount == null ? undefined : JSON.stringify({ questionCount }),
  });
}

/**
 * 获取单个简历源文件的 10 分钟预签名下载/预览 URL。直接 window.open(data.url) 打开预览。
 */
export function getResumeSourceFileDownload(workspaceId: string, taskId: string, sourceFileId: string) {
  return apiFetch<ResumeSourceDownload>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/resume-source-files/${sourceFileId}/download`);
}

export function updateJdDraft(workspaceId: string, taskId: string, draft: JdDraft) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/jd-draft`, {
    method: "PUT",
    body: JSON.stringify(draft),
  });
}

export function confirmJdDraft(workspaceId: string, taskId: string, draftId: string) {
  return apiFetch<{ id: string }>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/jd-draft/confirm?draftId=${draftId}`, {
    method: "POST",
  });
}

export type JdRunEvent = {
  id: number;
  type: "status" | "delta" | "completed" | "failed";
  data: { status?: string; progress?: number; delta?: string; message?: string; errorCode?: string };
};

export async function streamJdRunEvents(
  workspaceId: string,
  taskId: string,
  afterEventId: number,
  onEvent: (event: JdRunEvent) => void,
  signal: AbortSignal,
) {
  const response = await apiStream(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/jd-runs/events`, {
    headers: afterEventId > 0 ? { "Last-Event-ID": String(afterEventId) } : {}, signal,
  });
  if (!response.body) throw new Error("浏览器不支持生成进度流");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, "\n");
    let boundary = buffer.indexOf("\n\n");
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      if (!frame.startsWith(":")) {
        let id = 0;
        let type = "status";
        const dataLines: string[] = [];
        for (const line of frame.split("\n")) {
          if (line.startsWith("id:")) id = Number(line.slice(3).trim());
          if (line.startsWith("event:")) type = line.slice(6).trim();
          if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
        }
        if (id > 0 && dataLines.length) onEvent({ id, type: type as JdRunEvent["type"], data: JSON.parse(dataLines.join("\n")) });
      }
      boundary = buffer.indexOf("\n\n");
    }
    if (done) return;
  }
}
