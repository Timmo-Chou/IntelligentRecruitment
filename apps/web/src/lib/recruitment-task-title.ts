export const recruitmentFeatureTaskTitles = {
  JD_GENERATION: "JD生成",
  RESUME_PARSING: "AI简历解析",
  CANDIDATE_SCREENING: "AI简历筛选",
  INTERVIEW_KIT: "AI面试出题",
} as const;

export type RecruitmentFeature = keyof typeof recruitmentFeatureTaskTitles;

export function resolveRecruitmentTaskTitle(inputTitle: string, feature: RecruitmentFeature | null) {
  const explicitTitle = inputTitle.trim();
  if (explicitTitle) return explicitTitle;
  return feature ? recruitmentFeatureTaskTitles[feature] : "智能招聘任务";
}
