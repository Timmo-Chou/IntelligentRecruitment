import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const state = vi.hoisted(() => ({ ruleError: false, api: vi.fn() }));
vi.mock("@/lib/admin-api-client", () => ({ adminApiFetch: state.api }));
vi.mock("@/lib/use-permission", () => ({ usePermission: () => ({ hasPermission: () => true }) }));
vi.mock("@tanstack/react-query", () => ({
  useQuery: ({ queryKey }: { queryKey: string[] }) => {
    const rows = queryKey[0] === "products" ? [{ id: "plan-original", code: "E2E_PLAN", display_name: "原测试套餐", audience: "ENTERPRISE", status: "PUBLISHED", version: 1, billing_period: "YEAR", duration_months: 12, per_seat_credit_amount: 1000, price_micro_yuan: 0,
      feature_snapshot: { type: "jsonb", value: JSON.stringify({ AI_RECRUITMENT_USE: true, AI_JD_GENERATION: false, LEGACY_FEATURE: false }) } }]
      : queryKey[0] === "platform-permission-catalog" ? [{ code: "AI_RECRUITMENT_USE", status: "ACTIVE", frontend: "USER", product_domain: "RECRUITMENT" }]
      : [{ status: "PUBLISHED", entitlement_feature_code: "AI_RESUME_PARSING" }, { status: "PUBLISHED", entitlement_feature_code: "AI_RESUME_PARSING" }, { status: "RETIRED", entitlement_feature_code: "OLD_RESUME_CODE" }];
    return { data: rows, isLoading: false, isError: queryKey[0] === "product-entitlement-rules" && state.ruleError };
  },
  useMutation: (options: { mutationFn: () => unknown; onSuccess?: () => void; onError?: (error: unknown) => void }) => ({
    mutate: () => { void Promise.resolve().then(options.mutationFn).then(() => options.onSuccess?.()).catch(error => options.onError?.(error)); }, isPending: false,
  }),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));

import ProductsPage from "./page";

beforeEach(() => { state.ruleError = false; state.api.mockReset(); state.api.mockResolvedValue({ plan_id: "plan-new" }); });
afterEach(cleanup);

describe("套餐业务权益配置", () => {
  it("复制套餐并新增解析权益，发布原代码与布尔值，保留原禁用和历史权益", async () => {
    render(<ProductsPage />);
    fireEvent.click(screen.getByRole("button", { name: "创建新版本" }));
    const resumeOptions = screen.getAllByRole("checkbox", { name: /简历解析/ });
    expect(resumeOptions).toHaveLength(1);
    expect(resumeOptions[0]).not.toBeChecked();
    fireEvent.click(resumeOptions[0]);
    fireEvent.click(screen.getAllByRole("button", { name: "发布配置" }).at(-1)!);
    await waitFor(() => expect(state.api).toHaveBeenCalledTimes(1));
    expect(state.api.mock.calls[0][0]).toBe("/platform/recruitment/plans");
    const request = JSON.parse(state.api.mock.calls[0][1].body);
    expect(request).toMatchObject({ code: "E2E_PLAN", displayName: "原测试套餐", version: 2, perSeatCreditAmount: 1000, priceMicroYuan: 0 });
    expect(JSON.parse(request.featureSnapshot)).toEqual({ AI_RECRUITMENT_USE: true, AI_JD_GENERATION: false, LEGACY_FEATURE: false, AI_RESUME_PARSING: true });
    expect(state.api.mock.calls.some(([path]) => path.includes("activate") || path.includes("entitlement-overrides"))).toBe(false);
  });

  it("业务权益源读取失败时阻止发布，不将缺少权益误当成空配置", async () => {
    state.ruleError = true;
    render(<ProductsPage />);
    fireEvent.click(screen.getByRole("button", { name: "创建新版本" }));
    fireEvent.click(screen.getAllByRole("button", { name: "发布配置" }).at(-1)!);
    await waitFor(() => expect(screen.getByText("请等待功能权限和业务权益配置加载成功后再发布")).toBeInTheDocument());
    expect(state.api).not.toHaveBeenCalled();
  });
});
