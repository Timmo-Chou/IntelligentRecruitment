// 订单管理页面组件测试：验证权限控制和列表渲染
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({
    data: {
      items: [
        {
          id: "order-1",
          order_no: "ORDER20240101001",
          user_id: "user-1",
          amount: 99.0,
          payment_status: "PENDING",
          created_at: "2024-01-01",
        },
      ],
      total: 1,
      page: 1,
      size: 20,
    },
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

import OrdersPage from "./page";

describe("OrdersPage", () => {
  it("渲染订单列表", () => {
    mockHasPermission.mockReturnValue(false);
    render(<OrdersPage />);
    expect(screen.getByText("订单管理")).toBeInTheDocument();
    expect(screen.getByText("ORDER20240101001")).toBeInTheDocument();
    expect(screen.getByText("¥99.00")).toBeInTheDocument();
  });

  it("无 ADMIN_ORDER_EDIT 权限时不显示标记已支付按钮", () => {
    mockHasPermission.mockReturnValue(false);
    render(<OrdersPage />);
    expect(screen.queryByText("标记已支付")).not.toBeInTheDocument();
  });

  it("有 ADMIN_ORDER_EDIT 权限时显示标记已支付按钮", () => {
    mockHasPermission.mockReturnValue(true);
    render(<OrdersPage />);
    expect(screen.getByText("标记已支付")).toBeInTheDocument();
  });
});
