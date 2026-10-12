import { describe, expect, it } from "vitest";
import { resolveRecruitmentTaskTitle } from "./recruitment-task-title";

describe("resolveRecruitmentTaskTitle", () => {
  it.each([
    ["JD_GENERATION", "职位描述生成"],
    ["RESUME_PARSING", "简历解析"],
    ["CANDIDATE_SCREENING", "简历筛选"],
    ["INTERVIEW_KIT", "面试题包生成"],
  ] as const)("uses %s as the default task title", (feature, title) => {
    expect(resolveRecruitmentTaskTitle("", feature)).toBe(title);
  });

  it("keeps an explicitly entered task title", () => {
    expect(resolveRecruitmentTaskTitle("  研发岗位  ", "JD_GENERATION")).toBe("研发岗位");
  });
});
