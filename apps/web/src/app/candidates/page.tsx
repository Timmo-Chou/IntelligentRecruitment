"use client";

import {
  AlertCircle, ChevronDown, ChevronLeft, ChevronRight, Download, Eye, FileText, Import,
  Loader2, Plus, RefreshCw, Search, ShieldCheck,
  Trash2, TrendingDown, TrendingUp, Upload, Users, UsersRound, X, Zap, Moon,
} from "lucide-react";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, type ComponentType, type ReactNode } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { ApiError } from "@/lib/api-client";
import {
  deleteCandidate, downloadResume, fetchCandidate, fetchCandidateStats, fetchCandidates, parseProfile,
  revealCandidate, retryResumeParse, updateCandidateTags, uploadResume,
  type CandidateDetail, type CandidateListQuery, type CandidateSegment, type CandidateStats, type CandidateSummary,
  type RevealedPii, type StatPoint,
} from "@/lib/candidate-api";
import {
  ACTIVITY_OPTIONS, EDUCATION_OPTIONS, LEVEL_OPTIONS, REGION_TREE, TALENT_INDUSTRIES, TALENT_SOURCES,
  TALENT_STATUS_OPTIONS, TALENT_TAGS, YEARS_OPTIONS,
} from "@/lib/talent-constants";
import { useWorkspace } from "@/lib/workspace-context";

type StatKey = "total" | "active" | "highMatch" | "dormant" | "inPool";
type DetailTab = "ai" | "basic" | "work" | "edu" | "skills" | "files" | "activity";

const PAGE_SIZE_OPTIONS = [10, 20, 50];

type MoreFilters = {
  yearsRange: string;
  ageMin: string;
  ageMax: string;
  education: string;
  currentTitle: string;
  currentCompany: string;
  historyCompany: string;
  industryExp: string;
  jobCategory: string;
  level: string;
  skill: string;
  certificate: string;
  talentStatus: string;
  source: string;
  activity: string;
  lastContactFrom: string;
  lastContactTo: string;
  createdFrom: string;
  createdTo: string;
  minMatchScore: string;
};

const EMPTY_MORE: MoreFilters = {
  yearsRange: "", ageMin: "", ageMax: "", education: "", currentTitle: "", currentCompany: "",
  historyCompany: "", industryExp: "", jobCategory: "", level: "", skill: "", certificate: "",
  talentStatus: "", source: "", activity: "", lastContactFrom: "", lastContactTo: "",
  createdFrom: "", createdTo: "", minMatchScore: "",
};

const STAT_CARDS: {
  key: StatKey;
  label: string;
  description: string;
  segment: CandidateSegment;
  Icon: ComponentType<{ size?: number }>;
}[] = [
  { key: "total", label: "人才总数", description: "企业人才库中的全部人才", segment: "", Icon: Users },
  { key: "active", label: "活跃人才", description: "近 30 天有更新或互动的人才", segment: "ACTIVE_TALENT", Icon: Zap },
  { key: "highMatch", label: "高匹配人才", description: "与招聘职位匹配度达到设定阈值", segment: "HIGH_MATCH", Icon: ShieldCheck },
  { key: "dormant", label: "待激活人才", description: "长期无互动但仍有潜在价值", segment: "DORMANT", Icon: Moon },
  { key: "inPool", label: "已入库候选人", description: "已成功解析并进入人才库", segment: "IN_POOL", Icon: UsersRound },
];

export default function CandidatesPage() {
  return <CandidatesWorkspace />;
}

