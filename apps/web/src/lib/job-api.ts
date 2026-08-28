import { apiFetch } from "@/lib/api-client";

// --- 类型定义 ---

export type JobStatus = "DRAFT" | "ACTIVE" | "CLOSED";

export type Job = {
  id: string;
  workspaceId: string;
  title: string;
  companyName: string;
  location: string;
  description: string;
  requirements: string;
  skills: string;
  experienceLevel: string;
  education: string;
  jobType: string;
  status: JobStatus;
  source?: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

export type JobInput = {
  title: string;
  companyName: string;
  location: string;
  description: string;
  requirements: string;
  skills: string;
  experienceLevel: string;
  education: string;
  jobType: string;
};

export type JobStats = {
  total: number;
  active: number;
  closed: number;
  draft: number;
};

export type JobListResult = {
  items: Job[];
  total: number;
  page: number;
  pageSize: number;
};

export type JobVersion = {
  id: string;
  jobId: string;
  versionNumber: number;
  snapshot: string;
  changeSummary: string;
  createdBy: string;
  createdAt: string;
};

export type JobsPageCache = {
  stats: JobStats;
  items: Job[];
  total: number;
  search: string;
  status: string;
  page: number;
  pageSize: number;
};

const JOBS_CACHE_PREFIX = "ir-jobs-cache:";

export function readJobsCache(workspaceId: string): JobsPageCache | null {
  try {
    const raw = sessionStorage.getItem(JOBS_CACHE_PREFIX + workspaceId);
    if (!raw) return null;
    return JSON.parse(raw) as JobsPageCache;
  } catch {
    return null;
  }
}

export function writeJobsCache(workspaceId: string, cache: JobsPageCache) {
  try {
    sessionStorage.setItem(JOBS_CACHE_PREFIX + workspaceId, JSON.stringify(cache));
  } catch {
    // ignore quota / private mode
  }
}

export function upsertJobInCache(workspaceId: string, job: Job) {
  const cache = readJobsCache(workspaceId);
  if (!cache) {
    writeJobsCache(workspaceId, {
      stats: {
        total: 1,
        active: job.status === "ACTIVE" ? 1 : 0,
        closed: job.status === "CLOSED" ? 1 : 0,
        draft: job.status === "DRAFT" ? 1 : 0,
      },
      items: [job],
      total: 1,
      search: "",
      status: "",
      page: 1,
      pageSize: 10,
    });
    return;
  }
  const exists = cache.items.some((item) => item.id === job.id);
  writeJobsCache(workspaceId, {
    ...cache,
    items: [job, ...cache.items.filter((item) => item.id !== job.id)],
    total: exists ? cache.total : cache.total + 1,
  });
}

export function removeJobFromCache(workspaceId: string, jobId: string) {
  const cache = readJobsCache(workspaceId);
  if (!cache) return;
  writeJobsCache(workspaceId, {
    ...cache,
    items: cache.items.filter((item) => item.id !== jobId),
    total: Math.max(0, cache.total - (cache.items.some((item) => item.id === jobId) ? 1 : 0)),
  });
}

// --- API 函数 ---

/** 获取职位统计 */
export async function fetchJobStats(workspaceId: string): Promise<JobStats> {
  return apiFetch<JobStats>(`/workspaces/${workspaceId}/jobs/stats`);
}

/** 分页查询职位列表 */
export async function fetchJobs(
  workspaceId: string,
  params: { search?: string; status?: string; page?: number; pageSize?: number } = {},
): Promise<JobListResult> {
  const searchParams = new URLSearchParams();
  if (params.search) searchParams.set("search", params.search);
  if (params.status) searchParams.set("status", params.status);
  searchParams.set("page", String(params.page ?? 1));
  searchParams.set("pageSize", String(params.pageSize ?? 10));
  return apiFetch<JobListResult>(
    `/workspaces/${workspaceId}/jobs?${searchParams.toString()}`,
  );
}

/** 获取职位详情 */
export async function fetchJob(workspaceId: string, jobId: string): Promise<Job> {
  return apiFetch<Job>(`/workspaces/${workspaceId}/jobs/${jobId}`);
}

/** 创建职位 */
export async function createJob(workspaceId: string, input: JobInput): Promise<Job> {
  return apiFetch<Job>(`/workspaces/${workspaceId}/jobs`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 更新职位 */
export async function updateJob(workspaceId: string, jobId: string, input: JobInput): Promise<Job> {
  return apiFetch<Job>(`/workspaces/${workspaceId}/jobs/${jobId}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/** 更新职位状态 */
export async function updateJobStatus(workspaceId: string, jobId: string, status: string): Promise<Job> {
  return apiFetch<Job>(`/workspaces/${workspaceId}/jobs/${jobId}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

/** 删除职位 */
export async function deleteJob(workspaceId: string, jobId: string): Promise<void> {
  return apiFetch<void>(`/workspaces/${workspaceId}/jobs/${jobId}`, {
    method: "DELETE",
  });
}

/** 批量更新状态 */
export async function batchUpdateStatus(workspaceId: string, jobIds: string[], status: string): Promise<void> {
  return apiFetch<void>(`/workspaces/${workspaceId}/jobs/batch/status`, {
    method: "POST",
    body: JSON.stringify({ jobIds, status }),
  });
}

/** 批量删除 */
export async function batchDelete(workspaceId: string, jobIds: string[]): Promise<void> {
  return apiFetch<void>(`/workspaces/${workspaceId}/jobs/batch/delete`, {
    method: "POST",
    body: JSON.stringify({ jobIds }),
  });
}

/** 获取职位版本历史 */
export async function fetchJobVersions(workspaceId: string, jobId: string): Promise<JobVersion[]> {
  return apiFetch<JobVersion[]>(`/workspaces/${workspaceId}/jobs/${jobId}/versions`);
}
