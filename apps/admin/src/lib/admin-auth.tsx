"use client";

// 管理后台认证上下文
// 管理员通过用户名密码登录获取 access_token，存储在 localStorage 中

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { getAccessToken, setAccessToken } from "./admin-api-client";

export type AdminInfo = {
  adminId: string;
  displayName: string;
  role: "SUPER_ADMIN" | "ADMIN" | "OPERATOR";
  permissions: string[];
};

type AdminAuthContextValue = {
  /** 管理员信息 */
  admin: AdminInfo | null;
  /** 是否已认证 */
  isAuthenticated: boolean;
  /** 登录：保存 token 和管理员信息 */
  login: (token: string, admin: AdminInfo) => void;
  /** 登出：清除 token */
  logout: () => void;
};

const AdminAuthContext = createContext<AdminAuthContextValue>({
  admin: null,
  isAuthenticated: false,
  login: () => {},
  logout: () => {},
});

export function useAdminAuth() {
  return useContext(AdminAuthContext);
}

const ADMIN_INFO_KEY = "admin-info";

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [admin, setAdmin] = useState<AdminInfo | null>(null);

  useEffect(() => {
    const token = getAccessToken();
    const saved = localStorage.getItem(ADMIN_INFO_KEY);
    if (token && saved) {
      try {
        // 认证信息必须在客户端恢复，避免 SSR 阶段读取 localStorage 造成 hydration 不一致。
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setAdmin(JSON.parse(saved));
      } catch {
        setAccessToken(null);
      }
    }
  }, []);

  const login = useCallback((token: string, adminInfo: AdminInfo) => {
    setAccessToken(token);
    localStorage.setItem(ADMIN_INFO_KEY, JSON.stringify(adminInfo));
    setAdmin(adminInfo);
  }, []);

  const logout = useCallback(() => {
    setAccessToken(null);
    localStorage.removeItem(ADMIN_INFO_KEY);
    setAdmin(null);
  }, []);

  return (
    <AdminAuthContext.Provider
      value={{
        admin,
        isAuthenticated: admin !== null,
        login,
        logout,
      }}
    >
      {children}
    </AdminAuthContext.Provider>
  );
}
