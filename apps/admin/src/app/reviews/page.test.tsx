// 审核中心页面组件测试：验证权限控制和列表渲染
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";

type LinkProps = { children: ReactNode; href: string };

// Mock 依赖
vi.mock("next/link", () => ({ default: ({ children, href }: LinkProps) => <a href={href}>{children}</a> }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
  useParams: () => ({}),
}));

vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({
    data: [
      {
        registration_id: "reg-1",
        legal_name: "测试企业A",
        credit_code: "91110000MA01ABCD12",
        contact_name: "张三",
        status: "PENDING_REVIEW",
        rejection_reason: null,
        created_at: "2024-01-01",
        reviewed_at: null,
        tenant_id: null,
      },
    ],
    isLoading: false,
  }),
  useMutation: () => ({ mutate: vi.fn(), isPending: false }),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));

vi.mock("@/lib/admin-api-client", () => ({
  adminApiFetch: vi.fn(),
}));

const mockHasPermission = vi.hoisted(() => vi.fn(() => false));
vi.mock("@/lib/use-permission", () => ({
  usePermission: () => ({ hasPermission: mockHasPermission }),
}));

import ReviewsPage from "./page";

describe("ReviewsPage", () => {
  it("渲染审核列表", () => {
    mockHasPermission.mockReturnValue(false);
    render(<ReviewsPage />);
    expect(screen.getByText("审核中心")).toBeInTheDocument();
    expect(screen.getByText("测试企业A")).toBeInTheDocument();
    expect(screen.getByText("91110000MA01ABCD12")).toBeInTheDocument();
    expect(screen.getByText("张三")).toBeInTheDocument();
  });

  it("无 ADMIN_REVIEW_EDIT 权限时不显示通过/拒绝按钮", () => {
    mockHasPermission.mockReturnValue(false);
    render(<ReviewsPage />);
    expect(screen.queryByText("通过")).not.toBeInTheDocument();
    expect(screen.queryByText("拒绝")).not.toBeInTheDocument();
  });

  it("有 ADMIN_REVIEW_EDIT 权限时显示通过/拒绝按钮", () => {
    mockHasPermission.mockReturnValue(true);
    render(<ReviewsPage />);
    expect(screen.getByText("通过")).toBeInTheDocument();
    expect(screen.getByText("拒绝")).toBeInTheDocument();
  });
});