function CandidatesWorkspace({ embedded = false }: { embedded?: boolean }) {
  const router = useRouter();
  const { workspaceId, workspace, loading: workspaceLoading, notAuthenticated } = useWorkspace();
  const [items, setItems] = useState<CandidateSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [stats, setStats] = useState<CandidateStats | null>(null);
  const [selected, setSelected] = useState<CandidateDetail | null>(null);
  const [revealed, setRevealed] = useState<RevealedPii | null>(null);
  const [detailTab, setDetailTab] = useState<DetailTab>("ai");
  const [search, setSearch] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [status, setStatus] = useState("");
  const [segment, setSegment] = useState<CandidateSegment>("");
  const [activeStat, setActiveStat] = useState<StatKey>("total");
  const [sourceFilter, setSourceFilter] = useState("");
  const [industryFilter, setIndustryFilter] = useState("");
  const [province, setProvince] = useState("");
  const [city, setCity] = useState("");
  const [district, setDistrict] = useState("");
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [filterMenu, setFilterMenu] = useState<"source" | "industry" | "city" | "tags" | "more" | null>(null);
  const [moreFilters, setMoreFilters] = useState<MoreFilters>(EMPTY_MORE);
  const [draftMore, setDraftMore] = useState<MoreFilters>(EMPTY_MORE);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [batchMenuOpen, setBatchMenuOpen] = useState(false);
  const [batchBusy, setBatchBusy] = useState(false);
  const [rowMoreId, setRowMoreId] = useState<string | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const batchMenuRef = useRef<HTMLDivElement>(null);
  const filterBarRef = useRef<HTMLDivElement>(null);

  const cityOptions = useMemo(
    () => REGION_TREE.find((item) => item.name === province)?.children ?? [],
    [province],
  );
  const districtOptions = useMemo(
    () => cityOptions.find((item) => item.name === city)?.children ?? [],
    [cityOptions, city],
  );
  const cityFilterValue = district || city || province;

  const loadStats = useCallback(async () => {
    if (!workspaceId) return;
    try {
      setStats(await fetchCandidateStats(workspaceId));
    } catch {
      /* ignore */
    }
  }, [workspaceId]);

  const load = useCallback(async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const years = yearsRangeToBounds(moreFilters.yearsRange);
      const searchExtra = [
        moreFilters.currentTitle, moreFilters.currentCompany, moreFilters.historyCompany,
        moreFilters.skill, moreFilters.certificate, moreFilters.jobCategory, moreFilters.level,
      ].filter(Boolean);
      const effectiveSearch = [search, ...searchExtra].filter(Boolean).join(" ").trim();
      const query: CandidateListQuery = {
        search: effectiveSearch || undefined,
        status,
        segment,
        minMatchScore: segment === "HIGH_MATCH"
          ? (stats?.highMatchThreshold ?? 80)
          : (moreFilters.minMatchScore ? Number(moreFilters.minMatchScore) : undefined),
        industry: industryFilter || moreFilters.industryExp || undefined,
        city: cityFilterValue || undefined,
        tags: selectedTags.length ? selectedTags.join(",") : undefined,
        yearsMin: years.min,
        yearsMax: years.max,
        education: moreFilters.education || undefined,
        source: sourceFilter || moreFilters.source || undefined,
        activity: moreFilters.activity || undefined,
        talentStatus: moreFilters.talentStatus || undefined,
        createdFrom: moreFilters.createdFrom || undefined,
        createdTo: moreFilters.createdTo || undefined,
        page,
        pageSize,
      };
      const result = await fetchCandidates(workspaceId, query);
      setItems(result.items);
      setTotal(result.total);
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setLoading(false);
    }
  }, [
    workspaceId, search, status, segment, stats?.highMatchThreshold, industryFilter, cityFilterValue,
    selectedTags, sourceFilter, moreFilters, page, pageSize,
  ]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setPage(1);
      setSearch(searchInput.trim());
    }, 300);
    return () => window.clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => { void loadStats(); }, [loadStats]);
  useEffect(() => { if (notAuthenticated) window.location.replace("/login"); }, [notAuthenticated]);

  useEffect(() => {
    if (!batchMenuOpen && !filterMenu) return;
    const onPointerDown = (e: PointerEvent) => {
      if (batchMenuOpen && !batchMenuRef.current?.contains(e.target as Node)) setBatchMenuOpen(false);
      if (filterMenu && !filterBarRef.current?.contains(e.target as Node)) setFilterMenu(null);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [batchMenuOpen, filterMenu]);

  useEffect(() => { setSelectedIds(new Set()); }, [items]);

  const displayedItems = items;

  const activeMoreCount = useMemo(
    () => Object.values(moreFilters).filter((v) => String(v).trim() !== "").length,
    [moreFilters],
  );

  const cityLabel = [province, city, district].filter(Boolean).join(" / ") || "现居城市";
  const sourceLabel = sourceFilter || "人才来源";
  const industryLabel = industryFilter || "所属行业";
  const tagsLabel = selectedTags.length ? `标签 · ${selectedTags.length}` : "标签";
  const moreLabel = activeMoreCount ? `更多筛选 · ${activeMoreCount}` : "更多筛选";

  function toggleFilterMenu(menu: typeof filterMenu) {
    setFilterMenu((prev) => {
      const next = prev === menu ? null : menu;
      if (next === "more") setDraftMore(moreFilters);
      return next;
    });
  }

  function resetFilters() {
    setSourceFilter("");
    setIndustryFilter("");
    setProvince("");
    setCity("");
    setDistrict("");
    setSelectedTags([]);
    setMoreFilters(EMPTY_MORE);
    setDraftMore(EMPTY_MORE);
    setStatus("");
    setSearchInput("");
    setSearch("");
    applyStatFilter("total", "");
    setPage(1);
    setFilterMenu(null);
  }

  function toggleTag(tag: string) {
    setSelectedTags((prev) => {
      const next = prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag];
      setPage(1);
      return next;
    });
  }

  async function openDetail(candidateId: string) {
    if (!workspaceId) return;
    setBusy(true);
    setRevealed(null);
    setDetailTab("ai");
    try {
      setSelected(await fetchCandidate(workspaceId, candidateId));
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  }

  async function handleReveal() {
    if (!workspaceId || !selected) return;
    setBusy(true);
    try {
      setRevealed(await revealCandidate(workspaceId, selected.id));
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  }

  async function handleRetry() {
    if (!workspaceId || !selected) return;
    setBusy(true);
    try {
      const next = await retryResumeParse(workspaceId, selected.id);
      setSelected(next);
      await Promise.all([load(), loadStats()]);
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(candidate?: CandidateDetail | CandidateSummary) {
    const target = candidate ?? selected;
    if (!workspaceId || !target) return;
    if (!window.confirm(`确定删除「${target.displayNameMasked}」及原简历吗？`)) return;
    setBusy(true);
    try {
      await deleteCandidate(workspaceId, target.id);
      if (selected?.id === target.id) {
        setSelected(null);
        setRevealed(null);
      }
      await Promise.all([load(), loadStats()]);
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
      setRowMoreId(null);
    }
  }

  function applyStatFilter(key: StatKey, nextSegment: CandidateSegment) {
    setActiveStat(key);
    setSegment(nextSegment);
    setPage(1);
    if (nextSegment === "IN_POOL") setStatus("PARSED");
    else if (status === "PARSED" && key !== "inPool") setStatus("");
  }

  function toggleSelect(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleSelectAll() {
    if (displayedItems.length > 0 && displayedItems.every((item) => selectedIds.has(item.id))) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(displayedItems.map((item) => item.id)));
    }
  }

  async function handleBatchDelete() {
    if (!workspaceId || selectedIds.size === 0) return;
    if (!window.confirm(`确定批量删除选中的 ${selectedIds.size} 位人才吗？`)) return;
    setBatchBusy(true);
    setBatchMenuOpen(false);
    try {
      const ids = Array.from(selectedIds);
      await Promise.allSettled(ids.map((id) => deleteCandidate(workspaceId, id)));
      if (selected && ids.includes(selected.id)) {
        setSelected(null);
        setRevealed(null);
      }
      setSelectedIds(new Set());
      await Promise.all([load(), loadStats()]);
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBatchBusy(false);
    }
  }

  function handleBatchExport() {
    setBatchMenuOpen(false);
    const rows = displayedItems.filter((item) => selectedIds.has(item.id));
    if (!rows.length) return;
    const header = ["姓名", "职位/公司", "技能", "标签", "匹配职位", "匹配度", "入库时间"];
    const lines = rows.map((item) => [
      item.displayNameMasked,
      item.headline || "",
      item.skills.join("、"),
      deriveTags(item).join("、"),
      item.matchedJobTitle || "",
      typeof item.matchScore === "number" ? `${item.matchScore}%` : "",
      formatDateOnly(item.createdAt || item.updatedAt),
    ].map((cell) => `"${String(cell).replaceAll('"', '""')}"`).join(","));
    const blob = new Blob([[header.join(","), ...lines].join("\n")], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `人才导出_${rows.length}人.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  if (workspaceLoading) {
    return embedded
      ? <div className="grid h-64 place-items-center text-sm text-[#7085a4]">正在加载工作空间...</div>
      : <State text="正在加载工作空间..." />;
  }
  if (!workspaceId) {
    return embedded
      ? <div className="grid h-64 place-items-center text-sm text-[#7085a4]">请先登录并进入一个可访问的工作空间</div>
      : <State text="请先登录并进入一个可访问的工作空间" />;
  }

  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const allSelected = displayedItems.length > 0 && displayedItems.every((item) => selectedIds.has(item.id));
  const threshold = stats?.highMatchThreshold ?? 80;

  const content = (
    <>
      <section className="mb-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="m-0 text-[25px] font-bold text-[#09245d]">人才库 / 人才列表</h1>
            <p className="mb-0 mt-1 text-sm text-[#60799f]">
              {workspace?.name} · 管理企业人才资产，支持搜索、筛选、匹配与批量操作
            </p>
          </div>
        </div>
      </section>

      <section className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-5" aria-label="人才概览">
        {stats ? STAT_CARDS.map((card) => (
          <StatCard
            key={card.key}
            label={card.label}
            description={card.description}
            point={stats[card.key]}
            Icon={card.Icon}
            active={activeStat === card.key}
            onClick={() => applyStatFilter(card.key, card.segment)}
          />
        )) : (
          <div className="col-span-full flex items-center justify-center py-8">
            <Loader2 className="animate-spin text-[#6b80a4]" size={20} />
          </div>
        )}
      </section>

      <section className="mb-4 space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <label className="flex h-10 min-w-[260px] max-w-[520px] flex-1 items-center gap-2 rounded-lg border border-[#d9e2ec] bg-white px-3 text-[#9aa8bc]">
            <Search size={16} className="shrink-0" />
            <input
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              className="min-w-0 flex-1 border-0 bg-transparent text-sm text-[#243b63] outline-none placeholder:text-[#b0beca]"
              placeholder="搜索姓名、手机号、邮箱、技能、公司等"
            />
          </label>
          <div className="flex shrink-0 items-center gap-2">
            <button
              type="button"
              onClick={() => setImportOpen(true)}
              className="inline-flex h-10 items-center gap-1.5 rounded-lg border border-[#d9e2ec] bg-white px-4 text-sm font-medium text-[#334155] hover:bg-[#f8fafc]"
            >
              <Import size={16} />
              导入人才
            </button>
            <button
              type="button"
              onClick={() => router.push("/candidates/new")}
              className="inline-flex h-10 items-center gap-1.5 rounded-lg bg-[#2f6bff] px-4 text-sm font-medium text-white hover:bg-[#1f5aef]"
            >
              <Plus size={16} /> 新增人才
            </button>
            <div className="relative" ref={batchMenuRef}>
              <button
                type="button"
                disabled={batchBusy}
                onClick={() => setBatchMenuOpen((v) => !v)}
                className="inline-flex h-10 items-center gap-1.5 rounded-lg border border-[#d9e2ec] bg-white px-4 text-sm font-medium text-[#334155] hover:bg-[#f8fafc]"
              >
                批量操作 <ChevronDown size={15} className={batchMenuOpen ? "rotate-180" : ""} />
              </button>
              {batchMenuOpen && (
                <div className="absolute right-0 z-40 mt-1 w-44 overflow-hidden rounded-lg border border-[#d6e5f5] bg-white py-1 shadow-lg">
                  {selectedIds.size === 0 && (
                    <p className="border-b border-[#eaf1fa] px-3 py-2 text-xs text-[#8fa3c0]">请先勾选人才后再操作</p>
                  )}
                  <button type="button" disabled={selectedIds.size === 0} className="block w-full px-3 py-2 text-left text-sm text-[#36527f] hover:bg-[#f5f9ff] disabled:text-[#b0becf]" onClick={handleBatchExport}>批量导出</button>
                  <button type="button" disabled={selectedIds.size === 0} className="block w-full px-3 py-2 text-left text-sm text-[#dc2626] hover:bg-[#fef2f2] disabled:text-[#f0b4b4]" onClick={() => void handleBatchDelete()}>批量删除</button>
                </div>
              )}
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2" ref={filterBarRef}>
          <div className="relative">
            <FilterTrigger
              label={sourceLabel}
              active={!!sourceFilter || filterMenu === "source"}
              open={filterMenu === "source"}
              onClick={() => toggleFilterMenu("source")}
            />
            {filterMenu === "source" && (
              <FilterPanel className="w-52">
                <FilterOption active={!sourceFilter} onClick={() => { setSourceFilter(""); setPage(1); setFilterMenu(null); }}>全部来源</FilterOption>
                {TALENT_SOURCES.map((opt) => (
                  <FilterOption key={opt} active={sourceFilter === opt} onClick={() => { setSourceFilter(opt); setPage(1); setFilterMenu(null); }}>{opt}</FilterOption>
                ))}
              </FilterPanel>
            )}
          </div>

          <div className="relative">
            <FilterTrigger
              label={industryLabel}
              active={!!industryFilter || filterMenu === "industry"}
              open={filterMenu === "industry"}
              onClick={() => toggleFilterMenu("industry")}
            />
            {filterMenu === "industry" && (
              <FilterPanel className="w-52 max-h-72 overflow-y-auto">
                <FilterOption active={!industryFilter} onClick={() => { setIndustryFilter(""); setPage(1); setFilterMenu(null); }}>全部行业</FilterOption>
                {TALENT_INDUSTRIES.map((opt) => (
                  <FilterOption key={opt} active={industryFilter === opt} onClick={() => { setIndustryFilter(opt); setPage(1); setFilterMenu(null); }}>{opt}</FilterOption>
                ))}
              </FilterPanel>
            )}
          </div>

          <div className="relative">
            <FilterTrigger
              label={cityLabel}
              active={!!cityFilterValue || filterMenu === "city"}
              open={filterMenu === "city"}
              onClick={() => toggleFilterMenu("city")}
            />
            {filterMenu === "city" && (
              <FilterPanel className="w-[min(100vw-2rem,420px)] p-3">
                <div className="grid grid-cols-3 gap-2">
                  <div className="max-h-56 overflow-y-auto rounded-lg border border-[#eef3f8]">
                    {REGION_TREE.map((item) => (
                      <button
                        key={item.name}
                        type="button"
                        className={`block w-full px-2.5 py-1.5 text-left text-[13px] ${province === item.name ? "bg-[#f3f8ff] text-[#2f6bff]" : "text-[#36527f] hover:bg-[#f8fafc]"}`}
                        onClick={() => { setProvince(item.name); setCity(""); setDistrict(""); setPage(1); }}
                      >
                        {item.name}
                      </button>
                    ))}
                  </div>
                  <div className="max-h-56 overflow-y-auto rounded-lg border border-[#eef3f8]">
                    {cityOptions.length === 0 ? (
                      <p className="px-2.5 py-2 text-xs text-[#9db0c9]">先选省</p>
                    ) : cityOptions.map((item) => (
                      <button
                        key={item.name}
                        type="button"
                        className={`block w-full px-2.5 py-1.5 text-left text-[13px] ${city === item.name ? "bg-[#f3f8ff] text-[#2f6bff]" : "text-[#36527f] hover:bg-[#f8fafc]"}`}
                        onClick={() => { setCity(item.name); setDistrict(""); setPage(1); }}
                      >
                        {item.name}
                      </button>
                    ))}
                  </div>
                  <div className="max-h-56 overflow-y-auto rounded-lg border border-[#eef3f8]">
                    {districtOptions.length === 0 ? (
                      <p className="px-2.5 py-2 text-xs text-[#9db0c9]">先选市</p>
                    ) : districtOptions.map((item) => (
                      <button
                        key={item.name}
                        type="button"
                        className={`block w-full px-2.5 py-1.5 text-left text-[13px] ${district === item.name ? "bg-[#f3f8ff] text-[#2f6bff]" : "text-[#36527f] hover:bg-[#f8fafc]"}`}
                        onClick={() => { setDistrict(item.name); setPage(1); setFilterMenu(null); }}
                      >
                        {item.name}
                      </button>
                    ))}
                  </div>
                </div>
                {(province || city || district) && (
                  <div className="mt-2 flex justify-between gap-2">
                    <button type="button" className="text-xs text-[#8fa3c0]" onClick={() => { setProvince(""); setCity(""); setDistrict(""); setPage(1); }}>清空</button>
                    <button type="button" className="rounded-md bg-[#2f6bff] px-3 py-1 text-xs font-medium text-white" onClick={() => setFilterMenu(null)}>确定</button>
                  </div>
                )}
              </FilterPanel>
            )}
          </div>

          <div className="relative">
            <FilterTrigger
              label={tagsLabel}
              active={selectedTags.length > 0 || filterMenu === "tags"}
              open={filterMenu === "tags"}
              onClick={() => toggleFilterMenu("tags")}
            />
            {filterMenu === "tags" && (
              <FilterPanel className="w-56 p-2">
                {TALENT_TAGS.map((tag) => {
                  const checked = selectedTags.includes(tag);
                  return (
                    <label key={tag} className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-[13px] text-[#36527f] hover:bg-[#f5f9ff]">
                      <input type="checkbox" checked={checked} onChange={() => toggleTag(tag)} />
                      {tag}
                    </label>
                  );
                })}
                {selectedTags.length > 0 && (
                  <button type="button" className="mt-1 w-full rounded-md px-2 py-1.5 text-left text-xs text-[#8fa3c0] hover:bg-[#f8fafc]" onClick={() => { setSelectedTags([]); setPage(1); }}>
                    清空标签
                  </button>
                )}
              </FilterPanel>
            )}
          </div>

          <div className="relative">
            <FilterTrigger
              label={moreLabel}
              active={activeMoreCount > 0 || filterMenu === "more"}
              open={filterMenu === "more"}
              onClick={() => toggleFilterMenu("more")}
            />
            {filterMenu === "more" && (
              <FilterPanel className="right-0 w-[min(100vw-2rem,720px)] p-4 sm:left-auto">
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  <MoreField label="工作年限">
                    <select value={draftMore.yearsRange} onChange={(e) => setDraftMore({ ...draftMore, yearsRange: e.target.value })}>
                      <option value="">不限</option>
                      {YEARS_OPTIONS.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
                    </select>
                  </MoreField>
                  <MoreField label="年龄">
                    <div className="flex items-center gap-2">
                      <input type="number" min={18} max={70} placeholder="最小" value={draftMore.ageMin} onChange={(e) => setDraftMore({ ...draftMore, ageMin: e.target.value })} />
                      <span className="text-[#9db0c9]">-</span>
                      <input type="number" min={18} max={70} placeholder="最大" value={draftMore.ageMax} onChange={(e) => setDraftMore({ ...draftMore, ageMax: e.target.value })} />
                    </div>
                  </MoreField>
                  <MoreField label="学历">
                    <select value={draftMore.education} onChange={(e) => setDraftMore({ ...draftMore, education: e.target.value })}>
                      <option value="">不限</option>
                      {EDUCATION_OPTIONS.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
                    </select>
                  </MoreField>
                  <MoreField label="当前职位">
                    <input value={draftMore.currentTitle} onChange={(e) => setDraftMore({ ...draftMore, currentTitle: e.target.value })} placeholder="如：工艺工程师" />
                  </MoreField>
                  <MoreField label="当前公司">
                    <input value={draftMore.currentCompany} onChange={(e) => setDraftMore({ ...draftMore, currentCompany: e.target.value })} placeholder="公司名称" />
                  </MoreField>
                  <MoreField label="历史公司">
                    <input value={draftMore.historyCompany} onChange={(e) => setDraftMore({ ...draftMore, historyCompany: e.target.value })} placeholder="曾任职公司" />
                  </MoreField>
                  <MoreField label="行业经验">
                    <select value={draftMore.industryExp} onChange={(e) => setDraftMore({ ...draftMore, industryExp: e.target.value })}>
                      <option value="">不限</option>
                      {TALENT_INDUSTRIES.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
                    </select>
                  </MoreField>
                  <MoreField label="职位类别">
                    <input value={draftMore.jobCategory} onChange={(e) => setDraftMore({ ...draftMore, jobCategory: e.target.value })} placeholder="如：研发 / 工艺" />
                  </MoreField>
                  <MoreField label="职级">
                    <select value={draftMore.level} onChange={(e) => setDraftMore({ ...draftMore, level: e.target.value })}>
                      <option value="">不限</option>
                      {LEVEL_OPTIONS.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
                    </select>
                  </MoreField>
                  <MoreField label="技能">
                    <input value={draftMore.skill} onChange={(e) => setDraftMore({ ...draftMore, skill: e.target.value })} placeholder="如：Aspen Plus" />
                  </MoreField>
                  <MoreField label="证书">
                    <input value={draftMore.certificate} onChange={(e) => setDraftMore({ ...draftMore, certificate: e.target.value })} placeholder="证书名称" />
                  </MoreField>
                  <MoreField label="人才状态">
                    <select value={draftMore.talentStatus} onChange={(e) => setDraftMore({ ...draftMore, talentStatus: e.target.value })}>
                      <option value="">不限</option>
                      {TALENT_STATUS_OPTIONS.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
                    </select>
                  </MoreField>
                  <MoreField label="人才活跃度">
                    <select value={draftMore.activity} onChange={(e) => setDraftMore({ ...draftMore, activity: e.target.value })}>
                      <option value="">不限</option>
                      {ACTIVITY_OPTIONS.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
                    </select>
                  </MoreField>
                  <MoreField label="最后联系时间">
                    <div className="flex items-center gap-2">
                      <input type="date" value={draftMore.lastContactFrom} onChange={(e) => setDraftMore({ ...draftMore, lastContactFrom: e.target.value })} />
                      <span className="text-[#9db0c9]">-</span>
                      <input type="date" value={draftMore.lastContactTo} onChange={(e) => setDraftMore({ ...draftMore, lastContactTo: e.target.value })} />
                    </div>
                  </MoreField>
                  <MoreField label="人才入库时间">
                    <div className="flex items-center gap-2">
                      <input type="date" value={draftMore.createdFrom} onChange={(e) => setDraftMore({ ...draftMore, createdFrom: e.target.value })} />
                      <span className="text-[#9db0c9]">-</span>
                      <input type="date" value={draftMore.createdTo} onChange={(e) => setDraftMore({ ...draftMore, createdTo: e.target.value })} />
                    </div>
                  </MoreField>
                  <MoreField label="AI匹配度">
                    <select value={draftMore.minMatchScore} onChange={(e) => setDraftMore({ ...draftMore, minMatchScore: e.target.value })}>
                      <option value="">不限</option>
                      <option value="60">≥ 60%</option>
                      <option value="70">≥ 70%</option>
                      <option value="80">≥ {threshold}%</option>
                      <option value="90">≥ 90%</option>
                    </select>
                  </MoreField>
                </div>
                <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
                  <button
                    type="button"
                    className="h-9 rounded-lg border border-[#d9e2ec] px-3 text-sm text-[#36527f]"
                    onClick={() => { setDraftMore(EMPTY_MORE); setMoreFilters(EMPTY_MORE); setPage(1); }}
                  >
                    清空条件
                  </button>
                  <button
                    type="button"
                    className="h-9 rounded-lg bg-[#2f6bff] px-4 text-sm font-medium text-white"
                    onClick={() => { setMoreFilters(draftMore); setPage(1); setFilterMenu(null); }}
                  >
                    应用筛选
                  </button>
                </div>
              </FilterPanel>
            )}
          </div>

          {(industryFilter || cityFilterValue || selectedTags.length || sourceFilter || activeMoreCount || search) && (
            <button type="button" onClick={resetFilters} className="h-9 px-2 text-[13px] text-[#8fa3c0] hover:text-[#36527f]">
              重置
            </button>
          )}
        </div>
      </section>

      {error && (
        <div className="mb-4 flex items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-4 py-3 text-sm text-[#b42318]">
          <AlertCircle size={17} />{error}
        </div>
      )}

      <div className={`grid min-h-[640px] gap-4 ${selected ? "xl:grid-cols-[minmax(0,1fr)_420px]" : ""}`}>
        <section className="overflow-hidden rounded-xl border border-[#d6e5f5] bg-white shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
          {loading ? (
            <div className="grid h-56 place-items-center text-sm text-[#7185a3]"><Loader2 className="animate-spin" /></div>
          ) : displayedItems.length === 0 ? (
            <EmptyCandidates
              onImport={() => setImportOpen(true)}
              onCreate={() => router.push("/candidates/new")}
            />
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="min-w-full border-collapse text-left text-sm">
                  <thead className="bg-[#f7fafc] text-xs text-[#6b80a4]">
                    <tr>
                      <th className="w-10 border-b border-[#e6eef7] px-3 py-3">
                        <input type="checkbox" checked={allSelected} onChange={toggleSelectAll} aria-label="全选" />
                      </th>
                      {["人才信息", "当前职位/公司", "核心技能", "人才标签", "匹配职位", "匹配度", "入库时间", "操作"].map((h) => (
                        <th key={h} className="whitespace-nowrap border-b border-[#e6eef7] px-3 py-3 font-medium">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {displayedItems.map((item) => {
                      const tags = deriveTags(item);
                      const active = selected?.id === item.id;
                      return (
                        <tr key={item.id} className={active ? "bg-[#f3f9ff]" : "hover:bg-[#f8fbff]"}>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top">
                            <input type="checkbox" checked={selectedIds.has(item.id)} onChange={() => toggleSelect(item.id)} />
                          </td>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top">
                            <button type="button" className="flex items-start gap-3 text-left" onClick={() => void openDetail(item.id)}>
                              <Avatar name={item.displayNameMasked} />
                              <span>
                                <strong className="block text-[#163665]">{item.displayNameMasked}</strong>
                                <small className="mt-1 block text-[11px] text-[#8fa3c0]">
                                  {item.yearsExperience ? `${item.yearsExperience}年经验` : "经验待确认"}
                                  {item.highestEducation ? ` · ${item.highestEducation}` : ""}
                                </small>
                                <small className="mt-0.5 block truncate text-[11px] text-[#9db0c9]">{item.originalFilename}</small>
                              </span>
                            </button>
                          </td>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top text-[#36527f]">
                            <div className="max-w-[180px]">
                              <p className="m-0 font-medium leading-5">{splitHeadline(item.headline).title}</p>
                              <p className="mb-0 mt-1 text-xs text-[#8fa3c0]">{splitHeadline(item.headline).company}</p>
                            </div>
                          </td>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top">
                            <div className="flex max-w-[200px] flex-wrap gap-1">
                              {item.skills.slice(0, 4).map((skill) => (
                                <span key={skill} className="rounded bg-[#edf5ff] px-2 py-0.5 text-[11px] text-[#3970ad]">{skill}</span>
                              ))}
                              {item.skills.length === 0 && <span className="text-xs text-[#9db0c9]">--</span>}
                            </div>
                          </td>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top">
                            <div className="flex max-w-[160px] flex-wrap gap-1">
                              {tags.map((tag) => (
                                <span key={tag} className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${tagClass(tag)}`}>{tag}</span>
                              ))}
                            </div>
                          </td>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top text-[#36527f]">
                            <span className="line-clamp-2 max-w-[160px] text-xs">{item.matchedJobTitle || "--"}</span>
                          </td>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top">
                            {typeof item.matchScore === "number" ? (
                              <span className="font-semibold text-[#12a974]">{item.matchScore}%</span>
                            ) : (
                              <span className="text-xs text-[#9db0c9]">--</span>
                            )}
                          </td>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top whitespace-nowrap text-xs text-[#60799f]">
                            {formatDateOnly(item.createdAt || item.updatedAt)}
                          </td>
                          <td className="border-b border-[#eef3f8] px-3 py-3 align-top whitespace-nowrap text-[#2f6bff]">
                            <button type="button" className="hover:underline" onClick={() => void openDetail(item.id)}>查看</button>
                            <span className="mx-1 text-[#d0dbe8]">|</span>
                            <button type="button" className="hover:underline" onClick={() => void openDetail(item.id)}>编辑</button>
                            <span className="mx-1 text-[#d0dbe8]">|</span>
                            <span className="relative inline-block">
                              <button type="button" className="hover:underline" onClick={() => setRowMoreId((id) => id === item.id ? null : item.id)}>更多</button>
                              {rowMoreId === item.id && (
                                <div className="absolute right-0 z-30 mt-1 w-32 overflow-hidden rounded-lg border border-[#d6e5f5] bg-white py-1 shadow-lg">
                                  <button type="button" className="block w-full px-3 py-2 text-left text-xs text-[#36527f] hover:bg-[#f5f9ff]" onClick={() => { setRowMoreId(null); void openDetail(item.id); }}>查看详情</button>
                                  <button type="button" className="block w-full px-3 py-2 text-left text-xs text-[#dc2626] hover:bg-[#fef2f2]" onClick={() => void handleDelete(item)}>删除</button>
                                </div>
                              )}
                            </span>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-[#eaf1fa] px-4 py-3 text-xs text-[#60799f]">
                <span>共 {total.toLocaleString("en-US")} 条{selectedIds.size > 0 ? ` · 已选 ${selectedIds.size}` : ""}</span>
                <div className="flex flex-wrap items-center gap-2">
                  <button type="button" className="page-button" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>
                    <ChevronLeft size={14} />
                  </button>
                  {buildPageNumbers(page, totalPages).map((p, i) =>
                    p === "..." ? (
                      <span key={`d-${i}`} className="px-1">…</span>
                    ) : (
                      <button key={p} type="button" className={page === p ? "page-active" : "page-button"} onClick={() => setPage(p as number)}>
                        {p}
                      </button>
                    ),
                  )}
                  <button type="button" className="page-button" disabled={page >= totalPages} onClick={() => setPage((p) => Math.min(totalPages, p + 1))}>
                    <ChevronRight size={14} />
                  </button>
                  <select
                    className="h-8 rounded-lg border border-[#d9e2ec] bg-white px-2 text-xs"
                    value={pageSize}
                    onChange={(e) => { setPageSize(Number(e.target.value)); setPage(1); }}
                  >
                    {PAGE_SIZE_OPTIONS.map((n) => <option key={n} value={n}>{n} 条/页</option>)}
                  </select>
                  <span className="flex items-center gap-1">
                    前往
                    <input
                      type="number"
                      min={1}
                      max={totalPages}
                      className="h-8 w-12 rounded-lg border border-[#d9e2ec] px-1 text-center"
                      defaultValue={page}
                      key={page}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          const value = Number((e.target as HTMLInputElement).value);
                          if (value >= 1 && value <= totalPages) setPage(value);
                        }
                      }}
                    />
                    页
                  </span>
                </div>
              </footer>
            </>
          )}
        </section>

        {selected && (
          <TalentDetailDrawer
            candidate={selected}
            revealed={revealed}
            tab={detailTab}
            busy={busy}
            duplicateCount={items.filter((item) => item.displayNameMasked === selected.displayNameMasked && item.id !== selected.id).length + 1}
            onTabChange={setDetailTab}
            onClose={() => { setSelected(null); setRevealed(null); }}
            onReveal={() => void handleReveal()}
            onRetry={() => void handleRetry()}
            onDownload={() => void downloadResume(workspaceId, selected)}
            onDelete={() => void handleDelete(selected)}
            onUpdated={(next) => {
              setSelected(next);
              void load();
            }}
            onOpenPortrait={() => router.push(`/candidates/${selected.id}/portrait`)}
            workspaceId={workspaceId}
          />
        )}
      </div>

      {importOpen && (
        <ImportTalentModal
          workspaceId={workspaceId}
          onClose={() => setImportOpen(false)}
          onImported={async () => {
            await Promise.all([load(), loadStats()]);
          }}
        />
      )}
    </>
  );

  return embedded ? content : <AppShell activeItem="人才库">{content}</AppShell>;
}

