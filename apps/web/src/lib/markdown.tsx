import Link from "next/link";
import type { ReactNode } from "react";

function renderInline(text: string): ReactNode[] {
  const parts = text.split(/(\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\))/g);
  return parts.map((part, index) => {
    if (part.startsWith("**") && part.endsWith("**")) {
      return <strong key={index}>{part.slice(2, -2)}</strong>;
    }

    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link) {
      return <Link key={index} href={link[2]} className="font-medium text-[#176ce5] hover:underline">{link[1]}</Link>;
    }

    return part;
  });
}

export function renderMarkdown(content: string): ReactNode[] {
  const lines = content.split("\n");
  const nodes: ReactNode[] = [];
  let index = 0;
  let key = 0;

  while (index < lines.length) {
    const line = lines[index];

    if (!line.trim()) {
      index += 1;
      continue;
    }

    if (line.startsWith("#### ")) {
      nodes.push(<h4 key={key++} className="mb-2 mt-5 text-base font-semibold text-[#10285b]">{renderInline(line.slice(5))}</h4>);
      index += 1;
      continue;
    }

    if (line.startsWith("### ")) {
      nodes.push(<h3 key={key++} className="mb-2 mt-6 text-lg font-semibold text-[#10285b]">{renderInline(line.slice(4))}</h3>);
      index += 1;
      continue;
    }

    if (line.startsWith("## ")) {
      nodes.push(<h2 key={key++} className="mb-3 mt-8 text-xl font-bold text-[#09245d]">{renderInline(line.slice(3))}</h2>);
      index += 1;
      continue;
    }

    if (line.startsWith("# ")) {
      nodes.push(<h1 key={key++} className="mb-4 text-3xl font-bold text-[#071b4b]">{renderInline(line.slice(2))}</h1>);
      index += 1;
      continue;
    }

    if (/^\*\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^\*\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\*\s+/, ""));
        index += 1;
      }
      nodes.push(
        <ul key={key++} className="my-3 list-disc space-y-2 pl-6 text-sm leading-7 text-[#405b86]">
          {items.map(item => <li key={item}>{renderInline(item)}</li>)}
        </ul>,
      );
      continue;
    }

    const paragraph: string[] = [line];
    index += 1;
    while (index < lines.length && lines[index].trim() && !lines[index].startsWith("#") && !/^\*\s+/.test(lines[index])) {
      paragraph.push(lines[index]);
      index += 1;
    }

    nodes.push(
      <p key={key++} className="my-3 text-sm leading-7 text-[#405b86]">
        {renderInline(paragraph.join(" "))}
      </p>,
    );
  }

  return nodes;
}
