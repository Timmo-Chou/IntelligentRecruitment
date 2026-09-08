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

    if (line.startsWith("> ")) {
      const quote: string[] = [];
      while (index < lines.length && lines[index].startsWith("> ")) {
        quote.push(lines[index].slice(2));
        index += 1;
      }
      nodes.push(
        <blockquote key={key++} className="my-5 border-l-4 border-[#75a9f8] bg-[#f3f8ff] px-4 py-3 text-sm leading-7 text-[#31537f]">
          {renderInline(quote.join(" "))}
        </blockquote>,
      );
      continue;
    }

    if (line.startsWith("```")) {
      const language = line.slice(3).trim();
      index += 1;
      const code: string[] = [];
      while (index < lines.length && !lines[index].startsWith("```")) {
        code.push(lines[index]);
        index += 1;
      }
      if (index < lines.length) index += 1;
      nodes.push(
        <div key={key++} className="my-5 overflow-x-auto rounded-xl bg-[#10285b] p-4 shadow-inner">
          {language && <div className="mb-2 text-xs font-medium uppercase tracking-wider text-blue-200">{language}</div>}
          <pre className="text-xs leading-6 text-[#e6f0ff] sm:text-sm"><code>{code.join("\n")}</code></pre>
        </div>,
      );
      continue;
    }

    if (/^---+$/.test(line)) {
      nodes.push(<hr key={key++} className="my-8 border-0 border-t border-[#dce7f7]" />);
      index += 1;
      continue;
    }

    if (line.startsWith("|") && index + 1 < lines.length && /^\|?\s*:?-{3,}/.test(lines[index + 1])) {
      const cells = (value: string) => value.trim().replace(/^\||\|$/g, "").split("|").map(item => item.trim());
      const headers = cells(line);
      index += 2;
      const rows: string[][] = [];
      while (index < lines.length && lines[index].startsWith("|")) {
        rows.push(cells(lines[index]));
        index += 1;
      }
      nodes.push(
        <div key={key++} className="my-5 overflow-x-auto rounded-xl border border-[#dce7f7]">
          <table className="min-w-full text-left text-sm">
            <thead className="bg-[#edf5ff] text-[#10285b]">
              <tr>{headers.map((header, column) => <th key={column} className="whitespace-nowrap px-4 py-3 font-semibold">{renderInline(header)}</th>)}</tr>
            </thead>
            <tbody className="divide-y divide-[#e5edf8] text-[#405b86]">
              {rows.map((row, rowIndex) => <tr key={rowIndex}>{headers.map((_, column) => <td key={column} className="px-4 py-3 align-top leading-6">{renderInline(row[column] ?? "")}</td>)}</tr>)}
            </tbody>
          </table>
        </div>,
      );
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

    if (/^\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^\d+\.\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\d+\.\s+/, ""));
        index += 1;
      }
      nodes.push(
        <ol key={key++} className="my-3 list-decimal space-y-2 pl-6 text-sm leading-7 text-[#405b86]">
          {items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}
        </ol>,
      );
      continue;
    }

    const paragraph: string[] = [line];
    index += 1;
    while (index < lines.length && lines[index].trim() && !lines[index].startsWith("#")
      && !lines[index].startsWith("> ") && !lines[index].startsWith("```") && !lines[index].startsWith("|")
      && !/^\*\s+/.test(lines[index]) && !/^\d+\.\s+/.test(lines[index]) && !/^---+$/.test(lines[index])) {
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
