import { apiFetch } from "@/lib/api-client";

export type ScreeningDimension = {
  name: string; weight: number; description: string; required: boolean;
  exclusionRule: string; missingPolicy: "REVIEW" | "NEGOTIABLE" | "IGNORE";
};
export type ScreeningQuote = {
  id: string; workspaceId: string; planId: string; planVersionId: string; candidateCount: number;
  pricingVersion: string; unitPriceMinor: number; estimatedAmountMinor: number;
  availableAmountMinor: number; expiresAt: string;
};
export type ScreeningPricing = {
  pricingVersion: string; unitPriceMinor: number; quoteTtlSeconds: number;
};
export type ScreeningPlan = {
  id: string; jobId: string; jobTitle: string; currentVersionId: string; versionNumber: number;
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
  progress: number; scenario: string; pricingVersion: string; unitPriceMinor: number;
  estimatedAmountMinor: number; settledAmountMinor: number; items: ScreeningItem[];
  createdAt: string; completedAt: string | null;
};
export type ScreeningRunSummary = {
  id: string; jobId: string; jobTitle: string; status: string; progress: number; totalItems: number;
  succeededItems: number; estimatedAmountMinor: number; settledAmountMinor: number; createdAt: string;
};

export const defaultScreeningDimensions: ScreeningDimension[] = [
  { name: "基本信息", weight: 10, description: "地点、到岗时间等基础条件", required: false, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "教育背景", weight: 10, description: "学历与专业背景", required: false, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "职业履历", weight: 25, description: "岗位相关经历与稳定性", required: false, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "专业技能", weight: 30, description: "核心技能和技术深度", required: true, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "项目成果", weight: 15, description: "可验证的项目结果", required: false, exclusionRule: "", missingPolicy: "REVIEW" },
  { name: "求职动机", weight: 10, description: "岗位意愿和发展匹配", required: false, exclusionRule: "", missingPolicy: "NEGOTIABLE" },
];

export function fetchScreeningPlans(workspaceId: string) {
  return apiFetch<ScreeningPlan[]>(`/workspaces/${workspaceId}/screening-plans`);
}
export function createScreeningPlan(workspaceId: string, input: { jobId: string; name: string; dimensions: ScreeningDimension[] }) {
  return apiFetch<ScreeningPlan>(`/workspaces/${workspaceId}/screening-plans`, { method: "POST", body: JSON.stringify(input) });
}
export function updateScreeningPlan(workspaceId: string, planId: string, dimensions: ScreeningDimension[]) {
  return apiFetch<ScreeningPlan>(`/workspaces/${workspaceId}/screening-plans/${planId}`, { method: "PUT", body: JSON.stringify({ dimensions }) });
}
export function fetchScreeningRuns(workspaceId: string) {
  return apiFetch<ScreeningRunSummary[]>(`/workspaces/${workspaceId}/screening-runs`);
}
export function fetchScreeningRun(workspaceId: string, runId: string) {
  return apiFetch<ScreeningRun>(`/workspaces/${workspaceId}/screening-runs/${runId}`);
}
export function createScreeningQuote(workspaceId: string, planId: string, candidateIds: string[]) {
  return apiFetch<ScreeningQuote>(`/workspaces/${workspaceId}/screening-quotes`, {
    method: "POST", body: JSON.stringify({ planId, candidateIds }),
  });
}
export function fetchScreeningPricing(workspaceId: string) {
  return apiFetch<ScreeningPricing>(`/workspaces/${workspaceId}/screening-pricing`);
}
export function startScreeningRun(workspaceId: string, planId: string, candidateIds: string[], scenario: string, quoteId: string, idempotencyKey: string) {
  return apiFetch<ScreeningRun>(`/workspaces/${workspaceId}/screening-runs`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ planId, candidateIds, scenario, quoteId }),
  });
}
export function cancelScreeningRun(workspaceId: string, runId: string, idempotencyKey: string) {
  return apiFetch<ScreeningRun>(`/workspaces/${workspaceId}/screening-runs/${runId}/cancel`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
  });
}
export function createRetryScreeningQuote(workspaceId: string, runId: string) {
  return apiFetch<ScreeningQuote>(`/workspaces/${workspaceId}/screening-runs/${runId}/retry-quote`, {
    method: "POST",
  });
}
export function retryFailedScreening(workspaceId: string, runId: string, quoteId: string, idempotencyKey: string) {
  return apiFetch<ScreeningRun>(`/workspaces/${workspaceId}/screening-runs/${runId}/retry-failed`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: JSON.stringify({ quoteId }),
  });
}
