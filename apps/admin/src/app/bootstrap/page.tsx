"use client";

// 首个超级管理员引导页：仅使用引导密钥创建首个管理员
import { KeyRound, LockKeyhole, Phone, Shield, UserRound } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { type AdminInfo, useAdminAuth } from "@/lib/admin-auth";
import { adminApiFetch, ApiError } from "@/lib/admin-api-client";

type BootstrapResponse = {
  access_token: string;
  admin_id: string;
  display_name: string;
  role: AdminInfo["role"];
  permissions: string[];
};

export default function BootstrapPage() {
  const router = useRouter();
  const { login } = useAdminAuth();
  const [bootstrapKey, setBootstrapKey] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [phone, setPhone] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!bootstrapKey.trim() || !username.trim() || !password || !confirmPassword || !displayName.trim() || !phone.trim()) {
      setError("请完整填写引导信息");
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

    setLoading(true);
    setError(null);
    const key = bootstrapKey.trim();
    try {
      const result = await adminApiFetch<BootstrapResponse>("/platform/admins/bootstrap", {
        method: "POST",
        headers: { "X-Platform-Admin-Key": key },
        body: JSON.stringify({
          username: username.trim(),
          password,
          display_name: displayName.trim(),
          phone: phone.trim(),
        }),
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
        if (err.body.code === "BOOTSTRAP_NOT_ALLOWED") {
          setError("系统已经完成初始化，请直接使用管理员账号登录");
        } else if (err.body.code === "PLATFORM_ADMIN_REQUIRED") {
          setError("引导密钥无效，请检查后重试");
        } else {
          setError(err.message);
        }
      } else {
        setError("引导失败，请检查网络连接后重试");
      }
    } finally {
      setBootstrapKey("");
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-blue-50 p-4">
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 shadow-card">
        <div className="mb-7 text-center">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-50">
            <Shield className="h-7 w-7 text-blue-600" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800">首个管理员引导</h1>
          <p className="mt-2 text-sm text-slate-500">仅用于创建首个超级管理员账号</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <Field label="引导密钥" icon={<KeyRound className="h-5 w-5 text-slate-400" />}>
            <input value={bootstrapKey} onChange={(event) => setBootstrapKey(event.target.value)} type="password" autoComplete="off" placeholder="请输入引导密钥" className={inputClass} />
          </Field>
          <Field label="用户名" icon={<UserRound className="h-5 w-5 text-slate-400" />}>
            <input value={username} onChange={(event) => setUsername(event.target.value)} type="text" autoComplete="username" placeholder="请输入管理员用户名" className={inputClass} />
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

          {error && <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}
          <button type="submit" disabled={loading} className="w-full rounded-lg bg-blue-600 py-3 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50">
            {loading ? "创建中…" : "创建超级管理员"}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-slate-500">
          已有管理员？ <Link href="/login" className="text-blue-600 hover:text-blue-700">返回登录</Link>
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
