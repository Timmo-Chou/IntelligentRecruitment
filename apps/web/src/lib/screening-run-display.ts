import { getDisplayLabel } from "@intelligent-recruitment/display-dictionary";

export function screeningRunSettlementSummary(run: {
  status: string;
  progress: number;
  settlementStatus: string | null;
}): string {
  const status = getDisplayLabel("screening.runStatus", run.status, "筛选状态暂未识别");
  const settlement = getDisplayLabel("screening.settlementStatus", run.settlementStatus, "结算状态暂未识别");
  if (run.status === "RUNNING") return `正在筛选，当前进度 ${run.progress}%`;
  if (["PENDING", "EXECUTING", "RUNNING", "USAGE_PENDING", "RELEASE_PENDING", "CANCEL_REQUESTED", "CANCELLATION_PENDING"].includes(run.settlementStatus ?? "")) {
    return `筛选${status}，${settlement}。`;
  }
  if (run.settlementStatus === "SETTLEMENT_FAILED") return `筛选${status}，${settlement}，请联系平台运营。`;
  if (run.settlementStatus === "RECONCILIATION_REQUIRED") return `筛选${status}，${settlement}，请稍后查看。`;
  if (["RELEASED", "CANCELLED", "MIXED"].includes(run.settlementStatus ?? "")) return `筛选${status}，${settlement}。`;
  if (run.status === "COMPLETED" || run.status === "PARTIAL_FAILED") {
    if (run.settlementStatus === "SETTLED") return `筛选${status}，${settlement}，可在企业账单中查看本账号的积分消耗。`;
    return `筛选${status}，可在企业账单中查看本账号的积分消耗。`;
  }
  return `筛选${status}。`;
}