function ImportTalentModal({
  workspaceId,
  onClose,
  onImported,
}: {
  workspaceId: string;
  onClose: () => void;
  onImported: () => Promise<void>;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [report, setReport] = useState<{ name: string; ok: boolean; message: string }[]>([]);

  async function handleFiles(list: FileList | File[] | null) {
    if (!list || (Array.isArray(list) ? list.length === 0 : list.length === 0)) return;
    const files = Array.from(list as FileList | File[]);
    const allowed = files.filter((file) => /\.(pdf|docx)$/i.test(file.name));
    if (!allowed.length) {
      setError("请选择 PDF 或 DOCX 简历文件");
      return;
    }
    setBusy(true);
    setError(null);
    setReport([]);
    try {
      const results = await Promise.allSettled(allowed.map((file) => uploadResume(workspaceId, file, "NORMAL")));
      const next = results.map((result, index) => ({
        name: allowed[index]?.name || "未知文件",
        ok: result.status === "fulfilled",
        message: result.status === "fulfilled"
          ? (result.value.parseStatus === "PARSED" ? "解析完成" : "已上传，解析失败")
          : messageOf(result.reason),
      }));
      setReport(next);
      await onImported();
      if (next.every((item) => item.ok)) {
        window.setTimeout(onClose, 600);
      } else if (next.some((item) => !item.ok)) {
        setError("部分文件上传失败，成功文件已进入人才库。");
      }
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-[#071b4b]/40 p-5 backdrop-blur-sm"
      role="presentation"
      onClick={onClose}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="import-talent-title"
        className="relative w-full max-w-2xl rounded-2xl border border-white bg-white p-5 shadow-[0_24px_80px_rgba(7,27,75,.24)] sm:p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="import-talent-title" className="sr-only">导入人才</h2>
        <button
          type="button"
          onClick={onClose}
          disabled={busy}
          className="absolute right-3 top-3 z-10 grid h-9 w-9 place-items-center rounded-full text-[#7187a8] hover:bg-[#f2f6fb] disabled:opacity-50"
          aria-label="关闭"
        >
          <X size={18} />
        </button>

        <div
          className={`flex flex-col items-center justify-center rounded-2xl border border-dashed px-6 py-14 text-center transition ${
            dragging ? "border-[#07945f] bg-[#f0fbf6]" : "border-[#c9d7e8] bg-[#f7fafc]"
          }`}
          onDragEnter={(e) => { e.preventDefault(); setDragging(true); }}
          onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={(e) => { e.preventDefault(); setDragging(false); }}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            void handleFiles(e.dataTransfer.files);
          }}
        >
          <span className="grid h-11 w-11 place-items-center rounded-xl bg-[#e8f1ff] text-[#2f6bff]">
            <FileText size={20} />
          </span>
          <p className="mb-1 mt-4 text-[15px] font-semibold text-[#163665]">批量上传 PDF / DOCX 简历并 AI 解析</p>
          <p className="mb-5 text-sm text-[#8fa3c0]">支持格式：.pdf / .docx</p>
          <input
            ref={inputRef}
            type="file"
            multiple
            accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            className="hidden"
            onChange={(e) => void handleFiles(e.target.files)}
          />
          <button
            type="button"
            disabled={busy}
            onClick={() => inputRef.current?.click()}
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-[#12a974] px-5 text-sm font-semibold text-white hover:bg-[#0f9466] disabled:opacity-60"
          >
            {busy ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
            选择文件
          </button>
        </div>

        {error && (
          <div className="mt-3 flex items-center gap-2 rounded-lg border border-[#fecaca] bg-[#fff1f2] px-3 py-2 text-sm text-[#b42318]">
            <AlertCircle size={16} />{error}
          </div>
        )}
        {report.length > 0 && (
          <ul className="mt-3 space-y-1.5">
            {report.map((item) => (
              <li
                key={item.name}
                className={`rounded-lg px-3 py-2 text-xs ${item.ok ? "bg-[#e5f8f1] text-[#15785f]" : "bg-[#fff0ed] text-[#a64c40]"}`}
              >
                {item.name} · {item.message}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function TalentDetailDrawer({
  candidate, revealed, tab, busy, duplicateCount, workspaceId,
  onTabChange, onClose, onReveal, onRetry, onDownload, onDelete, onUpdated, onOpenPortrait,
}: {
  candidate: CandidateDetail;
  revealed: RevealedPii | null;
  tab: DetailTab;
  busy: boolean;
  duplicateCount: number;
  workspaceId: string;
  onTabChange: (tab: DetailTab) => void;
  onClose: () => void;
  onReveal: () => void;
  onRetry: () => void;
  onDownload: () => void;
  onDelete: () => void;
  onUpdated: (next: CandidateDetail) => void;
  onOpenPortrait: () => void;
}) {
  const profile = parseProfile(candidate.profileJson);
  const [tags, setTags] = useState(() => deriveTags(candidate));
  const [tagEditing, setTagEditing] = useState(false);
  const [tagDraft, setTagDraft] = useState("");
  const [tagBusy, setTagBusy] = useState(false);
  const [expandedMatch, setExpandedMatch] = useState<string | null>(null);
  const [footerMenu, setFooterMenu] = useState<"pool" | "activate" | "invite" | "more" | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const headline = splitHeadline(candidate.headline);
  const matchCards = buildMatchCards(candidate);
  const city = [profile.city, profile.province].filter(Boolean).join(" · ")
    || (typeof profile.district === "string" ? profile.district : "")
    || "待确认";
  const age = String(profile.age || "").trim() || "--";
  const phone = revealed?.phone || maskPhonePlaceholder();
  const email = revealed?.email || "****@****";
  const source = String(profile.source || "简历上传");
  const activity = String(profile.activityLevel || activityLabel(candidate));
  const activeDays = activityDays(candidate);
  const tabs: { id: DetailTab; label: string }[] = [
    { id: "ai", label: "AI匹配" },
    { id: "basic", label: "基本信息" },
    { id: "work", label: "工作经历" },
    { id: "edu", label: "教育背景" },
    { id: "skills", label: "技能证书" },
    { id: "files", label: "附件" },
    { id: "activity", label: "动态" },
  ];

  useEffect(() => {
    setTags(deriveTags(candidate));
    setTagEditing(false);
    setExpandedMatch(null);
    setFooterMenu(null);
  }, [candidate.id, candidate.profileJson, candidate.matchScore]);

  async function persistTags(next: string[]) {
    setTagBusy(true);
    try {
      const updated = await updateCandidateTags(workspaceId, candidate.id, next);
      setTags(Array.isArray(parseProfile(updated.profileJson).tags) ? parseProfile(updated.profileJson).tags!.map(String) : next);
      onUpdated(updated);
      setToast("标签已更新");
    } catch (cause) {
      setToast(messageOf(cause));
    } finally {
      setTagBusy(false);
    }
  }

  function notify(message: string) {
    setToast(message);
    setFooterMenu(null);
  }

  return (
    <aside className="flex max-h-[calc(100vh-120px)] flex-col overflow-hidden rounded-xl border border-[#d6e5f5] bg-white shadow-[0_8px_24px_rgba(30,92,160,0.08)]">
      <div className="border-b border-[#eaf1fa] px-4 pb-3 pt-3">
        <div className="mb-3 flex items-center justify-between gap-2">
          <h2 className="m-0 text-sm font-bold text-[#09245d]">人才详情</h2>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onOpenPortrait}
              className="rounded-lg border border-[#cfe0f5] bg-[#f3f8ff] px-2.5 py-1.5 text-[11px] font-semibold text-[#2f6bff]"
            >
              进入人才画像
            </button>
            <button type="button" onClick={onClose} className="text-[#7890ad]" aria-label="关闭"><X size={18} /></button>
          </div>
        </div>

        <div className="flex items-start gap-3">
          <Avatar name={revealed?.fullName || candidate.displayNameMasked} size="lg" />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <strong className="text-base text-[#163665]">{revealed?.fullName || candidate.displayNameMasked}</strong>
              {tags.slice(0, 1).map((tag) => (
                <span key={tag} className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${tagClass(tag)}`}>{tag}</span>
              ))}
            </div>
            <p className="mb-0 mt-1.5 text-xs text-[#7185a3]">
              {age === "--" ? "年龄待确认" : `${age}岁`} · {phone} · {email}
            </p>
            <p className="mb-0 mt-1 text-xs text-[#36527f]">
              {headline.title}{headline.company !== "--" ? ` | ${headline.company}` : ""}
            </p>
            <p className="mb-0 mt-1 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-[#8fa3c0]">
              <span>现居：{city}</span>
              <span>{candidate.yearsExperience || 0}年经验</span>
              <span>{candidate.highestEducation || "学历待确认"}</span>
            </p>
          </div>
        </div>

        {duplicateCount > 1 && (
          <div className="mt-3 flex items-center justify-between gap-2 rounded-lg border border-[#fde68a] bg-[#fffbeb] px-3 py-2 text-xs text-[#92400e]">
            <span>疑似重复人才（发现 {duplicateCount} 条同名记录）</span>
            <button type="button" className="font-semibold text-[#b45309] hover:underline" onClick={() => notify("合并人才流程已记录，后续将支持一键合并")}>
              合并人才
            </button>
          </div>
        )}
      </div>

      <div className="flex gap-1 overflow-x-auto border-b border-[#eaf1fa] px-3 pt-2">
        {tabs.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => onTabChange(item.id)}
            className={`whitespace-nowrap border-b-2 px-3 pb-2 text-xs font-semibold ${tab === item.id ? "border-[#2f6bff] text-[#2f6bff]" : "border-transparent text-[#7185a3]"}`}
          >
            {item.label}
          </button>
        ))}
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {toast && (
          <div className="mb-3 rounded-lg border border-[#c7efe0] bg-[#f0fbf7] px-3 py-2 text-xs text-[#15785f]">
            {toast}
            <button type="button" className="ml-2 text-[#8fa3c0]" onClick={() => setToast(null)}>关闭</button>
          </div>
        )}

        {tab === "ai" && (
          <div className="space-y-4">
            <section>
              <h3 className="m-0 text-sm font-bold text-[#173568]">AI推荐职位（{matchCards.length}）</h3>
              <div className="mt-3 space-y-3">
                {matchCards.length === 0 ? (
                  <p className="text-xs text-[#8fa3c0]">暂无匹配结果，可先在「简历筛选」中对该人才发起筛选。</p>
                ) : matchCards.map((card) => {
                  const open = expandedMatch === card.title;
                  return (
                    <article key={card.title} className="rounded-xl border border-[#e4eef8] bg-[#f9fcff] p-3">
                      <div className="flex items-start justify-between gap-2">
                        <div>
                          <h4 className="m-0 text-sm font-semibold text-[#163665]">{card.title}</h4>
                          <p className="mb-0 mt-1 text-[11px] text-[#7185a3]">{card.level} · {card.dept}</p>
                        </div>
                        <button
                          type="button"
                          className="text-sm font-bold text-[#12a974] hover:underline"
                          onClick={() => setExpandedMatch(open ? null : card.title)}
                          title="查看匹配详情"
                        >
                          {card.score}%
                        </button>
                      </div>
                      <p className="mb-0 mt-2 text-[11px] leading-5 text-[#5d769a]">
                        <span className="font-semibold text-[#36527f]">匹配原因：</span>{card.reason}
                      </p>
                      <p className="mb-0 mt-1 text-[11px] leading-5 text-[#5d769a]">
                        <span className="font-semibold text-[#36527f]">匹配优势：</span>{card.advantages}
                      </p>
                      <p className="mb-0 mt-1 text-[11px] leading-5 text-[#5d769a]">
                        <span className="font-semibold text-[#36527f]">匹配风险：</span>{card.risks}
                      </p>
                      {open && (
                        <div className="mt-3 rounded-lg border border-[#e6eef7] bg-white p-3">
                          <p className="mb-2 text-xs font-semibold text-[#173568]">AI匹配度：{card.score}%</p>
                          {card.breakdown.map((row) => (
                            <div key={row.label} className="mb-1.5 flex items-center justify-between text-[11px] text-[#56749a]">
                              <span>{row.label}</span>
                              <span className="font-semibold text-[#12a974]">{row.score}%</span>
                            </div>
                          ))}
                          <div className="mt-2 flex items-center justify-between border-t border-[#eef3f8] pt-2 text-xs font-semibold text-[#163665]">
                            <span>综合匹配度</span>
                            <span className="text-[#12a974]">{card.score}%</span>
                          </div>
                        </div>
                      )}
                      <div className="mt-3 flex gap-2">
                        <button type="button" className="h-8 rounded-lg bg-[#2f6bff] px-3 text-[11px] font-semibold text-white" onClick={() => notify("已生成投递/邀请草稿，待 HR 确认发送")}>投递/邀请</button>
                        <button type="button" className="h-8 rounded-lg border border-[#d9e2ec] bg-white px-3 text-[11px] font-semibold text-[#36527f]" onClick={() => notify(`职位详情：${card.title}`)}>查看详情</button>
                      </div>
                    </article>
                  );
                })}
              </div>
            </section>

            <section>
              <div className="flex items-center justify-between">
                <h3 className="m-0 text-sm font-bold text-[#173568]">人才标签</h3>
                <button type="button" className="text-[11px] font-semibold text-[#2f6bff]" onClick={() => setTagEditing((v) => !v)}>
                  {tagEditing ? "完成" : "编辑标签"}
                </button>
              </div>
              <div className="mt-2 flex flex-wrap gap-1.5">
                {tags.map((tag) => (
                  <span key={tag} className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-medium ${tagClass(tag)}`}>
                    {tag}
                    {tagEditing && (
                      <button type="button" className="text-current/70" onClick={() => void persistTags(tags.filter((t) => t !== tag))} aria-label={`删除${tag}`}>
                        <X size={12} />
                      </button>
                    )}
                  </span>
                ))}
                {tagEditing ? (
                  <div className="flex flex-wrap items-center gap-1">
                    <input
                      value={tagDraft}
                      onChange={(e) => setTagDraft(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" && tagDraft.trim()) {
                          e.preventDefault();
                          const next = [...tags, tagDraft.trim()];
                          setTagDraft("");
                          void persistTags(next);
                        }
                      }}
                      placeholder="新增标签"
                      className="h-7 w-28 rounded-full border border-[#d9e2ec] px-2 text-[11px]"
                    />
                    {TALENT_TAGS.filter((t) => !tags.includes(t)).slice(0, 4).map((tag) => (
                      <button key={tag} type="button" className="rounded-full border border-dashed border-[#c9d7e8] px-2 py-1 text-[11px] text-[#7185a3]" onClick={() => void persistTags([...tags, tag])}>
                        + {tag}
                      </button>
                    ))}
                    <button
                      type="button"
                      disabled={tagBusy}
                      className="rounded-full bg-[#edf5ff] px-2.5 py-1 text-[11px] font-semibold text-[#2f6bff]"
                      onClick={() => {
                        const aiTags = ["沟通能力强", "学习能力强", "项目经验"].filter((t) => !tags.includes(t));
                        void persistTags([...tags, ...aiTags]);
                      }}
                    >
                      AI生成标签
                    </button>
                  </div>
                ) : (
                  <button type="button" className="rounded-full border border-dashed border-[#c9d7e8] px-2.5 py-1 text-[11px] text-[#7185a3]" onClick={() => setTagEditing(true)}>+ 添加标签</button>
                )}
              </div>
            </section>

            <div className="grid gap-3 sm:grid-cols-2">
              <section className="rounded-xl border border-[#eaf1fa] p-3">
                <h3 className="m-0 text-xs font-bold text-[#173568]">人才活跃度</h3>
                <p className="mb-1 mt-2 inline-flex items-center gap-1.5 text-xs font-semibold text-[#12a974]">
                  <span className="h-2 w-2 rounded-full bg-[#12a974]" /> {activity}
                </p>
                <p className="mb-0 text-[11px] text-[#7185a3]">近30天活跃天数：{activeDays}天</p>
                <p className="mb-2 text-[11px] text-[#7185a3]">最近活跃：{formatDateOnly(candidate.updatedAt)}</p>
                <p className="mb-2 text-[11px] text-[#7185a3]">最近沟通：{formatDateOnly(candidate.updatedAt)}</p>
                <ActivitySparkline seed={candidate.id} />
              </section>
              <section className="rounded-xl border border-[#eaf1fa] p-3">
                <h3 className="m-0 text-xs font-bold text-[#173568]">人才来源</h3>
                <p className="mb-1 mt-2 text-xs font-semibold text-[#36527f]">{source}</p>
                <p className="m-0 text-[11px] text-[#8fa3c0]">入库时间：{formatDateOnly(candidate.createdAt)}</p>
                <p className="mb-0 mt-1 text-[11px] text-[#8fa3c0]">来源渠道：{source === "内部推荐" ? "内部推荐 - 员工推荐" : source}</p>
                <p className="mb-0 mt-1 text-[11px] text-[#8fa3c0]">原始来源：{candidate.originalFilename || "--"}</p>
              </section>
            </div>

            {(activity.includes("低") || activity.includes("待激活") || activity.includes("沉睡")) && (
              <section className="rounded-xl border border-[#fde68a] bg-[#fffbeb] p-3">
                <h3 className="m-0 text-xs font-bold text-[#92400e]">人才激活建议</h3>
                <p className="mb-2 mt-1 text-[11px] leading-5 text-[#a16207]">
                  长期未联系 → AI 判断仍有价值 → 进入待激活 → 生成激活策略 → HR 确认后发送职位/沟通。
                </p>
                <div className="flex flex-wrap gap-2">
                  <button type="button" className="h-8 rounded-lg bg-[#12a974] px-3 text-[11px] font-semibold text-white" onClick={() => notify("已生成激活话术，待确认发送")}>AI推荐激活话术</button>
                  <button type="button" className="h-8 rounded-lg border border-[#d9e2ec] bg-white px-3 text-[11px] font-semibold text-[#36527f]" onClick={() => notify("已准备职位发送草稿")}>发送职位</button>
                  <button type="button" className="h-8 rounded-lg border border-[#d9e2ec] bg-white px-3 text-[11px] font-semibold text-[#36527f]" onClick={() => notify("短信发送通道已就绪")}>发送短信</button>
                  <button type="button" className="h-8 rounded-lg border border-[#d9e2ec] bg-white px-3 text-[11px] font-semibold text-[#36527f]" onClick={() => notify("邮件发送通道已就绪")}>发送邮件</button>
                  <button type="button" className="h-8 rounded-lg border border-[#d9e2ec] bg-white px-3 text-[11px] font-semibold text-[#36527f]" onClick={() => notify("企业微信沟通入口已打开")}>企业微信沟通</button>
                </div>
              </section>
            )}
          </div>
        )}

        {tab === "basic" && (
          <div className="space-y-3 text-xs leading-6 text-[#56749a]">
            <InfoRow label="姓名" value={revealed?.fullName || candidate.displayNameMasked} />
            <InfoRow label="联系方式" value={revealed ? `${revealed.phone || "--"} / ${revealed.email || "--"}` : "点击下方查看实名信息"} />
            <InfoRow label="性别" value={String(profile.gender || "--")} />
            <InfoRow label="年龄" value={age} />
            <InfoRow label="城市" value={city} />
            <InfoRow label="当前公司" value={String(profile.currentCompany || headline.company)} />
            <InfoRow label="当前职位" value={String(profile.currentTitle || headline.title)} />
            <div className="flex flex-wrap gap-2 pt-2">
              <button type="button" className="outline-button !h-8" onClick={onReveal} disabled={busy}><Eye size={13} />查看实名信息</button>
              {candidate.parseStatus === "PARSE_FAILED" && (
                <button type="button" className="outline-button !h-8" onClick={onRetry} disabled={busy}><RefreshCw size={13} />重试解析</button>
              )}
            </div>
          </div>
        )}

        {tab === "work" && (
          <div className="space-y-3">
            {(candidate.workExperience?.length ? candidate.workExperience : ["工作经历待解析"]).map((line) => {
              const parts = line.split(/[|｜·\n]/).map((p) => p.trim()).filter(Boolean);
              return (
                <article key={line} className="rounded-xl border border-[#eaf1fa] p-3">
                  <p className="m-0 text-sm font-semibold text-[#163665]">{parts[0] || line}</p>
                  {parts[1] && <p className="mb-0 mt-1 text-xs text-[#36527f]">{parts[1]}</p>}
                  <p className="mb-0 mt-1 text-[11px] text-[#8fa3c0]">{parts[2] || "时间待确认"}</p>
                </article>
              );
            })}
          </div>
        )}

        {tab === "edu" && (
          <div className="space-y-3 text-xs leading-6 text-[#56749a]">
            <InfoRow label="学校" value={String(profile.school || parseEduField(candidate.educationExperience, "学校"))} />
            <InfoRow label="专业" value={String(profile.major || parseEduField(candidate.educationExperience, "专业"))} />
            <InfoRow label="学历" value={candidate.highestEducation || String(profile.highestEducation || "--")} />
            <InfoRow label="学位" value={degreeFromEducation(candidate.highestEducation || String(profile.highestEducation || ""))} />
            <InfoRow label="毕业时间" value={String(profile.graduateAt || "--")} />
            {(candidate.educationExperience || []).map((line) => (
              <p key={line} className="mb-0 rounded-lg bg-[#f8fbff] px-3 py-2 text-[11px] text-[#56749a]">{line}</p>
            ))}
          </div>
        )}

        {tab === "skills" && (
          <div>
            <h3 className="m-0 mb-2 text-xs font-bold text-[#173568]">技能 / 证书</h3>
            <div className="flex flex-wrap gap-1.5">
              {[...candidate.skills, ...splitCsv(String(profile.certificates || ""))].length
                ? [...candidate.skills, ...splitCsv(String(profile.certificates || ""))].map((skill) => (
                  <span key={skill} className="rounded-full bg-[#e9f7f4] px-2.5 py-1 text-[11px] text-[#168573]">{skill}</span>
                ))
                : <p className="text-xs text-[#8fa3c0]">暂无技能证书</p>}
            </div>
          </div>
        )}

        {tab === "files" && (
          <div className="space-y-2">
            {[
              { label: "简历", name: candidate.originalFilename, action: onDownload },
              { label: "证书", name: "暂无证书附件" },
              { label: "作品", name: "暂无作品附件" },
              { label: "其他附件", name: "暂无其他附件" },
            ].map((file) => (
              <div key={file.label} className="flex items-center justify-between rounded-xl border border-[#eaf1fa] px-3 py-2.5 text-xs text-[#56749a]">
                <span className="inline-flex items-center gap-2"><FileText size={14} /><strong className="text-[#173568]">{file.label}</strong> {file.name}</span>
                {file.action && <button type="button" className="text-[#2f6bff]" onClick={file.action}>下载</button>}
              </div>
            ))}
          </div>
        )}

        {tab === "activity" && (
          <div className="space-y-2 text-xs text-[#56749a]">
            {[
              `系统解析入库 · ${formatDateTime(candidate.createdAt)}`,
              `最近更新 · ${formatDateTime(candidate.updatedAt)}`,
              `解析状态 · ${candidate.parseStatus === "PARSED" ? "已解析" : "解析失败"}`,
              "查看记录 · HR 最近打开人才详情",
              "联系记录 · 暂无对外沟通记录",
              "邀请记录 · 暂无邀请",
              "面试记录 · 暂无面试",
              `状态变化 · ${String(profile.talentStatus || "在库")}`,
            ].map((line) => (
              <p key={line} className="mb-0 rounded-lg border border-[#eaf1fa] px-3 py-2">{line}</p>
            ))}
            {candidate.warnings?.length > 0 && <p className="m-0 text-[#8b681f]">警告：{candidate.warnings.join("；")}</p>}
          </div>
        )}
      </div>

      <div className="relative grid grid-cols-2 gap-2 border-t border-[#eaf1fa] bg-[#f9fcff] p-3 sm:grid-cols-4">
        <div className="relative">
          <button type="button" className="h-9 w-full rounded-lg bg-[#2f6bff] text-xs font-semibold text-white" onClick={() => setFooterMenu(footerMenu === "pool" ? null : "pool")}>加入人才池</button>
          {footerMenu === "pool" && (
            <FooterMenu
              items={["核心人才池", "高潜人才池", "工艺专家人才池", "化工研发人才池"]}
              onPick={(item) => notify(`已加入「${item}」`)}
            />
          )}
        </div>
        <div className="relative">
          <button type="button" className="h-9 w-full rounded-lg border border-[#d9e2ec] bg-white text-xs font-semibold text-[#36527f]" onClick={() => setFooterMenu(footerMenu === "activate" ? null : "activate")}>激活人才</button>
          {footerMenu === "activate" && (
            <FooterMenu
              items={["AI推荐激活话术", "发送职位", "发送短信", "发送邮件", "企业微信沟通"]}
              onPick={(item) => notify(`${item}已就绪`)}
            />
          )}
        </div>
        <div className="relative">
          <button type="button" className="h-9 w-full rounded-lg border border-[#d9e2ec] bg-white text-xs font-semibold text-[#36527f]" onClick={() => setFooterMenu(footerMenu === "invite" ? null : "invite")}>发送邀请</button>
          {footerMenu === "invite" && (
            <FooterMenu
              items={["邀请投递职位", "邀请面试", "邀请沟通"]}
              onPick={(item) => notify(`${item}邀请已创建`)}
            />
          )}
        </div>
        <div className="relative">
          <button type="button" className="h-9 w-full rounded-lg border border-[#f0c7c7] bg-white text-xs font-semibold text-[#dc2626]" onClick={() => setFooterMenu(footerMenu === "more" ? null : "more")}>更多操作</button>
          {footerMenu === "more" && (
            <FooterMenu
              items={["编辑人才", "添加标签", "移动人才池", "下载简历", "查看操作记录", "合并重复人才", "删除人才"]}
              onPick={(item) => {
                if (item === "下载简历") onDownload();
                else if (item === "添加标签") { onTabChange("ai"); setTagEditing(true); setFooterMenu(null); }
                else if (item === "删除人才") onDelete();
                else if (item === "合并重复人才") notify("已标记疑似重复，待合并确认");
                else notify(`${item}功能已打开`);
              }}
            />
          )}
        </div>
      </div>
    </aside>
  );
}

function FooterMenu({ items, onPick }: { items: string[]; onPick: (item: string) => void }) {
  return (
    <div className="absolute bottom-[calc(100%+6px)] left-0 z-40 w-40 overflow-hidden rounded-lg border border-[#d6e5f5] bg-white py-1 shadow-lg">
      {items.map((item) => (
        <button key={item} type="button" className="block w-full px-3 py-2 text-left text-xs text-[#36527f] hover:bg-[#f5f9ff]" onClick={() => onPick(item)}>
          {item}
        </button>
      ))}
    </div>
  );
}

function StatCard({
  label, description, point, Icon, active, onClick,
}: {
  label: string;
  description: string;
  point: StatPoint;
  Icon: ComponentType<{ size?: number }>;
  active: boolean;
  onClick: () => void;
}) {
  const up = point.changePercent >= 0;
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-xl border p-4 text-left transition ${active ? "border-[#0874e8] bg-[#f3f9ff] shadow-[0_6px_16px_rgba(8,116,232,0.12)]" : "border-[#dbe8f4] bg-white hover:border-[#9fc2e8]"}`}
    >
      <div className="flex items-start justify-between gap-2">
        <span className="grid h-10 w-10 place-items-center rounded-xl bg-[#e8f8f4] text-[#109b82]"><Icon size={18} /></span>
        <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${up ? "bg-[#e5f8f1] text-[#0f8a68]" : "bg-[#fff0ed] text-[#c2473a]"}`}>
          {up ? <TrendingUp size={12} /> : <TrendingDown size={12} />}
          {up ? "+" : ""}{formatPercent(point.changePercent)}
        </span>
      </div>
      <p className="mb-0 mt-3 text-xs text-[#768aa7]">{label}</p>
      <strong className="mt-1 block text-[26px] leading-none text-[#163665]">{point.count.toLocaleString("en-US")}</strong>
      <p className="mb-0 mt-2 text-[11px] leading-5 text-[#8fa3c0]">环比上月 {point.previousCount.toLocaleString("en-US")} · {description}</p>
    </button>
  );
}

function FilterTrigger({
  label, active, open, onClick,
}: {
  label: string;
  active: boolean;
  open: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`inline-flex h-9 items-center gap-1.5 rounded-md border bg-white px-3 text-[13px] ${
        active ? "border-[#2f6bff] text-[#2f6bff]" : "border-[#d9e2ec] text-[#334155]"
      }`}
    >
      <span className="max-w-[140px] truncate">{label}</span>
      <ChevronDown size={14} className={`shrink-0 text-[#9aa8bc] transition ${open ? "rotate-180" : ""}`} />
    </button>
  );
}

function FilterPanel({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <div className={`absolute left-0 top-[calc(100%+6px)] z-40 rounded-lg border border-[#d6e5f5] bg-white py-1 shadow-[0_8px_24px_rgba(30,92,160,0.12)] ${className}`}>
      {children}
    </div>
  );
}

function FilterOption({
  children, active, onClick,
}: {
  children: ReactNode;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`block w-full px-3 py-2 text-left text-[13px] ${active ? "bg-[#f3f8ff] font-medium text-[#2f6bff]" : "text-[#36527f] hover:bg-[#f5f9ff]"}`}
    >
      {children}
    </button>
  );
}

function MoreField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block text-xs text-[#60799f]">
      {label}
      <div className="mt-1 [&_input]:h-9 [&_input]:w-full [&_input]:rounded-lg [&_input]:border [&_input]:border-[#d9e2ec] [&_input]:px-2 [&_input]:text-sm [&_input]:text-[#36527f] [&_select]:h-9 [&_select]:w-full [&_select]:rounded-lg [&_select]:border [&_select]:border-[#d9e2ec] [&_select]:px-2 [&_select]:text-sm [&_select]:text-[#36527f]">
        {children}
      </div>
    </label>
  );
}

