export const recruitmentFeatureTaskTitles = {
  JD_GENERATION: "职位描述生成",
  RESUME_PARSING: "简历解析",
  CANDIDATE_SCREENING: "简历筛选",
  INTERVIEW_KIT: "面试题包生成",
} as const;

export type RecruitmentFeature = keyof typeof recruitmentFeatureTaskTitles;

export function resolveRecruitmentTaskTitle(inputTitle: string, feature: RecruitmentFeature | null) {
  const explicitTitle = inputTitle.trim();
  if (explicitTitle) return explicitTitle;
  return feature ? recruitmentFeatureTaskTitles[feature] : "智能招聘任务";
}
