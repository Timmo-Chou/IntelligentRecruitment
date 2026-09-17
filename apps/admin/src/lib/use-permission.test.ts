// usePermission hook 单元测试
import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { usePermission } from "./use-permission";

// Mock admin-auth 模块
const mockAdmin = vi.hoisted(() => ({
  admin: null as null | { role: string; permissions?: string[] },
}));

vi.mock("@/lib/admin-auth", () => ({
  useAdminAuth: () => ({ admin: mockAdmin.admin }),
}));

describe("usePermission", () => {
  it("未登录时所有权限返回 false", () => {
    mockAdmin.admin = null;
    const { result } = renderHook(() => usePermission());
    expect(result.current.hasPermission("ADMIN_TENANT_EDIT")).toBe(false);
    expect(result.current.isSuperAdmin).toBe(false);
  });

  it("SUPER_ADMIN 拥有全部权限", () => {
    mockAdmin.admin = { role: "SUPER_ADMIN", permissions: [] };
    const { result } = renderHook(() => usePermission());
    expect(result.current.hasPermission("ADMIN_TENANT_EDIT")).toBe(true);
    expect(result.current.hasPermission("ANY_PERMISSION")).toBe(true);
    expect(result.current.isSuperAdmin).toBe(true);
  });

  it("OPERATOR 仅拥有分配的权限", () => {
    mockAdmin.admin = {
      role: "OPERATOR",
      permissions: ["ADMIN_USER_VIEW", "ADMIN_USER_EDIT"],
    };
    const { result } = renderHook(() => usePermission());
    expect(result.current.hasPermission("ADMIN_USER_VIEW")).toBe(true);
    expect(result.current.hasPermission("ADMIN_USER_EDIT")).toBe(true);
    expect(result.current.hasPermission("ADMIN_TENANT_EDIT")).toBe(false);
    expect(result.current.isSuperAdmin).toBe(false);
  });

  it("权限列表为空时无任何权限", () => {
    mockAdmin.admin = { role: "ADMIN", permissions: [] };
    const { result } = renderHook(() => usePermission());
    expect(result.current.hasPermission("ADMIN_TENANT_EDIT")).toBe(false);
  });
});
