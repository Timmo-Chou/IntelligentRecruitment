import Link from "next/link";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { renderMarkdown } from "@/lib/markdown";

export function BossApiManualPage() {
  const content = readFileSync(join(process.cwd(), "src/content/open-platform/boss-api-integration.md"), "utf8");

  return (
    <main className="min-h-screen bg-[#f5f8fc] px-4 py-8 text-[#10285b] sm:px-6 lg:px-10 lg:py-12">
      <article className="mx-auto max-w-5xl overflow-hidden rounded-3xl border border-[#dce7f7] bg-white shadow-[0_18px_60px_rgba(39,100,180,0.09)]">
        <header className="border-b border-[#dce7f7] bg-[linear-gradient(135deg,#08285f,#176ce5)] px-6 py-8 text-white sm:px-10">
          <p className="text-sm font-medium tracking-[0.18em] text-blue-100">BOSS OPEN PLATFORM</p>
          <h1 className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">外部系统 API 接入手册</h1>
          <p className="mt-3 max-w-3xl text-sm leading-6 text-blue-100">从凭证配置到事件回调的完整服务端接入流程，以当前 BOSS 对接实现为准。</p>
        </header>
        <div className="px-6 py-8 sm:px-10 sm:py-10">
          <Link href="/" className="text-sm font-medium text-[#176ce5] hover:underline">← 返回 AI 智能招聘</Link>
          <div className="mt-6">{renderMarkdown(content)}</div>
        </div>
      </article>
    </main>
  );
}
