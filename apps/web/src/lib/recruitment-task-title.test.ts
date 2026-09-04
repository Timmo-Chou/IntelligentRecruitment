import { describe, expect, it } from "vitest";
import { resolveRecruitmentTaskTitle } from "./recruitment-task-title";

describe("resolveRecruitmentTaskTitle", () => {
  it.each([
    ["JD_GENERATION", "JD生成"],
    ["RESUME_PARSING", "AI简历解析"],
    ["CANDIDATE_SCREENING", "AI简历筛选"],
    ["INTERVIEW_KIT", "AI面试出题"],
  ] as const)("uses %s as the default task title", (feature, title) => {
    expect(resolveRecruitmentTaskTitle("", feature)).toBe(title);
  });

  it("keeps an explicitly entered task title", () => {
    expect(resolveRecruitmentTaskTitle("  研发岗位  ", "JD_GENERATION")).toBe("研发岗位");
  });
});
