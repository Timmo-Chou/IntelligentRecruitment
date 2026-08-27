"use client";

// 管理后台认证上下文
// 管理员的认证密钥存储在 localStorage 中，通过 X-Platform-Admin-Key 请求头传递

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

type AdminAuthContextValue = {
  /** 管理员密钥 */
  adminKey: string | null;
  /** 是否已认证 */
  isAuthenticated: boolean;
  /** 登录：保存密钥并标记已认证 */
  login: (key: string) => void;
  /** 登出：清除密钥 */
  logout: () => void;
};

const AdminAuthContext = createContext<AdminAuthContextValue>({
  adminKey: null,
  isAuthenticated: false,
  login: () => {},
  logout: () => {},
});

/** 在组件中使用管理员认证状态 */
export function useAdminAuth() {
  return useContext(AdminAuthContext);
}

const STORAGE_KEY = "admin-key";

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [adminKey, setAdminKey] = useState<string | null>(null);

  // 组件挂载时，检查 localStorage 中是否有已保存的密钥
  useEffect(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) {
      setAdminKey(saved);
    }
  }, []);

  const login = useCallback((key: string) => {
    localStorage.setItem(STORAGE_KEY, key);
    setAdminKey(key);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    setAdminKey(null);
  }, []);

  return (
    <AdminAuthContext.Provider
      value={{
        adminKey,
        isAuthenticated: adminKey !== null,
        login,
        logout,
      }}
    >
      {children}
    </AdminAuthContext.Provider>
  );
}