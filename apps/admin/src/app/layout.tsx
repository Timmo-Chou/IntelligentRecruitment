// 管理后台根布局
// 登录页不显示侧边栏，其他页面包裹 AdminShell
import type { Metadata } from "next";
import type { ReactNode } from "react";
import { AdminAuthProvider } from "@/lib/admin-auth";
import { Providers } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "平台管理 - AI智能招聘",
  description: "AI智能招聘平台管理后台",
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>
        <AdminAuthProvider>
          <Providers>{children}</Providers>
        </AdminAuthProvider>
      </body>
    </html>
  );
}