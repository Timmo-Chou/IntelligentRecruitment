"use client";

import { ArrowLeft, Loader2, Plus, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import { createTalent } from "@/lib/candidate-api";
import {
  EDUCATION_OPTIONS, GENDER_OPTIONS, LEVEL_OPTIONS, REGION_TREE, TALENT_INDUSTRIES,
  TALENT_TAGS, YEARS_OPTIONS, type TalentProfileInput,
} from "@/lib/talent-constants";
import { useWorkspace } from "@/lib/workspace-context";

const EMPTY: TalentProfileInput = {
  fullName: "", gender: "男", phone: "", email: "",
  province: "", city: "", district: "",
  currentCompany: "", currentTitle: "", currentLevel: "中级",
  yearsExperience: "3-5年", industry: "精细化工",
  highestEducation: "本科", school: "", major: "", graduateAt: "",
  professionalSkills: "", softwareSkills: "", managementSkills: "", industrySkills: "",
  tags: [], source: "手动新增", certificates: "", jobCategory: "", age: "",
};

export default function NewTalentPage() {
  const router = useRouter();
  const { workspaceId, loading: wsLoading, notAuthenticated } = useWorkspace();
  const [form, setForm] = useState<TalentProfileInput>(EMPTY);
  const [tagDraft, setTagDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (notAuthenticated) window.location.replace("/login");
  }, [notAuthenticated]);

  const cities = useMemo(
    () => REGION_TREE.find((p) => p.name === form.province)?.children ?? [],
    [form.province],
  );
  const districts = useMemo(
    () => cities.find((c) => c.name === form.city)?.children ?? [],
    [cities, form.city],
  );

  function update<K extends keyof TalentProfileInput>(key: K, value: TalentProfileInput[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function addTag(tag: string) {
    const value = tag.trim();
    if (!value || form.tags.includes(value)) return;
    update("tags", [...form.tags, value]);
    setTagDraft("");
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!workspaceId) return;
    if (!form.fullName.trim()) {
      setError("请填写姓名");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const created = await createTalent(workspaceId, form);
      router.push(`/candidates?highlight=${created.id}`);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "保存失败，请稍后重试");
    } finally {
      setSaving(false);
    }
  }

  if (wsLoading) {
    return <AppShell activeItem="人才库"><div className="grid h-64 place-items-center text-sm text-[#7085a4]">加载中...</div></AppShell>;
  }

  return (
    <AppShell activeItem="人才库">
      <div className="mb-4 flex items-center justify-between gap-3">
        <div>
          <Link href="/candidates" className="inline-flex items-center gap-1 text-sm text-[#2f6bff] hover:underline">
            <ArrowLeft size={15} /> 返回人才库
          </Link>
          <h1 className="mb-0 mt-2 text-[25px] font-bold text-[#09245d]">新增人才</h1>
          <p className="mb-0 mt-1 text-sm text-[#60799f]">录入基础信息、职业信息、教育背景、技能与标签</p>
        </div>
      </div>

      {error && <div className="mb-4 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]">{error}</div>}

      <form onSubmit={handleSubmit} className="space-y-4">
        <Section title="基础信息">
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            <Field label="姓名" required value={form.fullName} onChange={(v) => update("fullName", v)} />
            <SelectField label="性别" value={form.gender} options={[...GENDER_OPTIONS]} onChange={(v) => update("gender", v)} />
            <Field label="年龄" value={form.age || ""} onChange={(v) => update("age", v)} placeholder="如：32" />
            <Field label="手机" value={form.phone} onChange={(v) => update("phone", v)} placeholder="11 位手机号" />
            <Field label="邮箱" value={form.email} onChange={(v) => update("email", v)} />
            <SelectField
              label="省"
              value={form.province}
              options={REGION_TREE.map((p) => p.name)}
              onChange={(v) => setForm((prev) => ({ ...prev, province: v, city: "", district: "" }))}
              placeholder="请选择省"
            />
            <SelectField
              label="市"
              value={form.city}
              options={cities.map((c) => c.name)}
              onChange={(v) => setForm((prev) => ({ ...prev, city: v, district: "" }))}
              placeholder="请选择市"
            />
            <SelectField
              label="区"
              value={form.district}
              options={districts.map((d) => d.name)}
              onChange={(v) => update("district", v)}
              placeholder="请选择区"
            />
          </div>
        </Section>

        <Section title="职业信息">
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            <Field label="当前公司" value={form.currentCompany} onChange={(v) => update("currentCompany", v)} />
            <Field label="当前职位" value={form.currentTitle} onChange={(v) => update("currentTitle", v)} />
            <SelectField label="当前职级" value={form.currentLevel} options={[...LEVEL_OPTIONS]} onChange={(v) => update("currentLevel", v)} />
            <SelectField label="工作年限" value={form.yearsExperience} options={[...YEARS_OPTIONS]} onChange={(v) => update("yearsExperience", v)} />
            <SelectField label="行业经验" value={form.industry} options={[...TALENT_INDUSTRIES]} onChange={(v) => update("industry", v)} />
            <Field label="职位类别" value={form.jobCategory || ""} onChange={(v) => update("jobCategory", v)} placeholder="如：工艺技术类" />
          </div>
        </Section>

        <Section title="教育背景">
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <SelectField label="最高学历" value={form.highestEducation} options={[...EDUCATION_OPTIONS]} onChange={(v) => update("highestEducation", v)} />
            <Field label="学校" value={form.school} onChange={(v) => update("school", v)} />
            <Field label="专业" value={form.major} onChange={(v) => update("major", v)} />
            <Field label="毕业时间" value={form.graduateAt} onChange={(v) => update("graduateAt", v)} placeholder="如：2016-06" />
          </div>
        </Section>

        <Section title="技能">
          <div className="grid gap-3 md:grid-cols-2">
            <TextArea label="专业技能" value={form.professionalSkills} onChange={(v) => update("professionalSkills", v)} placeholder="逗号分隔，如：工艺设计, 反应工程" />
            <TextArea label="软件技能" value={form.softwareSkills} onChange={(v) => update("softwareSkills", v)} placeholder="如：Aspen Plus, AutoCAD" />
            <TextArea label="管理技能" value={form.managementSkills} onChange={(v) => update("managementSkills", v)} placeholder="如：项目管理, 团队管理" />
            <TextArea label="行业技能" value={form.industrySkills} onChange={(v) => update("industrySkills", v)} placeholder="如：精细化工, 新材料" />
          </div>
        </Section>

        <Section title="标签">
          <div className="flex flex-wrap gap-2">
            {TALENT_TAGS.map((tag) => {
              const active = form.tags.includes(tag);
              return (
                <button
                  key={tag}
                  type="button"
                  onClick={() => update("tags", active ? form.tags.filter((t) => t !== tag) : [...form.tags, tag])}
                  className={`rounded-full px-3 py-1 text-xs font-medium ${active ? "bg-[#2f6bff] text-white" : "bg-[#edf5ff] text-[#3970ad]"}`}
                >
                  {tag}
                </button>
              );
            })}
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <input
              value={tagDraft}
              onChange={(e) => setTagDraft(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addTag(tagDraft); } }}
              placeholder="自定义标签后回车添加"
              className="h-9 min-w-[220px] flex-1 rounded-lg border border-[#d9e2ec] px-3 text-sm"
            />
            <button type="button" className="outline-button !h-9" onClick={() => addTag(tagDraft)}><Plus size={14} />添加</button>
          </div>
          {form.tags.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-2">
              {form.tags.map((tag) => (
                <span key={tag} className="inline-flex items-center gap-1 rounded-full bg-[#fff0e8] px-2.5 py-1 text-xs text-[#d45d1c]">
                  {tag}
                  <button type="button" onClick={() => update("tags", form.tags.filter((t) => t !== tag))}><X size={12} /></button>
                </span>
              ))}
            </div>
          )}
        </Section>

        <div className="flex justify-end gap-3 pb-8">
          <Link href="/candidates" className="outline-button !h-10">取消</Link>
          <button type="submit" className="primary-button !h-10" disabled={saving}>
            {saving ? <><Loader2 size={16} className="animate-spin" /> 保存中...</> : "保存并入库"}
          </button>
        </div>
      </form>
    </AppShell>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-[#d6e5f5] bg-white p-5 shadow-[0_4px_16px_rgba(30,92,160,0.04)]">
      <h2 className="mb-4 mt-0 text-base font-bold text-[#173568]">{title}</h2>
      {children}
    </section>
  );
}

