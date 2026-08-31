"use client";

import { Bot, BriefcaseBusiness, Eye, EyeOff, FileCheck2, KeyRound, LockKeyhole, Phone, ScanSearch, ShieldCheck, Workflow } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useEffect, useState } from "react";
import { apiFetch, ApiError, setAccessToken } from "@/lib/api-client";
import { useWorkspace } from "@/lib/workspace-context";

const features = [["智能生成JD", "对话式生成，自助修改", BriefcaseBusiness], ["精准筛选简历", "多重匹配，智能评分", ScanSearch], ["专业面试出题", "个性化出题，智能复用", FileCheck2], ["自动化工作流", "一键生成流程，高效省心", Workflow]] as const;
type LoginMode = "code" | "password";
type AuthResponse = { access_token: string; onboarding_required: boolean; password_setup_required: boolean };

export default function LoginPage() {
  const router = useRouter();
  const { refresh: refreshWorkspaces } = useWorkspace();
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [seconds, setSeconds] = useState(0);
  const [agreed, setAgreed] = useState(false);
  const [mode, setMode] = useState<LoginMode>("code");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [showPasswordSetup, setShowPasswordSetup] = useState(false);
  const [resetMode, setResetMode] = useState(false);
  const [onboardingRequired, setOnboardingRequired] = useState(false);

  useEffect(() => {
    if (seconds <= 0) return;
    const timer = window.setTimeout(() => setSeconds(value => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [seconds]);

  async function continueAfterAuthentication(requiresOnboarding: boolean) {
    // WorkspaceProvider 会在登录页首次挂载时收到 401。认证成功后必须重新加载，
    // 否则跳转后的业务页仍会读取到旧的 notAuthenticated 状态并回到登录页。
    await refreshWorkspaces();
    router.replace(requiresOnboarding ? "/onboarding" : "/recruitment");
  }

  async function sendCode() {
    setLoading(true); setMessage(null);
    try {
      const result = await apiFetch<{ challenge_id: string; mock_code?: string }>("/auth/challenges", {
        method: "POST", body: JSON.stringify({ phone }),
      }, false);
      setChallengeId(result.challenge_id);
      setSeconds(60);
      setMessage(result.mock_code ? `开发验证码：${result.mock_code}` : "验证码已发送");
    } catch (error) { setMessage(error instanceof ApiError ? error.message : "验证码发送失败"); }
    finally { setLoading(false); }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!agreed) { setMessage("请先同意用户服务协议和隐私政策"); return; }
    if (mode === "code" && !challengeId) { setMessage("请先获取验证码"); return; }
    setLoading(true); setMessage(null);
    try {
      const result = resetMode
        ? await apiFetch<AuthResponse>("/auth/password-reset", { method: "POST", body: JSON.stringify({ challenge_id: challengeId, phone, code, newPassword: password }) }, false)
        : mode === "code"
        ? await apiFetch<AuthResponse>("/auth/verify", {
            method: "POST", body: JSON.stringify({ challenge_id: challengeId, phone, code }),
          }, false)
        : await apiFetch<AuthResponse>("/auth/password-login", {
            method: "POST", body: JSON.stringify({ phone, password }),
          }, false);
      setAccessToken(result.access_token);
      if (result.password_setup_required) {
        setOnboardingRequired(result.onboarding_required);
        setShowPasswordSetup(true);
      } else {
        await continueAfterAuthentication(result.onboarding_required);
      }
    } catch (error) { setMessage(error instanceof ApiError ? error.message : "登录失败，请稍后重试"); }
    finally { setLoading(false); }
  }

  function changeMode(next: LoginMode) {
    setMode(next); setResetMode(false); setMessage(null); setPassword(""); setShowPassword(false);
  }

  function startReset() { setMode("password"); setResetMode(true); setMessage("请输入验证码和新密码"); }

  return <main className="login-canvas min-h-screen p-5 text-[#10285b] lg:p-10">
    <div className="mx-auto grid min-h-[calc(100vh-40px)] max-w-[1500px] gap-6 lg:grid-cols-[460px_minmax(0,1fr)]">
      <section className="flex flex-col rounded-[26px] border border-white/80 bg-white/90 p-7 shadow-[0_18px_60px_rgba(39,100,180,0.09)] sm:p-10">
        <div><span className="flex items-center gap-3 text-lg font-bold text-[#09245d]"><span className="brand-mark" aria-hidden="true"><i/><i/></span>AI招聘工作台</span><h1 className="mb-0 mt-10 text-[34px] font-bold tracking-tight text-[#071b4b]">欢迎登录</h1><p className="mt-3 text-sm text-[#607697]">首次手机号验证后将自动注册</p></div>
        <div className="mt-8 flex border-b border-[#dbe5f2]"><button className={mode === "code" && !resetMode ? "login-tab-active" : "login-tab"} onClick={() => changeMode("code")} type="button">验证码登录</button><button className={mode === "password" || resetMode ? "login-tab-active" : "login-tab"} onClick={() => changeMode("password")} type="button">密码登录</button></div>
        <form className="mt-8 space-y-5" onSubmit={submit}>
          <label className="block"><span className="mb-2 block text-sm font-medium">手机号</span><span className="login-input"><Phone size={18}/><input value={phone} onChange={event => setPhone(event.target.value)} type="tel" inputMode="numeric" autoComplete="tel" placeholder="请输入手机号" aria-label="手机号" maxLength={11}/></span></label>
          {mode === "code" || resetMode ? <label className="block"><span className="mb-2 block text-sm font-medium">验证码</span><span className="login-input"><LockKeyhole size={18}/><input value={code} onChange={event => setCode(event.target.value)} type="text" inputMode="numeric" autoComplete="one-time-code" placeholder="请输入验证码" aria-label="验证码" maxLength={6}/><button type="button" onClick={sendCode} disabled={loading || phone.length !== 11 || seconds > 0} className="whitespace-nowrap border-l border-[#dbe5f2] pl-4 text-sm font-semibold text-[#176ce5] disabled:text-[#9aabc0]">{seconds>0?`${seconds}秒后重试`:"获取验证码"}</button></span></label>
          : <label className="block"><span className="mb-2 block text-sm font-medium">密码</span><span className="login-input"><KeyRound size={18}/><input value={password} onChange={event => setPassword(event.target.value)} type={showPassword ? "text" : "password"} autoComplete="current-password" placeholder="请输入登录密码" aria-label="密码" maxLength={64}/><button type="button" onClick={() => setShowPassword(value => !value)} className="text-[#6d82a2]" aria-label={showPassword ? "隐藏密码" : "显示密码"}>{showPassword ? <EyeOff size={18}/> : <Eye size={18}/>}</button></span></label>}
          {message && <p className="rounded-lg bg-[#f2f8ff] px-3 py-2 text-xs text-[#60799f]">{message}</p>}
          {mode === "password" && !resetMode && <button type="button" onClick={startReset} className="text-xs text-[#176ce5] hover:underline">忘记密码？通过验证码重置</button>}
          {resetMode && <label className="block"><span className="mb-2 block text-sm font-medium">新密码</span><input value={password} onChange={event=>setPassword(event.target.value)} type="password" autoComplete="new-password" className="login-input w-full" placeholder="8至64位，包含字母和数字"/></label>}
          <label className="flex items-start gap-2 text-xs leading-5 text-[#60799f]"><input checked={agreed} onChange={event => setAgreed(event.target.checked)} className="mt-0.5" type="checkbox" /><span>我已阅读并同意<Link href="/terms" className="text-[#176ce5] hover:underline" onClick={event => event.stopPropagation()} target="_blank">《用户服务协议》</Link>和<Link href="/privacy" className="text-[#176ce5] hover:underline" onClick={event => event.stopPropagation()} target="_blank">《隐私政策》</Link></span></label>
          <button className="login-submit disabled:opacity-60" disabled={loading || !agreed || phone.length !== 11 || ((mode === "code" || resetMode) && code.length !== 6) || (resetMode ? password.length < 8 : mode === "password" ? password.length < 8 : false)} type="submit">{loading ? "处理中…" : resetMode ? "重置密码并登录" : mode === "code" ? "登录 / 注册" : "登录"}</button>
        </form>
      </section>

      <section className="hidden min-w-0 flex-col justify-center px-8 lg:flex">
        <div className="text-center"><h2 className="m-0 text-[42px] font-bold tracking-tight text-[#09245d]">AI驱动，让招聘<span className="bg-gradient-to-r from-[#1b68f2] via-[#16a8dc] to-[#13b981] bg-clip-text text-transparent">更高效</span></h2><p className="mt-3 text-lg text-[#405b86]">智能生成JD　·　精准筛选简历　/　专业面试出题　·　自动化招聘流程</p></div>
        <div className="relative mx-auto my-8 flex h-[330px] w-full max-w-[760px] items-center justify-center"><div className="login-orbit"/><div className="login-screen"><span className="flex h-8 items-center rounded-t-xl bg-gradient-to-r from-[#2568ee] to-[#168fe8] px-4 text-xs font-semibold text-white">AI招聘工作台</span><div className="grid flex-1 grid-cols-[1fr_120px] gap-3 p-4"><div className="space-y-3">{[1,2,3,4].map(item => <span key={item} className="block h-9 rounded-lg bg-[#edf4ff]"/>)}</div><span className="rounded-xl bg-gradient-to-b from-[#d8f8ef] to-[#dcecff]"/></div></div><div className="login-robot"><Bot size={58}/><strong>AI</strong></div><div className="absolute bottom-4 left-[12%] h-24 w-28 rounded-xl border border-[#caddfa] bg-white/80 p-3"><span className="block h-14 bg-[linear-gradient(90deg,#2f6bff_20%,transparent_20%_28%,#13b981_28%_55%,transparent_55%)] opacity-70"/></div></div>
        <div className="grid grid-cols-4 divide-x divide-[#e2eaf5] rounded-[24px] border border-white bg-white/80 p-5 shadow-[0_14px_50px_rgba(39,100,180,0.07)]">{features.map(([title,desc,Icon]) => <article key={title} className="px-4 text-center"><span className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-[#edf5ff] text-[#296fee]"><Icon size={23}/></span><h3 className="mb-0 mt-3 text-sm font-bold text-[#102d64]">{title}</h3><p className="mb-0 mt-1 text-xs text-[#7187a8]">{desc}</p></article>)}</div>
      </section>
    </div>
    {showPasswordSetup && <PasswordSetupDialog
      onboardingRequired={onboardingRequired}
      onComplete={() => continueAfterAuthentication(onboardingRequired)}
    />}
  </main>;
}

function PasswordSetupDialog({ onboardingRequired, onComplete }: { onboardingRequired: boolean; onComplete: () => Promise<void>}) {
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const valid = password.length >= 8 && password.length <= 64 && /[A-Za-z]/.test(password) && /\d/.test(password);

  async function submit(event: FormEvent) {
    event.preventDefault(); setMessage(null);
    if (!valid) { setMessage("密码须为8至64位，并同时包含字母和数字"); return; }
    if (password !== confirmPassword) { setMessage("两次输入的密码不一致"); return; }
    setLoading(true);
    try {
      await apiFetch<void>("/auth/password", { method: "POST", body: JSON.stringify({ password }) });
      await onComplete();
    } catch (error) { setMessage(error instanceof ApiError ? error.message : "密码设置失败，请稍后重试"); }
    finally { setLoading(false); }
  }

  return <div className="fixed inset-0 z-50 grid place-items-center bg-[#071b4b]/45 p-5 backdrop-blur-sm" role="presentation">
    <section className="w-full max-w-md rounded-[24px] border border-white bg-white p-7 shadow-[0_24px_80px_rgba(7,27,75,.25)]" role="dialog" aria-modal="true" aria-labelledby="password-setup-title">
      <span className="grid h-12 w-12 place-items-center rounded-2xl bg-[#eaf3ff] text-[#176ce5]"><ShieldCheck size={25}/></span>
      <h2 id="password-setup-title" className="mb-0 mt-5 text-2xl font-bold text-[#09245d]">设置登录密码</h2>
      <p className="mt-2 text-sm leading-6 text-[#60799f]">首次验证码登录需要设置密码。完成后将进入{onboardingRequired ? "个人或企业使用方式选择" : "工作台"}。</p>
      <form className="mt-6 space-y-4" onSubmit={submit}>
        <label className="block"><span className="mb-2 block text-sm font-medium">登录密码</span><span className="login-input"><KeyRound size={18}/><input autoFocus value={password} onChange={event => setPassword(event.target.value)} type={showPassword ? "text" : "password"} autoComplete="new-password" placeholder="8至64位，包含字母和数字" aria-label="设置登录密码" maxLength={64}/><button type="button" onClick={() => setShowPassword(value => !value)} className="text-[#6d82a2]" aria-label={showPassword ? "隐藏密码" : "显示密码"}>{showPassword ? <EyeOff size={18}/> : <Eye size={18}/>}</button></span></label>
        <label className="block"><span className="mb-2 block text-sm font-medium">确认密码</span><span className="login-input"><LockKeyhole size={18}/><input value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} type={showPassword ? "text" : "password"} autoComplete="new-password" placeholder="请再次输入密码" aria-label="确认登录密码" maxLength={64}/></span></label>
        <div className="rounded-xl bg-[#f4f8fd] px-4 py-3 text-xs leading-5 text-[#61799c]">密码要求：8至64位，同时包含英文字母和数字。建议加入大小写字母及特殊字符。</div>
        {message && <p className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-700">{message}</p>}
        <button className="login-submit disabled:opacity-60" disabled={loading || !valid || password !== confirmPassword} type="submit">{loading ? "正在设置…" : "完成设置并继续"}</button>
      </form>
    </section>
  </div>;
}
