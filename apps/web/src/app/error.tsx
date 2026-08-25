"use client";

import { Button } from "@/components/ui/button";

export default function GlobalError({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <main className="grid min-h-screen place-items-center p-8">
      <section className="max-w-md rounded-xl border border-slate-200 bg-white p-6 text-center shadow-card">
        <h1 className="text-xl font-semibold">页面暂时无法加载</h1>
        <p className="text-sm text-slate-500">请稍后重试。如果问题持续存在，请联系管理员。</p>
        <Button onClick={reset}>重新加载</Button>
      </section>
    </main>
  );
}

