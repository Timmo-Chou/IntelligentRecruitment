"use client";

// 管理后台登录页：用户名密码登录
import { LockKeyhole, Shield, UserRound } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { type AdminInfo, useAdminAuth } from "@/lib/admin-auth";
import { adminApiFetch, ApiError } from "@/lib/admin-api-client";

type LoginResponse = {
  access_token: string;
  admin_id: string;
  display_name: string;
  role: AdminInfo["role"];
  permissions: string[];
};

export default function LoginPage() {
  const router = useRouter();
  const { login } = useAdminAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!username.trim() || !password) {
      setError("请输入用户名和密码");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await adminApiFetch<LoginResponse>("/platform/admins/login", {
        method: "POST",
        body: JSON.stringify({ username: username.trim(), password }),
      });
      login(result.access_token, {
        adminId: result.admin_id,
        displayName: result.display_name,
        role: result.role,
        permissions: result.permissions ?? [],
      });
      router.replace("/");
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError("登录失败，请检查网络连接后重试");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-blue-50 p-4">
      {/* 登录卡片 */}
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 shadow-card">
        {/* 头部 */}
        <div className="mb-8 text-center">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-50">
            <Shield className="h-7 w-7 text-blue-600" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800">平台管理</h1>
          <p className="mt-2 text-sm text-slate-500">AI智能招聘管理后台</p>
        </div>

        {/* 表单 */}
        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">
              用户名
            </label>
            <div className="flex items-center gap-3 rounded-lg border border-slate-300 bg-white px-4 py-3 transition-colors focus-within:border-blue-400 focus-within:ring-3 focus-within:ring-blue-50">
              <UserRound className="h-5 w-5 text-slate-400" />
              <input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                type="text"
                autoComplete="username"
                placeholder="请输入用户名"
                className="min-w-0 flex-1 border-0 bg-transparent text-sm text-slate-800 outline-none placeholder:text-slate-400"
                autoFocus
              />
            </div>
          </div>

          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">
              密码
            </label>
            <div className="flex items-center gap-3 rounded-lg border border-slate-300 bg-white px-4 py-3 transition-colors focus-within:border-blue-400 focus-within:ring-3 focus-within:ring-blue-50">
              <LockKeyhole className="h-5 w-5 text-slate-400" />
              <input
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                type="password"
                autoComplete="current-password"
                placeholder="请输入密码"
                className="min-w-0 flex-1 border-0 bg-transparent text-sm text-slate-800 outline-none placeholder:text-slate-400"
              />
            </div>
          </div>

          {/* 错误信息 */}
          {error && (
            <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
              {error}
            </div>
          )}

          {/* 提交按钮 */}
          <button
            type="submit"
            disabled={loading || !username.trim() || !password}
            className="w-full rounded-lg bg-blue-600 py-3 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? "登录中…" : "登录管理后台"}
          </button>
        </form>

        {/* 底部提示 */}
        <p className="mt-6 text-center text-xs text-slate-400">
          请使用管理员用户名和密码登录
        </p>
        <div className="mt-4 flex justify-center gap-4 text-sm">
          <Link href="/bootstrap" className="text-blue-600 hover:text-blue-700">
            首次部署引导
          </Link>
          <Link href="/register" className="text-slate-500 hover:text-slate-700">
            申请管理员
          </Link>
        </div>
      </div>
    </main>
  );
}
