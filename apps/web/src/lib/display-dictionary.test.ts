import { describe, expect, it } from "vitest";
import { getResumeParseStatusLabel, getSafeErrorMessage, preserveCode, resolveCatalogLabel, resolveCodeLabel, resolveFreeTextLabel } from "@intelligent-recruitment/display-dictionary";

describe("display dictionary", () => {
  it("maps known values using the field domain", () => {
    expect(resolveCodeLabel("tenant.status", "TRIAL_ACTIVE").label).toBe("试用中");
    expect(resolveCodeLabel("creditLot.status", "VOIDED").label).toBe("已作废");
  });

  it("keeps historical values explainable without using them as new options", () => {
    expect(resolveCodeLabel("resume.statusCompatibility", "PARSE_FAILED").label).toBe("解析失败");
  });

  it("uses a safe Chinese fallback for unknown values and errors", () => {
    expect(resolveCodeLabel("tenant.status", "FUTURE_STATE", "状态暂未识别").label).toBe("状态暂未识别");
    expect(getSafeErrorMessage("FUTURE_ERROR")).toBe("操作暂未完成，请稍后重试。");
    expect(getSafeErrorMessage("FUTURE_ERROR", "req-123")).toContain("诊断编号：req-123");
  });

  it("does not change codes used in requests or filters", () => {
    expect(preserveCode("AI_RESUME_PARSING")).toBe("AI_RESUME_PARSING");
  });

  it("uses Chinese catalog names but does not expose untranslated catalog names", () => {
    expect(resolveCatalogLabel({ code: "PLAN_X", displayName: "企业标准套餐", missingLabel: "套餐名称待补充" }).label).toBe("企业标准套餐");
    expect(resolveCatalogLabel({ code: "PLAN_X", displayName: "Enterprise Plan", displayNameIsCanonical: true, missingLabel: "套餐名称待补充" }).label).toBe("Enterprise Plan");
  });

  it("preserves free Chinese interview categories and hides unknown English categories", () => {
    expect(resolveFreeTextLabel("interview.questionCategory", "管理能力", "题目类别暂未配置")).toBe("管理能力");
    expect(resolveFreeTextLabel("interview.questionCategory", "TECH_LEADERSHIP", "题目类别暂未配置")).toBe("题目类别暂未配置");
  });

  it("maps historical resume values and full screening settlement lifecycle", () => {
    expect(getResumeParseStatusLabel("PARSE_FAILED")).toBe("解析失败");
    expect(resolveCodeLabel("screening.settlementStatus", "RELEASED").label).toBe("积分已释放");
    expect(resolveCodeLabel("screening.settlementStatus", "RECONCILIATION_REQUIRED").label).toBe("结果待核对");
  });

  it("shows known common server errors in Chinese", () => {
    expect(getSafeErrorMessage("INSUFFICIENT_CREDITS")).toContain("积分不足");
    expect(getSafeErrorMessage("INVALID_CREDENTIALS")).toContain("用户名或密码");
    expect(getSafeErrorMessage("PLATFORM_USER_NOT_FOUND")).toBe("用户不存在。");
    expect(getSafeErrorMessage("BOSS_SESSION_REQUIRED")).toContain("重新登录");
  });
});
