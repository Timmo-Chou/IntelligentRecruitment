"use client";

// 管理后台登录页：只需输入管理密钥
import { KeyRound, Shield } from "lucide-react";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { useAdminAuth } from "@/lib/admin-auth";
import { adminApiFetch, ApiError } from "@/lib/admin-api-client";

export default function LoginPage() {
  const router = useRouter();
  const { login } = useAdminAuth();
  const [adminKey, setAdminKey] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!adminKey.trim()) {
      setError("请输入管理密钥");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      // 尝试用管理密钥调用一个接口来验证
      await adminApiFetch("/platform/me", {
        headers: { "X-Platform-Admin-Key": adminKey.trim() },
      });
      // 验证通过，保存密钥
      login(adminKey.trim());
      router.replace("/");
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.status === 401 ? "管理密钥无效，请检查后重试" : err.message);
      } else {
        setError("验证失败，请检查网络连接后重试");
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
              管理密钥
            </label>
            <div className="flex items-center gap-3 rounded-lg border border-slate-300 bg-white px-4 py-3 transition-colors focus-within:border-blue-400 focus-within:ring-3 focus-within:ring-blue-50">
              <KeyRound className="h-5 w-5 text-slate-400" />
              <input
                value={adminKey}
                onChange={(e) => setAdminKey(e.target.value)}
                type="password"
                autoComplete="off"
                placeholder="请输入 X-Platform-Admin-Key"
                className="min-w-0 flex-1 border-0 bg-transparent text-sm text-slate-800 outline-none placeholder:text-slate-400"
                autoFocus
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
            disabled={loading || !adminKey.trim()}
            className="w-full rounded-lg bg-blue-600 py-3 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? "验证中…" : "登录管理后台"}
          </button>
        </form>

        {/* 底部提示 */}
        <p className="mt-6 text-center text-xs text-slate-400">
          请使用系统管理员分配的管理密钥登录
        </p>
      </div>
    </main>
  );
}