function Field({ label, value, onChange, required, placeholder }: {
  label: string; value: string; onChange: (v: string) => void; required?: boolean; placeholder?: string;
}) {
  return (
    <label className="block text-xs font-medium text-[#36527f]">
      {label}{required && <span className="text-[#dc2626]"> *</span>}
      <input
        className="mt-1 h-10 w-full rounded-lg border border-[#d9e2ec] px-3 text-sm text-[#132e61] outline-none focus:border-[#2f6bff]"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
    </label>
  );
}

function SelectField({ label, value, options, onChange, placeholder }: {
  label: string; value: string; options: string[]; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <label className="block text-xs font-medium text-[#36527f]">
      {label}
      <select
        className="mt-1 h-10 w-full rounded-lg border border-[#d9e2ec] bg-white px-3 text-sm text-[#132e61] outline-none focus:border-[#2f6bff]"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        {placeholder && <option value="">{placeholder}</option>}
        {options.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
      </select>
    </label>
  );
}

function TextArea({ label, value, onChange, placeholder }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <label className="block text-xs font-medium text-[#36527f]">
      {label}
      <textarea
        className="mt-1 min-h-[84px] w-full rounded-lg border border-[#d9e2ec] px-3 py-2 text-sm text-[#132e61] outline-none focus:border-[#2f6bff]"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
    </label>
  );
}
