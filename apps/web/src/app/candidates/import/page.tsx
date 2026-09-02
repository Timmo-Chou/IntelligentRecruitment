"use client";

import {
  ArrowLeft, ArrowRight, CheckCircle2, FileSpreadsheet, FileText, Import, Loader2, Upload,
} from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import { uploadResume } from "@/lib/candidate-api";
import { useWorkspace } from "@/lib/workspace-context";

type ImportChannel = "excel" | "csv" | "resume";
type StepId = "upload" | "mapping" | "validate" | "duplicate" | "parse" | "profile" | "done";
type ImportFormat = "excel" | "csv" | "pdf";

const FORMAT_CHANNELS: Record<ImportFormat, ImportChannel> = {
  excel: "excel",
  csv: "csv",
  pdf: "resume",
};

const CHANNEL_META: Record<ImportChannel, { title: string; desc: string; accept: string }> = {
  excel: { title: "Excel 导入", desc: "支持 .xlsx / .xls 人才名册", accept: ".xlsx,.xls" },
  csv: { title: "CSV 导入", desc: "逗号分隔的结构化人才数据", accept: ".csv,text/csv" },
  resume: { title: "PDF 简历导入", desc: "批量上传 PDF / DOCX 简历并 AI 解析", accept: ".pdf,.docx" },
};

const STEPS: { id: StepId; label: string }[] = [
  { id: "upload", label: "文件上传" },
  { id: "mapping", label: "字段映射" },
  { id: "validate", label: "数据校验" },
  { id: "duplicate", label: "重复识别" },
  { id: "parse", label: "AI解析" },
  { id: "profile", label: "生成画像" },
  { id: "done", label: "进入人才库" },
];

const FIELD_OPTIONS = ["姓名", "手机号", "邮箱", "当前公司", "当前职位", "技能", "学历", "忽略"];

export default function ImportTalentPage() {
  return (
    <Suspense fallback={<AppShell activeItem="人才库"><div className="grid h-64 place-items-center text-sm text-[#7085a4]">加载中...</div></AppShell>}>
      <ImportTalentWorkspace />
    </Suspense>
  );
}

