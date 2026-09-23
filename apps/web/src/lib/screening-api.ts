import { apiFetch } from "@/lib/api-client";

export type ScreeningDimension = {
  name: string; weight: number; description: string; required: boolean;
  exclusionRule: string; missingPolicy: "REVIEW" | "NEGOTIABLE" | "IGNORE";
};
export type ScreeningPlan = {
  id: string; recruitmentTaskId: string | null; jobId: string; jobTitle: string; currentVersionId: string; versionNumber: number;
  dimensions: ScreeningDimension[]; name: string; status: string; updatedAt: string;
};
export type ScreeningItem = {
  id: string; candidateId: string; candidateName: string; status: string; errorCode: string | null;
  attemptNumber: number; score: number | null; level: string | null; matchedPoints: string[];
  unmatchedPoints: string[]; negotiablePoints: string[]; missingInformation: string[];
  risks: string[]; evidence: string[];
};
export type ScreeningRun = {
  id: string; jobId: string; jobTitle: string; planId: string; planName: string; status: string;
  progress: number; scenario: string; items: ScreeningItem[];
  createdAt: string; completedAt: string | null; recruitmentTaskId: string | null; settlementStatus: string | null;
};
export type ScreeningRunSummary = {
  id: string; jobId: string; jobTitle: string; status: string; progress: number; totalItems: number;
  succeededItems: number; createdAt: string;
  recruitmentTaskId: string | null;
};

export const defaultScreeningDimensions: ScreeningDimension[] = [
  { name: "基本信息", weight: 10, description: "地点、到岗时间等基础条件", required: false, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "教育背景", weight: 10, description: "学历与专业背景", required: false, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "职业履历", weight: 25, description: "岗位相关经历与稳定性", required: false, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "专业技能", weight: 30, description: "核心技能和技术深度", required: true, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "项目成果", weight: 15, description: "可验证的项目结果", required: false, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "求职动机", weight: 10, description: "岗位意愿和发展匹配", required: false, exclusionRule: "", missingPolicy: "NEGOTIABLE" },
];

export function fetchScreeningPlans(tenantId: string, recruitmentTaskId?: string) {
  const query = recruitmentTaskId ? `?recruitmentTaskId=${encodeURIComponent(recruitmentTaskId)}` : "";
  return apiFetch<ScreeningPlan[]>(`/tenants/${tenantId}/screening-plans${query}`);
}
export function createScreeningPlan(tenantId: string, input: { jobId: string; name: string; dimensions: ScreeningDimension[]; recruitmentTaskId?: string }) {
  return apiFetch<ScreeningPlan>(`/tenants/${tenantId}/screening-plans`, { method: "POST", body: JSON.stringify(input) });
}
export function updateScreeningPlan(tenantId: string, planId: string, dimensions: ScreeningDimension[], jobId?: string) {
  return apiFetch<ScreeningPlan>(`/tenants/${tenantId}/screening-plans/${planId}`, { method: "PUT", body: JSON.stringify({ dimensions, jobId }) });
}
export function fetchScreeningRuns(tenantId: string, recruitmentTaskId?: string) {
  const query = recruitmentTaskId ? `?recruitmentTaskId=${encodeURIComponent(recruitmentTaskId)}` : "";
  return apiFetch<ScreeningRunSummary[]>(`/tenants/${tenantId}/screening-runs${query}`);
}
export function fetchScreeningRun(tenantId: string, runId: string) {
  return apiFetch<ScreeningRun>(`/tenants/${tenantId}/screening-runs/${runId}`);
}
export function startScreeningRun(tenantId: string, planId: string, candidateIds: string[], idempotencyKey: string) {
  return apiFetch<ScreeningRun>(`/tenants/${tenantId}/screening-runs`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ planId, candidateIds }),
  });
}
export function cancelScreeningRun(tenantId: string, runId: string, idempotencyKey: string) {
  return apiFetch<ScreeningRun>(`/tenants/${tenantId}/screening-runs/${runId}/cancel`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
  });
}
export function retryFailedScreening(tenantId: string, runId: string, idempotencyKey: string) {
  return apiFetch<ScreeningRun>(`/tenants/${tenantId}/screening-runs/${runId}/retry-failed`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
  });
}
