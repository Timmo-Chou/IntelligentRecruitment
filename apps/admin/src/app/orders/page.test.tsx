// 订单页面组件测试：验证权限控制、列表渲染及合同套餐选择/提交值。
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => cleanup());

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

vi.mock("@tanstack/react-query", () => ({
  useQuery: ({ queryKey }: { queryKey: string[] }) => {
    if (queryKey[0] === "contract-order-plans") return { data: [{ id: "plan-id-42", code: "ENTERPRISE_PLAN", display_name: "企业标准套餐", audience: "ENTERPRISE", status: "PUBLISHED" }], isLoading: false, isError: false };
    if (queryKey[0] === "contract-orders") return { data: [], isLoading: false, isError: false };
    if (queryKey[0] === "contract-order-tenants") return { data: { items: [], total: 0 }, isLoading: false, isError: false };
    return { data: { items: [{ id: "order-1", order_no: "ORDER20240101001", user_id: "user-1", amount: 99, payment_status: "PENDING", created_at: "2024-01-01" }], total: 1, page: 1, size: 20 }, isLoading: false, isError: false };
  },
  useMutation: (options: { mutationFn: (...args: unknown[]) => unknown; onSuccess?: () => void; onError?: (error: unknown) => void }) => ({
    mutate: (...args: unknown[]) => { void Promise.resolve().then(() => options.mutationFn(...args)).then(() => options.onSuccess?.()).catch(error => options.onError?.(error)); },
    isPending: false,
  }),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));

const mockAdminApiFetch = vi.hoisted(() => vi.fn());
vi.mock("@/lib/admin-api-client", () => ({ adminApiFetch: mockAdminApiFetch }));

const mockHasPermission = vi.hoisted(() => vi.fn(() => false));
vi.mock("@/lib/use-permission", () => ({ usePermission: () => ({ hasPermission: mockHasPermission }) }));

import OrdersPage from "./page";

describe("OrdersPage", () => {
  it("renders the orders list", () => {
    mockHasPermission.mockReturnValue(false);
    render(<OrdersPage />);
    expect(screen.getByText("订单管理")).toBeInTheDocument();
    expect(screen.getByText("ORDER20240101001")).toBeInTheDocument();
    expect(screen.getByText("¥99.00")).toBeInTheDocument();
  });

  it("hides payment controls without ADMIN_ORDER_EDIT", () => {
    mockHasPermission.mockReturnValue(false);
    render(<OrdersPage />);
    expect(screen.queryByText("标记已支付")).not.toBeInTheDocument();
  });

  it("shows payment controls with ADMIN_ORDER_EDIT", () => {
    mockHasPermission.mockReturnValue(true);
    render(<OrdersPage />);
    expect(screen.getByText("标记已支付")).toBeInTheDocument();
  });

  it("shows the Chinese plan name and submits the original plan id", async () => {
    mockHasPermission.mockReturnValue(true);
    mockAdminApiFetch.mockResolvedValue({ id: "contract-1" });
    render(<OrdersPage />);
    fireEvent.click(screen.getByRole("button", { name: "企业合同订单" }));
    fireEvent.click(screen.getByRole("button", { name: "创建合同订单" }));

    const planSelect = screen.getByLabelText("企业套餐") as HTMLSelectElement;
    expect(screen.getByRole("option", { name: "企业标准套餐" })).toHaveValue("plan-id-42");
    fireEvent.change(planSelect, { target: { value: "plan-id-42" } });
    fireEvent.change(screen.getByLabelText("企业名称"), { target: { value: "示例企业" } });
    fireEvent.change(screen.getByLabelText("统一社会信用代码"), { target: { value: "91310000TEST" } });
    fireEvent.change(screen.getByLabelText("合同编号"), { target: { value: "CONTRACT-42" } });
    fireEvent.change(screen.getByLabelText("席位数"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText("金额（微元）"), { target: { value: "1000000" } });
    fireEvent.change(screen.getByLabelText("开始时间"), { target: { value: "2026-09-01T00:00" } });
    fireEvent.change(screen.getByLabelText("结束时间"), { target: { value: "2027-09-01T00:00" } });
    fireEvent.click(screen.getAllByRole("button", { name: "创建合同订单" }).at(-1)!);

    await waitFor(() => expect(mockAdminApiFetch).toHaveBeenCalledWith("/platform/recruitment/contract-orders", expect.objectContaining({
      method: "POST",
      body: expect.stringContaining('"planId":"plan-id-42"'),
    })));
    expect(mockAdminApiFetch.mock.calls.at(-1)?.[1]?.body).not.toContain("ENTERPRISE_PLAN");
  });
});
