import { apiDownload, apiFetch } from "@/lib/api-client";
import type { TalentProfileInput } from "@/lib/talent-constants";
import { skillsFromProfile } from "@/lib/talent-constants";

export type CandidateSummary = {
  id: string;
  companyId: string | null;
  workspaceId: string;
  displayNameMasked: string;
  /** 当前 Workspace 授权用户可直接读取的真实联系方式。 */
  phone: string;
  email: string;
  status: string;
  parseStatus: string;
  originalFilename: string;
  headline: string;
  yearsExperience: number;
  highestEducation: string;
  skills: string[];
  createdAt: string;
  updatedAt: string;
  matchScore?: number | null;
  matchedJobTitle?: string | null;
  profileJson?: string | null;
};

export type CandidateDetail = CandidateSummary & {
  currentParseVersionId: string | null;
  resumeFileId: string;
  errorCode: string | null;
  mediaType: string;
  sizeBytes: number;
  parseVersion: number;
  workExperience: string[];
  educationExperience: string[];
  summary: string;
  warnings: string[];
};

export type CandidateListResult = { items: CandidateSummary[]; total: number; page: number; pageSize: number };
export type RevealedPii = { fullName: string; email: string; phone: string };

export type StatPoint = {
  count: number;
  previousCount: number;
  changePercent: number;
};

export type CandidateStats = {
  total: StatPoint;
  active: StatPoint;
  highMatch: StatPoint;
  dormant: StatPoint;
  inPool: StatPoint;
  highMatchThreshold: number;
};

export type CandidateSegment = "" | "ACTIVE_TALENT" | "HIGH_MATCH" | "DORMANT" | "IN_POOL";

export type CandidateListQuery = {
  search?: string;
  status?: string;
  segment?: CandidateSegment;
  minMatchScore?: number;
  industry?: string;
  city?: string;
  tags?: string;
  yearsMin?: number;
  yearsMax?: number;
  education?: string;
  source?: string;
  activity?: string;
  talentStatus?: string;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  pageSize?: number;
};

export type CandidateProfile = {
  gender?: string;
  province?: string;
  city?: string;
  district?: string;
  currentCompany?: string;
  currentTitle?: string;
  industry?: string;
  tags?: string[];
  source?: string;
  activityLevel?: string;
  talentStatus?: string;
  [key: string]: unknown;
};

export function parseProfile(json?: string | null): CandidateProfile {
  if (!json) return {};
  try {
    return JSON.parse(json) as CandidateProfile;
  } catch {
    return {};
  }
}

export function fetchCandidateStats(workspaceId: string) {
  return apiFetch<CandidateStats>(`/workspaces/${workspaceId}/candidates/stats`);
}

export function fetchCandidates(workspaceId: string, options: CandidateListQuery = {}) {
  const query = new URLSearchParams({
    page: String(options.page ?? 1),
    pageSize: String(options.pageSize ?? 10),
  });
  const entries: [keyof CandidateListQuery, string | number | undefined][] = [
    ["search", options.search],
    ["status", options.status],
    ["segment", options.segment],
    ["minMatchScore", options.minMatchScore],
    ["industry", options.industry],
    ["city", options.city],
    ["tags", options.tags],
    ["yearsMin", options.yearsMin],
    ["yearsMax", options.yearsMax],
    ["education", options.education],
    ["source", options.source],
    ["activity", options.activity],
    ["talentStatus", options.talentStatus],
    ["createdFrom", options.createdFrom],
    ["createdTo", options.createdTo],
  ];
  for (const [key, value] of entries) {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      query.set(key, String(value));
    }
}
  return apiFetch<CandidateListResult>(`/workspaces/${workspaceId}/candidates?${query}`);
}

export function fetchCandidate(workspaceId: string, candidateId: string) {
  return apiFetch<CandidateDetail>(`/workspaces/${workspaceId}/candidates/${candidateId}`);
}

export function createTalent(workspaceId: string, input: TalentProfileInput) {
  const skills = skillsFromProfile(input);
  return apiFetch<CandidateDetail>(`/workspaces/${workspaceId}/candidates`, {
    method: "POST",
    body: JSON.stringify({
      fullName: input.fullName,
      gender: input.gender,
      phone: input.phone,
      email: input.email,
      province: input.province,
      city: input.city,
      district: input.district,
      currentCompany: input.currentCompany,
      currentTitle: input.currentTitle,
      currentLevel: input.currentLevel,
      yearsExperience: input.yearsExperience,
      industry: input.industry,
      highestEducation: input.highestEducation,
      school: input.school,
      major: input.major,
      graduateAt: input.graduateAt,
      professionalSkills: input.professionalSkills,
      softwareSkills: input.softwareSkills,
      managementSkills: input.managementSkills,
      industrySkills: input.industrySkills,
      tags: input.tags,
      source: input.source || "手动新增",
      certificates: input.certificates || "",
      jobCategory: input.jobCategory || "",
      age: input.age || "",
      // keep skills mirrored for convenience
      skills,
    }),
  });
}

export function uploadResume(workspaceId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<CandidateDetail>(`/workspaces/${workspaceId}/candidates/resumes`, {
    method: "POST", body,
  });
}

export function retryResumeParse(workspaceId: string, candidateId: string) {
  return apiFetch<CandidateDetail>(`/workspaces/${workspaceId}/candidates/${candidateId}/parse-retries`, {
    method: "POST",
  });
}

export function revealCandidate(workspaceId: string, candidateId: string) {
  return apiFetch<RevealedPii>(`/workspaces/${workspaceId}/candidates/${candidateId}/reveal`, { method: "POST" });
}

export function updateCandidateTags(workspaceId: string, candidateId: string, tags: string[]) {
  return apiFetch<CandidateDetail>(`/workspaces/${workspaceId}/candidates/${candidateId}/tags`, {
    method: "PATCH",
    body: JSON.stringify({ tags }),
  });
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
