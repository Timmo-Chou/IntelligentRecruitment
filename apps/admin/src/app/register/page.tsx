"use client";

// 管理员注册申请页：提交后等待超级管理员审核
import { LockKeyhole, Phone, Shield, UserRound } from "lucide-react";
import Link from "next/link";
import { type FormEvent, useState } from "react";
import { adminApiFetch, ApiError } from "@/lib/admin-api-client";

export default function RegisterPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [phone, setPhone] = useState("");
  const [registerReason, setRegisterReason] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!username.trim() || !password || !confirmPassword || !displayName.trim() || !phone.trim() || !registerReason.trim()) {
      setError("请完整填写注册申请");
      return;
    }
    if (password.length < 8) {
      setError("密码至少需要 8 位");
      return;
    }
    if (password !== confirmPassword) {
      setError("两次输入的密码不一致");
      return;
    }
    if (registerReason.trim().length > 500) {
      setError("申请原因不能超过 500 个字符");
      return;
    }

    setLoading(true);
    setError(null);
    try {
      await adminApiFetch<void>("/platform/admins/register", {
        method: "POST",
        body: JSON.stringify({
          username: username.trim(),
          password,
          display_name: displayName.trim(),
          phone: phone.trim(),
          register_reason: registerReason.trim(),
        }),
      });
      setSubmitted(true);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
      else setError("提交失败，请检查网络连接后重试");
    } finally {
      setLoading(false);
    }
  }

  if (submitted) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-blue-50 p-4">
        <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-card">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-emerald-50">
            <Shield className="h-7 w-7 text-emerald-600" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800">申请已提交</h1>
          <p className="mt-3 text-sm leading-6 text-slate-500">管理员申请已提交，请等待超级管理员审核通过后再登录。</p>
          <Link href="/login" className="mt-6 inline-block rounded-lg bg-blue-600 px-6 py-3 text-sm font-semibold text-white hover:bg-blue-700">
            返回登录
          </Link>
        </div>
      </main>
    );
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-blue-50 p-4">
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 shadow-card">
        <div className="mb-7 text-center">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-50">
            <Shield className="h-7 w-7 text-blue-600" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800">申请管理员账号</h1>
          <p className="mt-2 text-sm text-slate-500">提交后由超级管理员审核</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <Field label="用户名" icon={<UserRound className="h-5 w-5 text-slate-400" />}>
            <input value={username} onChange={(event) => setUsername(event.target.value)} type="text" autoComplete="username" placeholder="请输入用户名" className={inputClass} />
          </Field>
          <Field label="密码" icon={<LockKeyhole className="h-5 w-5 text-slate-400" />}>
            <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="new-password" placeholder="至少 8 位" className={inputClass} />
          </Field>
          <Field label="确认密码" icon={<LockKeyhole className="h-5 w-5 text-slate-400" />}>
            <input value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} type="password" autoComplete="new-password" placeholder="请再次输入密码" className={inputClass} />
          </Field>
          <Field label="显示名称" icon={<UserRound className="h-5 w-5 text-slate-400" />}>
            <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} type="text" autoComplete="name" placeholder="请输入显示名称" className={inputClass} />
          </Field>
          <Field label="手机号" icon={<Phone className="h-5 w-5 text-slate-400" />}>
            <input value={phone} onChange={(event) => setPhone(event.target.value)} type="tel" autoComplete="tel" placeholder="请输入手机号" className={inputClass} />
          </Field>
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">申请原因</label>
            <textarea value={registerReason} onChange={(event) => setRegisterReason(event.target.value)} maxLength={500} rows={3} placeholder="请说明申请管理员权限的原因" className="w-full resize-none rounded-lg border border-slate-300 px-4 py-3 text-sm text-slate-800 outline-none placeholder:text-slate-400 focus:border-blue-400 focus:ring-3 focus:ring-blue-50" />
          </div>

          {error && <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}
          <button type="submit" disabled={loading} className="w-full rounded-lg bg-blue-600 py-3 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50">
            {loading ? "提交中…" : "提交申请"}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-slate-500">
          已有管理员账号？ <Link href="/login" className="text-blue-600 hover:text-blue-700">返回登录</Link>
        </p>
      </div>
    </main>
  );
}

const inputClass = "min-w-0 flex-1 border-0 bg-transparent text-sm text-slate-800 outline-none placeholder:text-slate-400";

function Field({ label, icon, children }: { label: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-2 block text-sm font-medium text-slate-700">{label}</label>
      <div className="flex items-center gap-3 rounded-lg border border-slate-300 bg-white px-4 py-3 transition-colors focus-within:border-blue-400 focus-within:ring-3 focus-within:ring-blue-50">
        {icon}
        {children}
      </div>
    </div>
  );
}
