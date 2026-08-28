import { apiFetch, apiStream } from "@/lib/api-client";

export type TaskSummary = {
  id: string;
  companyId: string | null;
  workspaceId: string;
  title: string;
  status: string;
  currentStage: string;
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
  responsibilities: string;
  requirements: string;
  skills: string;
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

export type TaskDetail = {
  task: TaskSummary;
  conversationId: string;
  messages: ConversationMessage[];
  jdDraft: JdDraft | null;
  latestAiRun: AiRun | null;
  /** 简历筛选六维评估维度 JSON 字符串（后端默认返回6项，非空） */
  screeningDimsJson: string | null;
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

function idempotencyKey(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`;
}

export function fetchTasks(workspaceId: string) {
  return apiFetch<TaskSummary[]>(`/workspaces/${workspaceId}/recruitment-tasks`);
}

export function fetchTask(workspaceId: string, taskId: string) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}`);
}

export function createTask(workspaceId: string, title: string, initialRequirement: string) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey("task") },
    body: JSON.stringify({ title, initialRequirement }),
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

export function sendMessage(workspaceId: string, taskId: string, content: string) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/messages`, {
    method: "POST",
    body: JSON.stringify({ content }),
  });
}

export function generateJd(workspaceId: string, taskId: string, input: GenerateJdInput) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/jd-runs`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey("jd") },
    body: JSON.stringify({ ...input, scenario: input.scenario ?? "NORMAL" }),
  });
}

export function updateJdDraft(workspaceId: string, taskId: string, draft: JdDraft) {
  return apiFetch<TaskDetail>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/jd-draft`, {
    method: "PUT",
    body: JSON.stringify(draft),
  });
}

export function confirmJdDraft(workspaceId: string, taskId: string) {
  return apiFetch<{ id: string }>(`/workspaces/${workspaceId}/recruitment-tasks/${taskId}/jd-draft/confirm`, {
    method: "POST",
  });
}

/** 简历筛选六维评估维度（与后端 ScreeningDimsView.dimensionsJson 结构一致） */
export type ScreeningDimension = {
  id: string;
  name: string;
  weight: number;
  description: string;
};

/** 读取简历筛选六维评估配置（后端返回 JSON 字符串） */
export function fetchScreeningDimensions(workspaceId: string, taskId: string) {
  return apiFetch<{ dimensionsJson: string }>(
    `/workspaces/${workspaceId}/recruitment-tasks/${taskId}/screening-dims`,
  );
}

/** 保存简历筛选六维评估配置（整体覆盖；后端会按默认 id 集合并入，避免缺列/非法值） */
export function updateScreeningDimensions(workspaceId: string, taskId: string, dimensions: ScreeningDimension[]) {
  return apiFetch<{ dimensionsJson: string }>(
    `/workspaces/${workspaceId}/recruitment-tasks/${taskId}/screening-dims`,
    { method: "PUT", body: JSON.stringify({ dimensionsJson: JSON.stringify(dimensions) }) },
  );
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