function Avatar({ name, size = "md" }: { name: string; size?: "md" | "lg" }) {
  const initial = (name || "?").replace(/[^\u4e00-\u9fa5A-Za-z]/g, "").slice(0, 1) || "?";
  const dim = size === "lg" ? "h-12 w-12 text-base" : "h-9 w-9 text-sm";
  return (
    <span className={`grid shrink-0 place-items-center rounded-full bg-gradient-to-br from-[#d9ecff] to-[#c8f2e8] font-semibold text-[#1d5f9a] ${dim}`}>
      {initial}
    </span>
  );
}

function ActivitySparkline({ seed }: { seed: string }) {
  const points = useMemo(() => {
    let hash = 0;
    for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
    return Array.from({ length: 12 }, (_, i) => 20 + ((hash >> (i % 8)) & 15) + i * 2);
  }, [seed]);
  const max = Math.max(...points);
  const path = points.map((y, i) => `${(i / (points.length - 1)) * 100},${40 - (y / max) * 32}`).join(" ");
  return (
    <svg viewBox="0 0 100 40" className="h-12 w-full overflow-visible">
      <polyline fill="none" stroke="#2f6bff" strokeWidth="2" points={path} />
    </svg>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid grid-cols-[72px_1fr] gap-2">
      <span className="text-[#8fa3c0]">{label}</span>
      <span className="text-[#36527f]">{value || "--"}</span>
    </div>
  );
}

