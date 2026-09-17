import { apiFetch } from "@/lib/api-client";

// --- 类型定义 ---

export type JobStatus = "DRAFT" | "ACTIVE" | "CLOSED";

export type Job = {
  id: string;
  tenantId: string;
  title: string;
  companyName: string;
  location: string;
  salaryRange: string;
  description: string;
  requirements: string;
  skills: string;
  experienceLevel: string;
  education: string;
  jobType: string;
  niceToHaves: string;
  benefits: string;
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
  salaryRange: string;
  description: string;
  requirements: string;
  skills: string;
  experienceLevel: string;
  education: string;
  jobType: string;
  niceToHaves: string;
  benefits: string;
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

export function readJobsCache(tenantId: string): JobsPageCache | null {
  try {
    const raw = sessionStorage.getItem(JOBS_CACHE_PREFIX + tenantId);
    if (!raw) return null;
    return JSON.parse(raw) as JobsPageCache;
  } catch {
    return null;
  }
}

export function writeJobsCache(tenantId: string, cache: JobsPageCache) {
  try {
    sessionStorage.setItem(JOBS_CACHE_PREFIX + tenantId, JSON.stringify(cache));
  } catch {
    // ignore quota / private mode
  }
}

export function upsertJobInCache(tenantId: string, job: Job) {
  const cache = readJobsCache(tenantId);
  if (!cache) {
    writeJobsCache(tenantId, {
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
  writeJobsCache(tenantId, {
    ...cache,
    items: [job, ...cache.items.filter((item) => item.id !== job.id)],
    total: exists ? cache.total : cache.total + 1,
  });
}

export function removeJobFromCache(tenantId: string, jobId: string) {
  const cache = readJobsCache(tenantId);
  if (!cache) return;
  writeJobsCache(tenantId, {
    ...cache,
    items: cache.items.filter((item) => item.id !== jobId),
    total: Math.max(0, cache.total - (cache.items.some((item) => item.id === jobId) ? 1 : 0)),
  });
}

// --- API 函数 ---

/** 获取职位统计 */
export async function fetchJobStats(tenantId: string): Promise<JobStats> {
  return apiFetch<JobStats>(`/tenants/${tenantId}/jobs/stats`);
}

/** 分页查询职位列表 */
export async function fetchJobs(
  tenantId: string,
  params: { search?: string; status?: string; page?: number; pageSize?: number } = {},
): Promise<JobListResult> {
  const searchParams = new URLSearchParams();
  if (params.search) searchParams.set("search", params.search);
  if (params.status) searchParams.set("status", params.status);
  searchParams.set("page", String(params.page ?? 1));
  searchParams.set("pageSize", String(params.pageSize ?? 10));
  return apiFetch<JobListResult>(
    `/tenants/${tenantId}/jobs?${searchParams.toString()}`,
  );
}

/** 获取职位详情 */
export async function fetchJob(tenantId: string, jobId: string): Promise<Job> {
  return apiFetch<Job>(`/tenants/${tenantId}/jobs/${jobId}`);
}

/** 创建职位 */
export async function createJob(tenantId: string, input: JobInput): Promise<Job> {
  return apiFetch<Job>(`/tenants/${tenantId}/jobs`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 更新职位 */
export async function updateJob(tenantId: string, jobId: string, input: JobInput): Promise<Job> {
  return apiFetch<Job>(`/tenants/${tenantId}/jobs/${jobId}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/** 更新职位状态 */
export async function updateJobStatus(tenantId: string, jobId: string, status: string): Promise<Job> {
  return apiFetch<Job>(`/tenants/${tenantId}/jobs/${jobId}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

/** 删除职位 */
export async function deleteJob(tenantId: string, jobId: string): Promise<void> {
  return apiFetch<void>(`/tenants/${tenantId}/jobs/${jobId}`, {
    method: "DELETE",
  });
}

/** 批量更新状态 */
export async function batchUpdateStatus(tenantId: string, jobIds: string[], status: string): Promise<void> {
  return apiFetch<void>(`/tenants/${tenantId}/jobs/batch/status`, {
    method: "POST",
    body: JSON.stringify({ jobIds, status }),
  });
}

/** 批量删除 */
export async function batchDelete(tenantId: string, jobIds: string[]): Promise<void> {
  return apiFetch<void>(`/tenants/${tenantId}/jobs/batch/delete`, {
    method: "POST",
    body: JSON.stringify({ jobIds }),
  });
}

/** 获取职位版本历史 */
export async function fetchJobVersions(tenantId: string, jobId: string): Promise<JobVersion[]> {
  return apiFetch<JobVersion[]>(`/tenants/${tenantId}/jobs/${jobId}/versions`);
}
