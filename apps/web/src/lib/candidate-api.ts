import { apiDownload, apiFetch } from "@/lib/api-client";

export type CandidateSummary = {
  id: string; companyId: string | null; workspaceId: string; displayNameMasked: string;
  status: string; parseStatus: string; originalFilename: string; headline: string;
  yearsExperience: number; highestEducation: string; skills: string[]; updatedAt: string;
};

export type CandidateDetail = CandidateSummary & {
  currentParseVersionId: string | null; resumeFileId: string; errorCode: string | null;
  mediaType: string; sizeBytes: number; parseVersion: number; workExperience: string[];
  educationExperience: string[]; summary: string; warnings: string[]; createdAt: string;
};

export type CandidateListResult = { items: CandidateSummary[]; total: number; page: number; pageSize: number };
export type RevealedPii = { fullName: string; email: string; phone: string };

export function fetchCandidates(workspaceId: string, search = "", status = "") {
  const query = new URLSearchParams({ page: "1", pageSize: "100" });
  if (search) query.set("search", search);
  if (status) query.set("status", status);
  return apiFetch<CandidateListResult>(`/workspaces/${workspaceId}/candidates?${query}`);
}

export function fetchCandidate(workspaceId: string, candidateId: string) {
  return apiFetch<CandidateDetail>(`/workspaces/${workspaceId}/candidates/${candidateId}`);
}

export function uploadResume(workspaceId: string, file: File, scenario = "NORMAL") {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<CandidateDetail>(`/workspaces/${workspaceId}/candidates/resumes?scenario=${scenario}`, {
    method: "POST", body,
  });
}

export function retryResumeParse(workspaceId: string, candidateId: string, scenario = "NORMAL") {
  return apiFetch<CandidateDetail>(`/workspaces/${workspaceId}/candidates/${candidateId}/parse-retries`, {
    method: "POST", body: JSON.stringify({ scenario }),
  });
}

export function revealCandidate(workspaceId: string, candidateId: string) {
  return apiFetch<RevealedPii>(`/workspaces/${workspaceId}/candidates/${candidateId}/reveal`, { method: "POST" });
}

export function deleteCandidate(workspaceId: string, candidateId: string) {
  return apiFetch<void>(`/workspaces/${workspaceId}/candidates/${candidateId}`, { method: "DELETE" });
}

export async function downloadResume(workspaceId: string, candidate: CandidateDetail) {
  const blob = await apiDownload(`/workspaces/${workspaceId}/candidates/${candidate.id}/resume-file`);
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = candidate.originalFilename;
  anchor.click();
  URL.revokeObjectURL(url);
}