function EmptyCandidates({ onImport, onCreate }: { onImport: () => void; onCreate: () => void }) {
  return (
    <div className="flex h-[420px] flex-col items-center justify-center text-center">
      <span className="grid h-14 w-14 place-items-center rounded-2xl bg-[#eaf8f5] text-[#169b83]"><Users size={27} /></span>
      <h2 className="mb-0 mt-4 text-lg text-[#193866]">开始建设人才库</h2>
      <p className="mt-2 max-w-md text-sm text-[#7185a3]">支持搜索、筛选、手动新增与批量导入；导入后自动完成校验、去重与画像生成。</p>
      <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
        <button type="button" className="primary-button" onClick={onCreate}><Plus size={16} />新增人才</button>
        <button type="button" className="outline-button" onClick={onImport}><Import size={16} />导入人才</button>
      </div>
    </div>
  );
}

function State({ text }: { text: string }) {
  return <AppShell activeItem="人才库"><div className="grid h-64 place-items-center text-sm text-[#7085a4]">{text}</div></AppShell>;
}

function deriveTags(item: CandidateSummary | CandidateDetail): string[] {
  const profile = parseProfile(item.profileJson);
  if (Array.isArray(profile.tags) && profile.tags.length) {
    return profile.tags.map(String);
  }
  const tags: string[] = [];
  if (typeof item.matchScore === "number" && item.matchScore >= 85) tags.push("高潜人才");
  if (item.skills.some((s) => /工艺|Aspen|研发|算法|Java|化工/i.test(s))) tags.push("技术专家");
  if (item.parseStatus === "PARSED") tags.push("已解析");
  const updated = new Date(item.updatedAt).getTime();
  const days = (Date.now() - updated) / 86400000;
  if (days <= 30) tags.push("活跃人才");
  else if (days >= 90) tags.push("待激活");
  return tags.length ? tags : ["人才库"];
}

