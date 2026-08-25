import Link from "next/link";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { renderMarkdown } from "@/lib/markdown";

type LegalDocumentPageProps = {
  fileName: "privacy-policy" | "user-agreement";
  backLabel?: string;
};

export function LegalDocumentPage({ fileName, backLabel = "返回登录" }: LegalDocumentPageProps) {
  const content = readFileSync(join(process.cwd(), "src/content/legal", `${fileName}.md`), "utf8");

  return (
    <main className="login-canvas min-h-screen p-5 text-[#10285b] lg:p-10">
      <article className="mx-auto max-w-4xl rounded-[26px] border border-white/80 bg-white/95 p-7 shadow-[0_18px_60px_rgba(39,100,180,0.09)] sm:p-10">
        <Link href="/login" className="text-sm font-medium text-[#176ce5] hover:underline">
          ← {backLabel}
        </Link>
        <div className="mt-6">{renderMarkdown(content)}</div>
      </article>
    </main>
  );
}
