import { describe, expect, it } from "vitest";
import { screeningRunSettlementSummary } from "./screening-run-display";

describe("screening settlement display", () => {
  it("keeps cancellation and credit-release states separate from screening results", () => {
    const cases = [
      ["RELEASE_PENDING", "积分释放中"],
      ["RELEASED", "积分已释放"],
      ["MIXED", "部分扣费、部分释放"],
      ["CANCEL_REQUESTED", "正在申请取消"],
      ["CANCELLATION_PENDING", "取消处理中"],
      ["RECONCILIATION_REQUIRED", "结果待核对"],
      ["SETTLEMENT_FAILED", "结算失败"],
      ["SETTLED", "结算完成"],
      ["CANCELLED", "已取消"],
    ];

    for (const [settlementStatus, label] of cases) {
      const summary = screeningRunSettlementSummary({ status: "COMPLETED", progress: 100, settlementStatus });
      expect(summary).toContain(label);
      expect(summary).not.toContain("筛选失败");
    }
  });

  it("shows live screening progress without collapsing it into settlement state", () => {
    expect(screeningRunSettlementSummary({ status: "RUNNING", progress: 45, settlementStatus: "RELEASE_PENDING" }))
      .toBe("正在筛选，当前进度 45%");
  });
});