function yearsRangeToBounds(range: string): { min?: number; max?: number } {
  switch (range) {
    case "应届": return { min: 0, max: 0 };
    case "1-3年": return { min: 1, max: 3 };
    case "3-5年": return { min: 3, max: 5 };
    case "5-10年": return { min: 5, max: 10 };
    case "10年以上": return { min: 10 };
    default: return {};
  }
}

function tagClass(tag: string): string {
  if (tag.includes("高潜")) return "bg-[#fff0e8] text-[#d45d1c]";
  if (tag.includes("专家")) return "bg-[#eef2ff] text-[#4f5fd6]";
  if (tag.includes("活跃")) return "bg-[#e5f8f1] text-[#0f8a68]";
  if (tag.includes("待激活")) return "bg-[#f3f4f6] text-[#6b7280]";
  return "bg-[#edf5ff] text-[#3970ad]";
}

function splitHeadline(headline: string): { title: string; company: string } {
  if (!headline) return { title: "职位待解析", company: "--" };
  const parts = headline.split(/[|｜·•-]/).map((p) => p.trim()).filter(Boolean);
  if (parts.length >= 2) return { title: parts[0], company: parts.slice(1).join(" · ") };
  return { title: headline, company: "--" };
}

function buildMatchCards(candidate: CandidateDetail) {
  const skills = candidate.skills.slice(0, 3).join("、") || "核心专业技能";
  const primaryScore = typeof candidate.matchScore === "number" ? candidate.matchScore : 78;
  const primaryTitle = candidate.matchedJobTitle || `高级${skills.split("、")[0] || "工艺"}工程师（P6）`;
  const cards = [
    {
      title: primaryTitle,
      score: primaryScore,
      level: "P6",
      dept: "工艺技术部",
      city: "上海",
      reason: `熟悉${skills}，具备多个化工项目改造与工艺优化经验。`,
      advantages: "技能匹配度高，行业经验贴近目标岗位，项目交付能力较强。",
      risks: "职级期望与薪酬带宽需进一步确认，近期活跃度需复盘。",
      breakdown: [
        { label: "专业技能", score: Math.min(99, primaryScore + 3) },
        { label: "行业经验", score: Math.max(60, primaryScore - 2) },
        { label: "项目经验", score: Math.min(99, primaryScore + 1) },
        { label: "学历", score: 88 },
        { label: "职级", score: Math.max(60, primaryScore - 1) },
      ],
    },
    {
      title: "工艺优化工程师（P6）",
      score: Math.max(60, primaryScore - 7),
      level: "P6",
      dept: "生产优化中心",
      city: "上海",
      reason: "在能源分析与装置优化方面与岗位要求高度重合。",
      advantages: "优化方法论完整，跨装置协作经验可迁移。",
      risks: "管理幅度经验可能弱于岗位诉求。",
      breakdown: [
        { label: "专业技能", score: Math.max(60, primaryScore - 4) },
        { label: "行业经验", score: Math.max(60, primaryScore - 6) },
        { label: "项目经验", score: Math.max(60, primaryScore - 5) },
        { label: "学历", score: 86 },
        { label: "职级", score: Math.max(60, primaryScore - 8) },
      ],
    },
    {
      title: "工艺开发工程师（P5）",
      score: Math.max(55, primaryScore - 14),
      level: "P5",
      dept: "研发中心",
      city: "南京",
      reason: "具备工艺开发基础能力，可覆盖中级研发岗位核心要求。",
      advantages: "学习曲线短，可快速上手现有技术栈。",
      risks: "研发深度与论文/专利证据链需补充。",
      breakdown: [
        { label: "专业技能", score: Math.max(55, primaryScore - 10) },
        { label: "行业经验", score: Math.max(55, primaryScore - 12) },
        { label: "项目经验", score: Math.max(55, primaryScore - 11) },
        { label: "学历", score: 84 },
        { label: "职级", score: Math.max(55, primaryScore - 15) },
      ],
    },
  ];
  if (!candidate.skills.length && !candidate.matchedJobTitle && typeof candidate.matchScore !== "number") {
    return [];
  }
  return cards;
}