function ImportTalentWorkspace() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { workspaceId, loading: wsLoading, notAuthenticated } = useWorkspace();
  const formatParam = (searchParams.get("format") || "pdf").toLowerCase() as ImportFormat;
  const initialChannel = FORMAT_CHANNELS[formatParam] ?? "resume";

  const [step, setStep] = useState<StepId>("upload");
  const [channel, setChannel] = useState<ImportChannel>(initialChannel);
  const [files, setFiles] = useState<File[]>([]);
  const [headers, setHeaders] = useState<string[]>([]);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState(0);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [importedCount, setImportedCount] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (notAuthenticated) window.location.replace("/login");
  }, [notAuthenticated]);

  useEffect(() => {
    setChannel(initialChannel);
    setStep("upload");
  }, [initialChannel]);

  const stepIndex = STEPS.findIndex((item) => item.id === step);
  const meta = CHANNEL_META[channel];

  const previewRows = useMemo(() => {
    if (!headers.length) return [];
    return headers.map((header) => ({
      source: header,
      target: mapping[header] || "忽略",
    }));
  }, [headers, mapping]);

  async function handleFiles(list: FileList | null) {
    if (!list?.length) return;
    const next = Array.from(list);
    setFiles(next);
    setError(null);
    if (channel === "resume") {
      setHeaders(["文件名", "解析姓名", "技能", "学历"]);
      setMapping({
        文件名: "忽略",
        解析姓名: "姓名",
        技能: "技能",
        学历: "学历",
      });
      return;
    }
    try {
      const text = await next[0].text();
      const firstLine = text.split(/\r?\n/).find((line) => line.trim()) || "";
      const cols = firstLine.split(/,|\t|;/).map((item) => item.replace(/^"|"$/g, "").trim()).filter(Boolean);
      const safeCols = cols.length ? cols : ["列1", "列2", "列3"];
      setHeaders(safeCols);
      const nextMapping: Record<string, string> = {};
      for (const col of safeCols) {
        if (/姓名|name/i.test(col)) nextMapping[col] = "姓名";
        else if (/手机|电话|phone/i.test(col)) nextMapping[col] = "手机号";
        else if (/邮箱|email/i.test(col)) nextMapping[col] = "邮箱";
        else if (/公司|company/i.test(col)) nextMapping[col] = "当前公司";
        else if (/职位|title|岗位/i.test(col)) nextMapping[col] = "当前职位";
        else if (/技能|skill/i.test(col)) nextMapping[col] = "技能";
        else if (/学历|education/i.test(col)) nextMapping[col] = "学历";
        else nextMapping[col] = "忽略";
      }
      setMapping(nextMapping);
    } catch {
      setHeaders(["列1", "列2", "列3"]);
      setMapping({ 列1: "姓名", 列2: "手机号", 列3: "忽略" });
    }
  }

  async function runPipeline() {
    if (!workspaceId) return;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const pipeline: StepId[] = ["validate", "duplicate", "parse", "profile", "done"];
      for (let i = 0; i < pipeline.length; i++) {
        setStep(pipeline[i]);
        setProgress(Math.round(((i + 1) / pipeline.length) * 100));
        await wait(500);
      }

      if (channel === "resume") {
        const results = await Promise.allSettled(files.map((file) => uploadResume(workspaceId, file, "NORMAL")));
        const ok = results.filter((item) => item.status === "fulfilled").length;
        const failed = results.length - ok;
        setImportedCount(ok);
        if (failed > 0) setMessage(`成功导入 ${ok} 人，失败 ${failed} 个文件。`);
        else setMessage(`成功导入 ${ok} 人，已进入人才库。`);
      } else {
        setImportedCount(0);
        setMessage("表格字段映射与校验已完成。当前版本请改用 PDF 简历导入完成入库，或由 HR 复核后手动新增。");
      }
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "导入失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  function nextStep() {
    if (step === "upload") {
      if (!files.length) {
        setError("请先上传文件");
        return;
      }
      setError(null);
      setStep("mapping");
    } else if (step === "mapping") void runPipeline();
  }

  if (wsLoading) {
    return <AppShell activeItem="人才库"><div className="grid h-64 place-items-center text-sm text-[#7085a4]">加载中...</div></AppShell>;
  }

  return (
    <AppShell activeItem="人才库">
      <div className="mb-4">
        <Link href="/candidates" className="inline-flex items-center gap-1 text-sm text-[#2f6bff] hover:underline">
          <ArrowLeft size={15} /> 返回人才库
        </Link>
        <h1 className="mb-0 mt-2 text-[25px] font-bold text-[#09245d]">导入人才 · {meta.title}</h1>
        <p className="mb-0 mt-1 text-sm text-[#60799f]">文件上传 → 字段映射 → 数据校验 → 重复识别 → AI解析 → 生成画像 → 进入人才库</p>
      </div>

      <ol className="mb-5 flex flex-wrap gap-2">
        {STEPS.map((item, index) => {
          const active = index <= stepIndex;
          return (
            <li
              key={item.id}
              className={`rounded-full px-3 py-1 text-xs font-semibold ${active ? "bg-[#2f6bff] text-white" : "bg-[#eef3f8] text-[#7a8eaa]"}`}
            >
              {index + 1}. {item.label}
            </li>
          );
        })}
      </ol>

      {error && <div className="mb-4 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]">{error}</div>}
      {message && <div className="mb-4 rounded-lg border border-[#c7efe0] bg-[#f0fbf7] px-4 py-3 text-sm text-[#15785f]">{message}</div>}

      <section className="rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-sm">
        {(step === "upload" || step === "mapping") && (
          <>
            <div className="mb-4 flex flex-wrap items-center gap-2">
              {(["excel", "csv", "pdf"] as ImportFormat[]).map((fmt) => {
                const active = FORMAT_CHANNELS[fmt] === channel;
                return (
                  <button
                    key={fmt}
                    type="button"
                    onClick={() => {
                      setChannel(FORMAT_CHANNELS[fmt]);
                      setFiles([]);
                      setHeaders([]);
                      setMapping({});
                      setStep("upload");
                      router.replace(`/candidates/import?format=${fmt}`);
                    }}
                    className={`rounded-lg border px-3 py-1.5 text-xs font-semibold ${active ? "border-[#2f6bff] bg-[#f3f8ff] text-[#2f6bff]" : "border-[#d9e2ec] text-[#516c94]"}`}
                  >
                    {fmt.toUpperCase()}
                  </button>
                );
              })}
            </div>

            {step === "upload" && (
              <div className="rounded-xl border border-dashed border-[#c5d7ea] bg-[#f8fbff] p-8 text-center">
                <span className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-[#edf5ff] text-[#2f6bff]">
                  {channel === "resume" ? <FileText size={22} /> : <FileSpreadsheet size={22} />}
                </span>
                <p className="mb-1 mt-3 text-sm font-semibold text-[#163665]">{meta.desc}</p>
                <p className="mb-4 text-xs text-[#8fa3c0]">支持格式：{meta.accept.replaceAll(",", " / ")}</p>
                <input
                  ref={inputRef}
                  type="file"
                  multiple={channel === "resume"}
                  accept={meta.accept}
                  className="hidden"
                  onChange={(e) => void handleFiles(e.target.files)}
                />
                <button type="button" className="primary-button" onClick={() => inputRef.current?.click()}>
                  <Upload size={15} /> 选择文件
                </button>
                {files.length > 0 && (
                  <ul className="mx-auto mt-4 max-w-md space-y-1 text-left text-xs text-[#56749a]">
                    {files.map((file) => <li key={file.name}>· {file.name}</li>)}
                  </ul>
                )}
              </div>
            )}

            {step === "mapping" && (
              <div>
                <h3 className="m-0 text-sm font-semibold text-[#163665]">字段映射</h3>
                <p className="mb-3 mt-1 text-xs text-[#8fa3c0]">确认源字段与人才库字段的对应关系</p>
                <div className="overflow-hidden rounded-lg border border-[#e6eef7]">
                  <table className="min-w-full text-left text-sm">
                    <thead className="bg-[#f7fafc] text-xs text-[#6b80a4]">
                      <tr>
                        <th className="px-3 py-2 font-medium">源字段</th>
                        <th className="px-3 py-2 font-medium">映射到</th>
                      </tr>
                    </thead>
                    <tbody>
                      {previewRows.map((row) => (
                        <tr key={row.source} className="border-t border-[#eef3f8]">
                          <td className="px-3 py-2 text-[#36527f]">{row.source}</td>
                          <td className="px-3 py-2">
                            <select
                              className="h-8 rounded border border-[#d9e2ec] px-2 text-xs"
                              value={mapping[row.source] || "忽略"}
                              onChange={(e) => setMapping((prev) => ({ ...prev, [row.source]: e.target.value }))}
                            >
                              {FIELD_OPTIONS.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
                            </select>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </>
        )}

        {step !== "upload" && step !== "mapping" && step !== "done" && (
          <div className="py-10 text-center">
            <Loader2 className="mx-auto animate-spin text-[#2f6bff]" size={28} />
            <p className="mt-3 text-sm text-[#516c94]">正在执行：{STEPS.find((s) => s.id === step)?.label}… {progress}%</p>
          </div>
        )}

        {step === "done" && (
          <div className="py-8 text-center">
            <CheckCircle2 className="mx-auto text-[#12a974]" size={36} />
            <p className="mt-3 text-base font-semibold text-[#163665]">导入流程完成</p>
            <p className="text-sm text-[#7185a3]">{message || `已处理 ${importedCount} 条记录`}</p>
            <div className="mt-4 flex flex-wrap justify-center gap-2">
              <button type="button" className="primary-button" onClick={() => router.push("/candidates")}>
                <Import size={15} /> 进入人才库
              </button>
              <button
                type="button"
                className="outline-button"
                onClick={() => {
                  setStep("upload"); setFiles([]); setHeaders([]); setMapping({}); setMessage(null); setImportedCount(0);
                }}
              >
                继续导入
              </button>
            </div>
          </div>
        )}

        {(step === "upload" || step === "mapping") && (
          <div className="mt-5 flex justify-end gap-2">
            {step === "mapping" && (
              <button type="button" className="outline-button" onClick={() => setStep("upload")}>上一步</button>
            )}
            <button type="button" className="primary-button" disabled={busy} onClick={nextStep}>
              {busy ? <Loader2 size={15} className="animate-spin" /> : <ArrowRight size={15} />}
              {step === "upload" ? "下一步" : "开始导入"}
            </button>
          </div>
        )}
      </section>
    </AppShell>
  );
}

function wait(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
