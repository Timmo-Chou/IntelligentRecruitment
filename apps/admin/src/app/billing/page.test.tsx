// 账本管理页面组件测试：验证积分调整按钮的权限控制
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({
    data: [
      {
        id: "adj-1",
        tenant_id: "tenant-1",
        adjustment_type: "CORRECTION_ADD",
        amount: 100,
        reason: "测试调整",
        created_at: "2024-01-01",
        expires_at: null,
      },
    ],
    isLoading: false,
  }),
  useMutation: () => ({ mutate: vi.fn(), isPending: false }),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));

vi.mock("@/lib/admin-api-client", () => ({ adminApiFetch: vi.fn() }));

const mockHasPermission = vi.hoisted(() => vi.fn(() => false));
vi.mock("@/lib/use-permission", () => ({
  usePermission: () => ({ hasPermission: mockHasPermission }),
}));

import BillingPage from "./page";

describe("BillingPage", () => {
  it("渲染积分调整记录列表", () => {
    mockHasPermission.mockReturnValue(false);
    render(<BillingPage />);
    expect(screen.getByText("账本管理")).toBeInTheDocument();
    expect(screen.getByText("测试调整")).toBeInTheDocument();
  });

  it("无 ADMIN_BILLING_EDIT 权限时不显示积分调整按钮", () => {
    mockHasPermission.mockReturnValue(false);
    render(<BillingPage />);
    expect(screen.queryByText("积分调整")).not.toBeInTheDocument();
  });

  it("有 ADMIN_BILLING_EDIT 权限时显示积分调整按钮", () => {
    mockHasPermission.mockReturnValue(true);
    render(<BillingPage />);
    expect(screen.getByText("积分调整")).toBeInTheDocument();
  });
});