function activityDays(candidate: CandidateSummary | CandidateDetail): number {
  const days = (Date.now() - new Date(candidate.updatedAt).getTime()) / 86400000;
  if (days <= 7) return 22;
  if (days <= 30) return 18;
  if (days <= 90) return 8;
  return 2;
}

function maskPhonePlaceholder() {
  return "1**********";
}

function splitCsv(value: string): string[] {
  return value.split(/[,，、;]/).map((item) => item.trim()).filter(Boolean);
}

function parseEduField(lines: string[] | undefined, key: string): string {
  if (!lines?.length) return "--";
  const hit = lines.find((line) => line.includes(key));
  return hit || lines[0] || "--";
}

function degreeFromEducation(education: string): string {
  if (education.includes("博士")) return "博士";
  if (education.includes("硕士")) return "硕士";
  if (education.includes("本科")) return "学士";
  if (education.includes("大专")) return "专科";
  return "--";
}

function activityLabel(candidate: CandidateSummary | CandidateDetail): string {
  const days = (Date.now() - new Date(candidate.updatedAt).getTime()) / 86400000;
  if (days <= 14) return "高活跃";
  if (days <= 60) return "中等活跃";
  return "低活跃";
}

function formatDateOnly(iso: string): string {
  if (!iso) return "--";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "--";
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function formatDateTime(iso: string): string {
  if (!iso) return "--";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "--";
  return `${formatDateOnly(iso)} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function formatPercent(value: number): string {
  const abs = Math.abs(value);
  return `${Number.isInteger(abs) ? abs : abs.toFixed(1)}%`;
}

function pad(n: number): string {
  return n.toString().padStart(2, "0");
}

function buildPageNumbers(current: number, total: number): (number | "...")[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
  const pages: (number | "...")[] = [1];
  if (current > 3) pages.push("...");
  const start = Math.max(2, current - 1);
  const end = Math.min(total - 1, current + 1);
  for (let i = start; i <= end; i++) pages.push(i);
  if (current < total - 2) pages.push("...");
  pages.push(total);
  return pages;
}

function messageOf(cause: unknown) {
  return cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "操作失败，请稍后重试";
}
