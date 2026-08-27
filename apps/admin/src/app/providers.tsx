"use client";

// 全局 Providers：TanStack Query + 登录/鉴权路由守卫
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useAdminAuth } from "@/lib/admin-auth";
import { AdminShell } from "@/components/layout/admin-shell";

/**
 * 路由守卫：未登录时跳转到 /login
 * 登录页不显示侧边栏，其他页面包裹 AdminShell
 */
function AuthGuard({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAdminAuth();
  const pathname = usePathname();
  const router = useRouter();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!mounted) return;
    // 在登录页，如果已认证则跳转到首页
    if (pathname === "/login" && isAuthenticated) {
      router.replace("/");
      return;
    }
    // 不在登录页，如果未认证则跳转到登录页
    if (pathname !== "/login" && !isAuthenticated) {
      router.replace("/login");
    }
  }, [pathname, isAuthenticated, mounted, router]);

  // 服务端渲染阶段不渲染，避免闪烁
  if (!mounted) return null;

  // 登录页：不显示侧边栏
  if (pathname === "/login") {
    return <>{children}</>;
  }

  // 未认证且不在登录页
  if (!isAuthenticated) {
    return null;
  }

  return <AdminShell>{children}</AdminShell>;
}

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: 1, staleTime: 15_000, refetchOnWindowFocus: false },
          mutations: { retry: false },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      <AuthGuard>{children}</AuthGuard>
    </QueryClientProvider>
  );
}