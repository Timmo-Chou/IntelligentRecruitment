"use client";

import { ArrowLeft, Loader2, Sparkles } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import { fetchCandidate, parseProfile, type CandidateDetail } from "@/lib/candidate-api";
import { useWorkspace } from "@/lib/workspace-context";

export default function TalentPortraitPage() {
  const params = useParams<{ id: string }>();
  const candidateId = params?.id;
  const { workspaceId, loading: wsLoading, notAuthenticated } = useWorkspace();
  const [candidate, setCandidate] = useState<CandidateDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (notAuthenticated) window.location.replace("/login");
  }, [notAuthenticated]);

  useEffect(() => {
    if (!workspaceId || !candidateId) return;
    setLoading(true);
    void fetchCandidate(workspaceId, candidateId)
      .then(setCandidate)
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : "加载失败"))
      .finally(() => setLoading(false));
  }, [workspaceId, candidateId]);

  if (wsLoading || loading) {
    return <AppShell activeItem="人才库"><div className="grid h-64 place-items-center"><Loader2 className="animate-spin text-[#6b80a4]" /></div></AppShell>;
  }

  if (!candidate) {
    return (
      <AppShell activeItem="人才库">
        <div className="rounded-xl border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]">{error || "未找到人才"}</div>
      </AppShell>
    );
  }

  const profile = parseProfile(candidate.profileJson);
  const sections = [
    { title: "教育背景", body: candidate.educationExperience?.join("；") || String(profile.school || candidate.highestEducation || "待完善") },
    { title: "工作经历", body: candidate.workExperience?.join("；") || "待完善" },
    { title: "行业经验", body: String(profile.industry || "化工及相关制造业") },
    { title: "专业技能", body: candidate.skills.join("、") || "待识别" },
    { title: "项目经验", body: candidate.summary || "基于简历解析生成的项目能力摘要待补充" },
    { title: "证书", body: String(profile.certificates || "待补充") },
    { title: "管理能力", body: String(profile.managementSkills || "待评估") },
    { title: "职业稳定性", body: `${candidate.yearsExperience || 0} 年从业经验，稳定性评估中` },
  ];

  return (
    <AppShell activeItem="人才库">
      <div className="mb-4">
        <Link href="/candidates" className="inline-flex items-center gap-1 text-sm text-[#2f6bff] hover:underline">
          <ArrowLeft size={15} /> 返回人才库
        </Link>
        <h1 className="mb-0 mt-2 flex items-center gap-2 text-[25px] font-bold text-[#09245d]">
          <Sparkles size={22} className="text-[#12a974]" /> 人才画像
        </h1>
        <p className="mb-0 mt-1 text-sm text-[#60799f]">
          {candidate.displayNameMasked} · 简历解析 → 教育/经历/技能/证书 → 生成画像 → 用于 AI 匹配
        </p>
      </div>

      <section className="mb-4 rounded-2xl border border-[#d6e5f5] bg-gradient-to-br from-[#f7fbff] to-[#f0fbf7] p-5">
        <p className="m-0 text-sm leading-7 text-[#36527f]">
          AI 综合判断：该人才在「{candidate.skills.slice(0, 2).join("、") || "专业领域"}」方向具备可复用能力，
          适合工艺优化、项目改造类岗位；建议结合人岗匹配结果推进激活或邀约。
        </p>
      </section>

      <div className="grid gap-3 md:grid-cols-2">
        {sections.map((item) => (
          <article key={item.title} className="rounded-xl border border-[#e6eef7] bg-white p-4">
            <h2 className="m-0 text-sm font-bold text-[#173568]">{item.title}</h2>
            <p className="mb-0 mt-2 text-xs leading-6 text-[#56749a]">{item.body}</p>
          </article>
        ))}
      </div>
    </AppShell>
  );
}
