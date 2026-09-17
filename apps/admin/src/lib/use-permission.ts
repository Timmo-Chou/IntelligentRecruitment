"use client";

// 权限判断 Hook：检查当前管理员是否拥有指定权限码
import { useAdminAuth } from "./admin-auth";

/**
 * 检查当前管理员是否拥有指定权限码。
 * SUPER_ADMIN 角色默认拥有全部权限。
 */
export function usePermission() {
  const { admin } = useAdminAuth();

  const hasPermission = (code: string) => {
    if (!admin) return false;
    if (admin.role === "SUPER_ADMIN") return true;
    return admin.permissions?.includes(code) ?? false;
  };

  return { hasPermission, isSuperAdmin: admin?.role === "SUPER_ADMIN" };
}
