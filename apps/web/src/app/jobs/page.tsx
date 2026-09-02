"use client";

import {
  BriefcaseBusiness, CalendarPlus, ChevronDown, ChevronLeft,
  ChevronRight, ChevronUp, CircleDot, Edit3, Loader2, Plus, Search,
  TimerReset, Trash2, AlertCircle, Inbox, CalendarDays,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { CategoryTreePanel } from "@/components/jobs/category-tree-panel";
import { useWorkspace } from "@/lib/workspace-context";
import {
  fetchJobStats, fetchJobs, fetchJob, createJob, updateJob, deleteJob,
  batchUpdateStatus, batchDelete, updateJobStatus,
  readJobsCache, writeJobsCache, upsertJobInCache, removeJobFromCache,
  type Job, type JobInput, type JobStats,
} from "@/lib/job-api";
import {
  JOB_CATEGORY_TREE,
  collectLeafIds,
  findCategoryName,
  formatCategorySelectionSummary,
  getCategoryButtonLabel,
} from "@/lib/job-categories";

const HIRE_STATUS_OPTIONS = [
  { label: "招聘中", value: "ACTIVE" },
  { label: "已结束", value: "CLOSED" },
  { label: "待发布", value: "PENDING" },
  { label: "草稿", value: "DRAFT" },
] as const;

type DeptNode = { id: string; name: string; children?: DeptNode[] };

const DEPARTMENT_TREE: DeptNode[] = [
  {
    id: "hq",
    name: "公司总部",
    children: [
      {
        id: "rd",
        name: "研发中心",
        children: [
          { id: "rd-be", name: "后端研发部" },
          { id: "rd-fe", name: "前端研发部" },
          { id: "rd-algo", name: "算法部" },
        ],
      },
      {
        id: "ops",
        name: "生产运营中心",
        children: [
          { id: "ops-prod", name: "生产部" },
          { id: "ops-qe", name: "质量部" },
        ],
      },
      { id: "hr", name: "人力资源部" },
      { id: "sales", name: "市场销售部" },
      { id: "supply", name: "供应链部" },
      { id: "admin", name: "职能支持部" },
    ],
  },
];

const LOCATION_OPTIONS = ["杭州", "上海", "北京", "深圳", "广州", "成都", "远程"];
const JOB_TYPE_OPTIONS = ["全职", "兼职", "实习", "外包"];
const EDUCATION_OPTIONS = ["大专", "本科", "硕士", "博士", "不限"];

const FILTER_CONTROL_CLASS =
  "h-10 w-full rounded border border-[#bdd3ef] bg-white px-3 text-[13px] font-semibold text-[#36527f] outline-none focus:border-[#0874e8]";
const FILTER_SELECT_CLASS = `${FILTER_CONTROL_CLASS} appearance-none`;
const FILTER_LABEL_CLASS = "mb-1.5 block text-[13px] font-semibold text-[#36527f]";

type AdvancedFilters = {
  location: string;
  jobType: string;
  education: string;
  publishedFrom: string;
  publishedTo: string;
  updatedFrom: string;
  updatedTo: string;
  salaryMin: string;
  salaryMax: string;
};

const EMPTY_ADVANCED: AdvancedFilters = {
  location: "",
  jobType: "",
  education: "",
  publishedFrom: "",
  publishedTo: "",
  updatedFrom: "",
  updatedTo: "",
  salaryMin: "",
  salaryMax: "",
};

type JobUiExtra = {
  departmentId: string;
  categoryId: string;
};

const JOB_EXTRA_STORAGE_KEY = "ir.job-ui-extras";

function readJobExtras(): Record<string, JobUiExtra> {
  if (typeof window === "undefined") return {};
  try {
    const raw = sessionStorage.getItem(JOB_EXTRA_STORAGE_KEY);
    return raw ? JSON.parse(raw) as Record<string, JobUiExtra> : {};
  } catch {
    return {};
  }
}

function writeJobExtras(next: Record<string, JobUiExtra>) {
  if (typeof window === "undefined") return;
  sessionStorage.setItem(JOB_EXTRA_STORAGE_KEY, JSON.stringify(next));
}

const PAGE_SIZE_OPTIONS = [10, 20, 50];

export default function JobsPage() {
  const { workspaceId, workspace, loading: wsLoading, notAuthenticated, error: wsError, refresh: refreshWorkspace } = useWorkspace();

  useEffect(() => {
    if (notAuthenticated) window.location.replace("/login");
  }, [notAuthenticated]);

  const [stats, setStats] = useState<JobStats | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [total, setTotal] = useState(0);
  const [dataLoading, setDataLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [search, setSearch] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [categoryFilters, setCategoryFilters] = useState<string[]>([]);
  const [departmentId, setDepartmentId] = useState("");
  const [statusFilters, setStatusFilters] = useState<string[]>([]);
  const [advancedFilters, setAdvancedFilters] = useState<AdvancedFilters>(EMPTY_ADVANCED);
  const [draftAdvanced, setDraftAdvanced] = useState<AdvancedFilters>(EMPTY_ADVANCED);
  const [moreOpen, setMoreOpen] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [detailJob, setDetailJob] = useState<Job | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [panelMode, setPanelMode] = useState<"view" | "edit">("view");
  const [jobExtras, setJobExtras] = useState<Record<string, JobUiExtra>>({});

  const [editOpen, setEditOpen] = useState(false);
  const [editJob, setEditJob] = useState<Job | null>(null);
  const [editSaving, setEditSaving] = useState(false);

  const [deleteConfirm, setDeleteConfirm] = useState<Job | null>(null);
  const [batchConfirm, setBatchConfirm] = useState<"publish" | "deactivate" | "delete" | null>(null);
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchMenuOpen, setBatchMenuOpen] = useState(false);
  const [categoryManageOpen, setCategoryManageOpen] = useState(false);
  const [managedCategoryIds, setManagedCategoryIds] = useState<string[]>(() =>
    JOB_CATEGORY_TREE.flatMap(collectLeafIds),
  );

  const loadRequestRef = useRef(0);
  const hydratedWorkspaceRef = useRef<string | null>(null);
  const batchMenuRef = useRef<HTMLDivElement>(null);

  const apiStatus = useMemo(() => {
    const apiCapable = statusFilters.filter((s) => s === "ACTIVE" || s === "CLOSED" || s === "DRAFT");
    if (apiCapable.length === 1 && statusFilters.length === 1) return apiCapable[0];
    return "";
  }, [statusFilters]);

  useEffect(() => {
    if (!workspaceId || hydratedWorkspaceRef.current === workspaceId) return;
    hydratedWorkspaceRef.current = workspaceId;
    const cached = readJobsCache(workspaceId);
    if (!cached) return;
    if (cached.search === search && cached.status === apiStatus && cached.page === page && cached.pageSize === pageSize) {
      setStats(cached.stats);
      setJobs(cached.items);
      setTotal(cached.total);
    }
  }, [workspaceId, search, apiStatus, page, pageSize]);

  const loadData = useCallback(async () => {
    if (!workspaceId) return;
    const requestId = ++loadRequestRef.current;
    setDataLoading(true);
    setError(null);
    try {
      const [statsRes, jobsRes] = await Promise.all([
        fetchJobStats(workspaceId),
        fetchJobs(workspaceId, {
          search: search || undefined,
          status: apiStatus || undefined,
          page,
          pageSize,
        }),
      ]);
      if (requestId !== loadRequestRef.current) return;
      setStats(statsRes);
      setJobs(jobsRes.items);
      setTotal(jobsRes.total);
      writeJobsCache(workspaceId, {
        stats: statsRes,
        items: jobsRes.items,
        total: jobsRes.total,
        search,
        status: apiStatus,
        page,
        pageSize,
      });
      setDetailJob((prev) => {
        if (!prev) return prev;
        return jobsRes.items.find((item) => item.id === prev.id) ?? null;
      });
    } catch (err) {
      if (requestId !== loadRequestRef.current) return;
      setError(err instanceof Error ? err.message : "加载职位数据失败");
    } finally {
      if (requestId === loadRequestRef.current) setDataLoading(false);
    }
  }, [workspaceId, search, apiStatus, page, pageSize]);

  useEffect(() => {
    setDataLoading(true);
    const timer = window.setTimeout(() => void loadData(), 0);
    return () => window.clearTimeout(timer);
  }, [loadData]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === "visible") void loadData();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [loadData]);

  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch((prev) => {
        if (prev !== searchInput) setPage(1);
        return searchInput;
      });
    }, 400);
    return () => clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    if (!batchMenuOpen) return;
    const onPointerDown = (e: PointerEvent) => {
      if (!batchMenuRef.current?.contains(e.target as Node)) setBatchMenuOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [batchMenuOpen]);

  const displayedJobs = useMemo(() => {
    return jobs.filter((job) => {
      if (statusFilters.length > 1 || statusFilters.includes("PENDING")) {
        if (!statusFilters.includes(job.status)) return false;
      }
      if (advancedFilters.location && !job.location.includes(advancedFilters.location)) return false;
      if (advancedFilters.jobType && job.jobType !== advancedFilters.jobType) return false;
      if (advancedFilters.education && !(job.education || "").includes(advancedFilters.education)) return false;
      if (advancedFilters.updatedFrom && new Date(job.updatedAt) < new Date(advancedFilters.updatedFrom)) return false;
      if (advancedFilters.updatedTo && new Date(job.updatedAt) > new Date(`${advancedFilters.updatedTo}T23:59:59`)) return false;
      if (advancedFilters.publishedFrom && new Date(job.createdAt) < new Date(advancedFilters.publishedFrom)) return false;
      if (advancedFilters.publishedTo && new Date(job.createdAt) > new Date(`${advancedFilters.publishedTo}T23:59:59`)) return false;
      return true;
    });
  }, [jobs, statusFilters, advancedFilters]);

  useEffect(() => {
    setJobExtras(readJobExtras());
  }, []);

  const handleViewDetail = async (job: Job) => {
    if (!workspaceId) return;
    setPanelMode("view");
    setEditOpen(false);
    setEditJob(null);
    setDetailLoading(true);
    try {
      const detail = await fetchJob(workspaceId, job.id);
      setDetailJob(detail);
    } catch {
      setDetailJob(job);
    } finally {
      setDetailLoading(false);
    }
  };

  const handleOpenEdit = (job?: Job) => {
    if (job) {
      setPanelMode("edit");
      setEditJob(job);
      setDetailJob(job);
      setEditOpen(false);
      return;
    }
    setEditJob(null);
    setEditOpen(true);
  };

  const syncJob = (saved: Job) => {
    if (workspaceId) upsertJobInCache(workspaceId, saved);
    setJobs((prev) => {
      const without = prev.filter((item) => item.id !== saved.id);
      if (apiStatus && saved.status !== apiStatus) return without;
      return [saved, ...without];
    });
    setDetailJob((prev) => (!prev || prev.id === saved.id ? saved : prev));
  };

  const persistJobExtra = (jobId: string, extra: JobUiExtra) => {
    setJobExtras((prev) => {
      const next = { ...prev, [jobId]: extra };
      writeJobExtras(next);
      return next;
    });
  };

  const handleSaveEdit = async (input: JobInput, extra?: JobUiExtra) => {
    if (!workspaceId) return;
    setEditSaving(true);
    try {
      const payload: JobInput = {
        ...input,
        companyName: input.companyName.trim() || editJob?.companyName || workspace?.name || "企业",
      };
      const saved = editJob?.id
        ? await updateJob(workspaceId, editJob.id, payload)
        : await createJob(workspaceId, payload);
      if (extra) persistJobExtra(saved.id, extra);
      setEditOpen(false);
      setEditJob(null);
      setPanelMode("view");
      if (!editJob?.id) setTotal((prev) => prev + 1);
      syncJob(saved);
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存职位失败");
    } finally {
      setEditSaving(false);
    }
  };

  const handleChangeStatus = async (job: Job, status: "ACTIVE" | "CLOSED") => {
    if (!workspaceId) return;
    try {
      const saved = await updateJobStatus(workspaceId, job.id, status);
      syncJob(saved);
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新职位状态失败");
    }
  };

  const handleDelete = async (jobId: string) => {
    if (!workspaceId) return;
    try {
      await deleteJob(workspaceId, jobId);
      removeJobFromCache(workspaceId, jobId);
      setDeleteConfirm(null);
      if (detailJob?.id === jobId) setDetailJob(null);
      setJobs((prev) => prev.filter((item) => item.id !== jobId));
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除职位失败");
    }
  };

  const handleBatchPublish = async () => {
    if (!workspaceId || selectedIds.size === 0) return;
    setBatchBusy(true);
    try {
      await batchUpdateStatus(workspaceId, Array.from(selectedIds), "ACTIVE");
      setSelectedIds(new Set());
      setBatchConfirm(null);
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : "批量发布失败");
    } finally {
      setBatchBusy(false);
    }
  };

  const handleBatchDeactivate = async () => {
    if (!workspaceId || selectedIds.size === 0) return;
    setBatchBusy(true);
    try {
      await batchUpdateStatus(workspaceId, Array.from(selectedIds), "CLOSED");
      setSelectedIds(new Set());
      setBatchConfirm(null);
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : "批量停用失败");
    } finally {
      setBatchBusy(false);
    }
  };

  const handleBatchDelete = async () => {
    if (!workspaceId || selectedIds.size === 0) return;
    setBatchBusy(true);
    try {
      const ids = Array.from(selectedIds);
      await batchDelete(workspaceId, ids);
      ids.forEach((id) => removeJobFromCache(workspaceId, id));
      setSelectedIds(new Set());
      if (detailJob && ids.includes(detailJob.id)) setDetailJob(null);
      setBatchConfirm(null);
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : "批量删除失败");
    } finally {
      setBatchBusy(false);
    }
  };

  const handleBatchExport = () => {
    const rows = displayedJobs.filter((job) => selectedIds.has(job.id));
    if (rows.length === 0) {
      setError("请先勾选要导出的职位");
      return;
    }
    const header = ["职位名称", "企业名称", "工作地点", "状态", "创建时间", "更新时间"];
    const lines = rows.map((job) =>
      [job.title, job.companyName, job.location, statusLabel(job.status), formatDate(job.createdAt), formatDate(job.updatedAt)]
        .map((cell) => `"${String(cell).replaceAll('"', '""')}"`)
        .join(","),
    );
    const blob = new Blob([[header.join(","), ...lines].join("\n")], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `职位导出_${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    setBatchMenuOpen(false);
  };

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const allSelected = displayedJobs.length > 0 && displayedJobs.every((job) => selectedIds.has(job.id));

  const toggleSelectAll = () => {
    if (allSelected) setSelectedIds(new Set());
    else setSelectedIds(new Set(displayedJobs.map((j) => j.id)));
  };

  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const hasFilters = Boolean(
    search || categoryFilters.length || departmentId || statusFilters.length || countAdvanced(advancedFilters) > 0,
  );
  const advancedCount = countAdvanced(advancedFilters);

  const applyAdvanced = () => {
    setAdvancedFilters(draftAdvanced);
    setPage(1);
    setMoreOpen(false);
  };

  const resetAdvancedDraft = () => {
    setDraftAdvanced(EMPTY_ADVANCED);
  };

  const cancelAdvanced = () => {
    setDraftAdvanced(advancedFilters);
    setMoreOpen(false);
  };

  const toggleMoreFilters = () => {
    if (moreOpen) {
      setDraftAdvanced(advancedFilters);
      setMoreOpen(false);
      return;
    }
    setDraftAdvanced(advancedFilters);
    setMoreOpen(true);
  };

  if (wsLoading) {
    return (
      <AppShell activeItem="职位库">
        <div className="flex h-64 items-center justify-center">
          <Loader2 className="animate-spin text-[#0874e8]" size={32} />
          <span className="ml-3 text-sm text-[#55709d]">正在加载工作空间...</span>
        </div>
      </AppShell>
    );
  }

  if (wsError && !workspaceId) {
    return (
      <AppShell activeItem="职位库">
        <div className="flex h-64 flex-col items-center justify-center gap-3">
          <AlertCircle size={40} className="text-[#dc2626]" />
          <p className="text-sm text-[#55709d]">加载工作空间失败：{wsError}</p>
          <button className="primary-button" type="button" onClick={refreshWorkspace}>重新加载</button>
        </div>
      </AppShell>
    );
  }

  if (notAuthenticated || !workspaceId) {
    return (
      <AppShell activeItem="职位库">
        <div className="flex h-64 flex-col items-center justify-center gap-3">
          <AlertCircle size={40} className="text-[#f59e0b]" />
          <p className="text-sm text-[#55709d]">
            {notAuthenticated ? "请先登录后再访问职位库" : "尚未选择工作空间，请先创建或加入一个工作空间"}
          </p>
        </div>
      </AppShell>
    );
  }

  return (
    <AppShell activeItem="职位库" pageHeader={
      <section className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex items-baseline gap-4">
          <h1 className="m-0 text-[25px] font-bold tracking-tight text-[#09245d]">职位库</h1>
          <p className="m-0 text-sm text-[#55709d]">
            {workspace?.name ?? "当前工作空间"} · 管理和搜索企业职位信息
          </p>
        </div>
      </section>
    }>

      <div className="grid gap-3 2xl:grid-cols-[minmax(700px,1fr)_420px]">
        <div className="min-w-0">
          {/* 统计卡片 */}
          <section className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4" aria-label="职位概览">
            {stats ? (
              <>
                <MetricCard label="职位总数" value={stats.total} Icon={BriefcaseBusiness} />
                <MetricCard label="招聘中" value={stats.active} Icon={TimerReset} />
                <MetricCard label="已结束" value={stats.closed} Icon={CircleDot} />
                <MetricCard label="草稿" value={stats.draft} Icon={CalendarPlus} />
              </>
            ) : (
              <div className="col-span-4 flex items-center justify-center py-6">
                <Loader2 className="animate-spin text-[#6b80a4]" size={20} />
              </div>
            )}
          </section>

          {/* 搜索与筛选（位于统计卡片下方） */}
          <section className="mb-4">
            <div className="flex flex-wrap items-center gap-2">
              <label className="flex h-10 min-w-[220px] max-w-[360px] flex-1 items-center gap-2 rounded border border-[#bdd3ef] bg-white px-3 text-[#36527f]">
                <Search size={15} className="shrink-0 text-[#36527f]" />
                <input
                  className="min-w-0 flex-1 border-0 bg-transparent text-[13px] font-semibold text-[#36527f] outline-none placeholder:text-[#8fa3c0] placeholder:font-normal"
                  placeholder="搜索职位"
                  value={searchInput}
                  onChange={(e) => setSearchInput(e.target.value)}
                />
              </label>

              <CategoryTreeSelect
                value={categoryFilters}
                onChange={(next) => { setCategoryFilters(next); setPage(1); }}
                onManage={() => setCategoryManageOpen(true)}
              />

              <div className="shrink-0">
                <DepartmentTreeSelect
                  value={departmentId}
                  onChange={(id) => { setDepartmentId(id); setPage(1); }}
                />
              </div>

              <div className="shrink-0">
                <StatusMultiSelect
                  values={statusFilters}
                  onChange={(next) => { setStatusFilters(next); setPage(1); }}
                />
              </div>

              <div className="flex shrink-0 items-center gap-2">
                <button
                  type="button"
                  className={`flex h-10 shrink-0 items-center gap-1 rounded border bg-white px-3 text-[13px] font-semibold ${moreOpen || advancedCount > 0 ? "border-[#0874e8] text-[#0874e8]" : "border-[#bdd3ef] text-[#36527f]"}`}
                  onClick={toggleMoreFilters}
                >
                  更多筛选
                  {advancedCount > 0 && (
                    <span className="rounded-full bg-[#0874e8] px-1.5 text-[10px] font-semibold text-white">{advancedCount}</span>
                  )}
                  {moreOpen ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                </button>

                <button className="primary-button !h-10" type="button" onClick={() => handleOpenEdit()}>
                  <Plus size={16} /> 新建职位
                </button>

                <div className="relative" ref={batchMenuRef}>
                  <button
                    className="outline-button !h-10 !border-[#bdd3ef] !text-[13px] !font-semibold !text-[#36527f]"
                    type="button"
                    disabled={batchBusy}
                    aria-expanded={batchMenuOpen}
                    aria-haspopup="menu"
                    onClick={() => setBatchMenuOpen((open) => !open)}
                  >
                    批量操作 <ChevronDown size={15} className={batchMenuOpen ? "rotate-180" : ""} />
                  </button>
                  {batchMenuOpen && (
                    <div
                      role="menu"
                      className="absolute right-0 z-40 mt-1 w-44 overflow-hidden rounded-lg border border-[#d6e5f5] bg-white py-1 shadow-lg"
                    >
                      {selectedIds.size === 0 && (
                        <p className="border-b border-[#eaf1fa] px-3 py-2 text-xs text-[#8fa3c0]">
                          请先勾选职位后再操作
                        </p>
                      )}
                      <button
                        type="button"
                        role="menuitem"
                        disabled={selectedIds.size === 0 || batchBusy}
                        className="block w-full px-3 py-2 text-left text-sm text-[#36527f] hover:bg-[#f5f9ff] disabled:cursor-not-allowed disabled:text-[#b0becf] disabled:hover:bg-white"
                        onClick={() => { setBatchMenuOpen(false); setBatchConfirm("publish"); }}
                      >
                        批量发布
                      </button>
                      <button
                        type="button"
                        role="menuitem"
                        disabled={selectedIds.size === 0 || batchBusy}
                        className="block w-full px-3 py-2 text-left text-sm text-[#36527f] hover:bg-[#f5f9ff] disabled:cursor-not-allowed disabled:text-[#b0becf] disabled:hover:bg-white"
                        onClick={() => { setBatchMenuOpen(false); setBatchConfirm("deactivate"); }}
                      >
                        批量停用
                      </button>
                      <button
                        type="button"
                        role="menuitem"
                        disabled={selectedIds.size === 0 || batchBusy}
                        className="block w-full px-3 py-2 text-left text-sm text-[#dc2626] hover:bg-[#fef2f2] disabled:cursor-not-allowed disabled:text-[#f0b4b4] disabled:hover:bg-white"
                        onClick={() => { setBatchMenuOpen(false); setBatchConfirm("delete"); }}
                      >
                        批量删除
                      </button>
                      <button
                        type="button"
                        role="menuitem"
                        disabled={selectedIds.size === 0 || batchBusy}
                        className="block w-full px-3 py-2 text-left text-sm text-[#36527f] hover:bg-[#f5f9ff] disabled:cursor-not-allowed disabled:text-[#b0becf] disabled:hover:bg-white"
                        onClick={() => { handleBatchExport(); setBatchMenuOpen(false); }}
                      >
                        批量导出
                      </button>
                    </div>
                  )}
                </div>
              </div>
            </div>

            {moreOpen && (
              <MoreFiltersPanel
                value={draftAdvanced}
                onChange={setDraftAdvanced}
                onConfirm={applyAdvanced}
                onCancel={cancelAdvanced}
                onReset={resetAdvancedDraft}
                onCollapse={cancelAdvanced}
              />
            )}
          </section>

          {(categoryFilters.length > 0 || departmentId) && (
            <p className="mb-3 text-xs text-[#7187a8]">
              职位分类 / 所属部门筛选将在职位字段完善后生效；当前可先选择并保留筛选条件。
            </p>
          )}

          <section className="overflow-hidden rounded-xl border border-[#d6e5f5] bg-white shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
            {error && (
              <div className="flex items-center gap-2 bg-[#fef2f2] px-4 py-3 text-sm text-[#dc2626]">
                <AlertCircle size={16} /> {error}
                <button className="ml-auto text-[#0874e8] underline" onClick={() => { setError(null); void loadData(); }}>重试</button>
              </div>
            )}

            {dataLoading && displayedJobs.length === 0 ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="animate-spin text-[#0874e8]" size={28} />
                <span className="ml-3 text-sm text-[#55709d]">加载职位列表...</span>
              </div>
            ) : displayedJobs.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-16 text-[#6b80a4]">
                <Inbox size={48} className="mb-3 opacity-40" />
                <p className="text-sm font-medium">{hasFilters ? "没有符合筛选条件的职位" : "暂无职位数据"}</p>
                <p className="mt-1 text-xs">
                  {hasFilters ? "试试清空筛选条件或切换招聘状态" : "点击「新建职位」开始创建第一个职位"}
                </p>
                {!hasFilters && (
                  <button className="primary-button mt-4" type="button" onClick={() => handleOpenEdit()}>
                    <Plus size={16} /> 新建职位
                  </button>
                )}
              </div>
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[1040px] border-collapse text-left text-xs text-[#36527f]">
                    <thead className="bg-[#f8fbff] text-[#536b91]">
                      <tr>
                        <th className="border-b border-[#dbe8f6] px-3 py-3 font-medium">
                          <input type="checkbox" aria-label="选择全部职位" checked={allSelected} onChange={toggleSelectAll} />
                        </th>
                        {["职位名称", "企业名称", "工作地点", "创建时间", "更新时间", "状态", "操作"].map((item) => (
                          <th key={item} className="border-b border-[#dbe8f6] px-3 py-3 font-medium">{item}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {displayedJobs.map((job) => (
                        <tr key={job.id} className={detailJob?.id === job.id ? "bg-[#eafff7]" : "hover:bg-[#f8fbff]"}>
                          <td className="border-b border-[#eaf1fa] px-3 py-3">
                            <input type="checkbox" aria-label={`选择${job.title}`} checked={selectedIds.has(job.id)} onChange={() => toggleSelect(job.id)} />
                          </td>
                          <td className="border-b border-[#eaf1fa] px-3 py-3 font-semibold text-[#132e61]">
                            <button className="text-left hover:text-[#0874e8] hover:underline" onClick={() => handleViewDetail(job)} type="button">
                              {job.title}
                            </button>
                          </td>
                          <td className="border-b border-[#eaf1fa] px-3 py-3">{job.companyName}</td>
                          <td className="border-b border-[#eaf1fa] px-3 py-3">{job.location}</td>
                          <td className="border-b border-[#eaf1fa] px-3 py-3">{formatDate(job.createdAt)}</td>
                          <td className="border-b border-[#eaf1fa] px-3 py-3">{formatDate(job.updatedAt)}</td>
                          <td className="border-b border-[#eaf1fa] px-3 py-3">
                            <span className={job.status === "ACTIVE" ? "status-live" : job.status === "CLOSED" ? "status-closed" : "inline-block rounded-md bg-[#f0f4fa] px-2 py-0.5 text-xs text-[#6b80a4]"}>
                              ● {statusLabel(job.status)}
                            </span>
                          </td>
                          <td className="border-b border-[#eaf1fa] whitespace-nowrap px-3 py-3 font-medium text-[#0874e8]">
                            <button className="hover:underline" onClick={() => handleViewDetail(job)} type="button">查看</button>
                            <span className="mx-1 text-[#c4d3e8]">|</span>
                            <button className="hover:underline" onClick={() => handleOpenEdit(job)} type="button">编辑</button>
                            <span className="mx-1 text-[#c4d3e8]">|</span>
                            <button
                              className="hover:underline disabled:cursor-not-allowed disabled:text-[#9db0c9] disabled:no-underline"
                              disabled={job.status === "ACTIVE"}
                              onClick={() => void handleChangeStatus(job, "ACTIVE")}
                              type="button"
                            >
                              发布
                            </button>
                            <span className="mx-1 text-[#c4d3e8]">|</span>
                            <button
                              className="hover:underline disabled:cursor-not-allowed disabled:text-[#9db0c9] disabled:no-underline"
                              disabled={job.status !== "ACTIVE"}
                              onClick={() => void handleChangeStatus(job, "CLOSED")}
                              type="button"
                            >
                              停用
                            </button>
                            <span className="mx-1 text-[#c4d3e8]">|</span>
                            <button className="hover:underline" onClick={() => setDeleteConfirm(job)} type="button">删除</button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <footer className="flex items-center justify-between px-4 py-4 text-xs text-[#4d6388]">
                  <span>共 {total} 条</span>
                  <div className="flex items-center gap-2">
                    <button className="page-button" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))} type="button">
                      <ChevronLeft size={14} />
                    </button>
                    {buildPageNumbers(page, totalPages).map((p, i) =>
                      p === "..." ? (
                        <span key={`dots-${i}`} className="px-1 text-[#8fa3c0]">…</span>
                      ) : (
                        <button key={p} className={page === p ? "page-active" : "page-button"} onClick={() => setPage(p as number)} type="button">
                          {p}
                        </button>
                      ),
                    )}
                    <button className="page-button" disabled={page >= totalPages} onClick={() => setPage((p) => Math.min(totalPages, p + 1))} type="button">
                      <ChevronRight size={14} />
                    </button>
                    <div className="relative ml-3">
                      <select
                        className="h-8 appearance-none rounded-lg border border-[#bdd3ef] bg-white px-3 text-xs text-[#36527f] outline-none"
                        value={pageSize}
                        onChange={(e) => { setPageSize(Number(e.target.value)); setPage(1); }}
                      >
                        {PAGE_SIZE_OPTIONS.map((n) => (
                          <option key={n} value={n}>每页 {n} 条</option>
                        ))}
                      </select>
                      <ChevronDown size={13} className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-[#6b80a4]" />
                    </div>
                  </div>
                </footer>
              </>
            )}
          </section>
        </div>

        <aside className="rounded-xl border border-[#d6e5f5] bg-white p-4 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
          {detailLoading ? (
            <div className="flex items-center justify-center py-16">
              <Loader2 className="animate-spin text-[#0874e8]" size={24} />
            </div>
          ) : detailJob && panelMode === "edit" ? (
            <JobEditPanel
              job={detailJob}
              departmentId={jobExtras[detailJob.id]?.departmentId ?? ""}
              categoryId={jobExtras[detailJob.id]?.categoryId ?? ""}
              saving={editSaving}
              onSave={(input, extra) => void handleSaveEdit(input, extra)}
              onCancel={() => {
                setPanelMode("view");
                setEditJob(null);
              }}
            />
          ) : detailJob ? (
            <>
              <div className="flex items-center justify-between border-b border-[#e0eaf5] pb-3">
                <h2 className="m-0 text-base font-bold text-[#173568]">职位详情</h2>
                <button className="outline-button !h-8 !px-3 text-[#0874e8]" type="button" onClick={() => handleOpenEdit(detailJob)}>
                  <Edit3 size={14} /> 编辑
                </button>
              </div>
              <DetailSection title="基础信息">
                <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2">
                  <InfoItem label="职位名称" value={detailJob.title} />
                  <InfoItem
                    label="所属部门"
                    value={findDeptName(DEPARTMENT_TREE, jobExtras[detailJob.id]?.departmentId ?? "") ?? "--"}
                  />
                  <InfoItem
                    label="职位分类"
                    value={findCategoryName(jobExtras[detailJob.id]?.categoryId ?? "") ?? "--"}
                  />
                  <InfoItem label="工作地点" value={detailJob.location || "--"} />
                  <InfoItem label="薪资范围" value={detailJob.salaryRange || "--"} />
                  <InfoItem label="经验要求" value={detailJob.experienceLevel || "--"} />
                  <InfoItem label="学历要求" value={detailJob.education || "--"} />
                  <InfoItem label="工作类型" value={detailJob.jobType || "--"} />
                  <InfoItem label="招聘状态" value={statusLabel(detailJob.status)} />
                  <InfoItem label="创建时间" value={formatDate(detailJob.createdAt)} />
                  <InfoItem label="更新时间" value={formatDate(detailJob.updatedAt)} />
                </dl>
              </DetailSection>

              <DetailSection title="职位描述">
                {detailJob.description
                  ? detailJob.description.split("\n").filter(Boolean).map((line, i) => (
                      <p key={i} className="m-0 mb-1">{line}</p>
                    ))
                  : <p className="m-0 text-[#8fa3c0]">暂无</p>}
              </DetailSection>

              <DetailSection title="任职要求">
                {detailJob.requirements ? (
                  <ul className="m-0 list-disc pl-4">
                    {detailJob.requirements.split("\n").filter(Boolean).map((line, i) => (
                      <li key={i}>{line}</li>
                    ))}
                  </ul>
                ) : (
                  <p className="m-0 text-[#8fa3c0]">暂无</p>
                )}
              </DetailSection>

              <DetailSection title="关键技能">
                {detailJob.skills ? (
                  <div className="flex flex-wrap gap-2">
                    {detailJob.skills.split(/[,，\s]+/).filter(Boolean).map((skill) => (
                      <span key={skill} className="skill-tag">{skill}</span>
                    ))}
                  </div>
                ) : (
                  <p className="m-0 text-[#8fa3c0]">暂无</p>
                )}
              </DetailSection>
              {detailJob.niceToHaves && <DetailSection title="加分项"><p className="m-0 whitespace-pre-wrap">{detailJob.niceToHaves}</p></DetailSection>}
              {detailJob.benefits && <DetailSection title="福利待遇"><p className="m-0 whitespace-pre-wrap">{detailJob.benefits}</p></DetailSection>}

              <div className="mt-4 grid grid-cols-1 gap-3 rounded-xl border border-[#deebf7] bg-[#f9fcff] p-4 sm:grid-cols-3">
                <button className="primary-button" type="button" onClick={() => alert("AI招聘助手功能即将上线")}>
                  AI招聘助手
                </button>
                {detailJob.status === "ACTIVE" ? (
                  <button className="outline-button" type="button" onClick={() => void handleChangeStatus(detailJob, "CLOSED")}>
                    停用职位
                  </button>
                ) : (
                  <button className="outline-button" type="button" onClick={() => void handleChangeStatus(detailJob, "ACTIVE")}>
                    发布
                  </button>
                )}
                <button className="outline-button text-[#dc2626]" type="button" onClick={() => setDeleteConfirm(detailJob)}>
                  <Trash2 size={14} /> 删除职位
                </button>
              </div>
            </>
          ) : (
            <div className="flex flex-col items-center justify-center py-16 text-[#6b80a4]">
              <BriefcaseBusiness size={40} className="mb-3 opacity-30" />
              <p className="text-sm">点击左侧职位查看详情</p>
            </div>
          )}
        </aside>
      </div>

      {editOpen && (
        <JobEditModal
          job={editJob}
          defaultCompanyName={workspace?.name ?? ""}
          saving={editSaving}
          onSave={(input, extra) => void handleSaveEdit(input, extra)}
          onClose={() => { setEditOpen(false); setEditJob(null); }}
        />
      )}

      {deleteConfirm && (
        <ConfirmModal
          title="确认删除"
          message={`确定要删除职位「${deleteConfirm.title}」吗？此操作不可撤销。`}
          confirmLabel="确认删除"
          danger
          onConfirm={() => handleDelete(deleteConfirm.id)}
          onCancel={() => setDeleteConfirm(null)}
        />
      )}

      {batchConfirm === "publish" && (
        <ConfirmModal
          title="批量发布"
          message={`将发布已选中的 ${selectedIds.size} 个职位为招聘中，确认继续？`}
          confirmLabel={batchBusy ? "发布中..." : "确认发布"}
          onConfirm={() => void handleBatchPublish()}
          onCancel={() => !batchBusy && setBatchConfirm(null)}
        />
      )}

      {batchConfirm === "deactivate" && (
        <ConfirmModal
          title="批量停用"
          message={`将停用已选中的 ${selectedIds.size} 个职位（状态变为已结束），确认继续？`}
          confirmLabel={batchBusy ? "停用中..." : "确认停用"}
          onConfirm={() => void handleBatchDeactivate()}
          onCancel={() => !batchBusy && setBatchConfirm(null)}
        />
      )}

      {batchConfirm === "delete" && (
        <ConfirmModal
          title="批量删除"
          message={`将删除已选中的 ${selectedIds.size} 个职位，此操作不可撤销。`}
          confirmLabel={batchBusy ? "删除中..." : "确认删除"}
          danger
          onConfirm={() => void handleBatchDelete()}
          onCancel={() => !batchBusy && setBatchConfirm(null)}
        />
      )}

      {categoryManageOpen && (
        <CategoryManagementModal
          value={managedCategoryIds}
          onChange={setManagedCategoryIds}
          onClose={() => setCategoryManageOpen(false)}
        />
      )}
    </AppShell>
  );
}

function CategoryTreeSelect({
  value,
  onChange,
  onManage,
}: {
  value: string[];
  onChange: (next: string[]) => void;
  onManage?: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<string[]>(value);
  const ref = useRef<HTMLDivElement>(null);
  const label = getCategoryButtonLabel(value);

  useEffect(() => {
    if (open) setDraft(value);
  }, [open, value]);

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  return (
    <div className="relative w-[112px] shrink-0" ref={ref}>
      <button
        type="button"
        className={`flex h-10 w-full items-center justify-between gap-2 rounded border bg-white px-2.5 text-[13px] font-semibold ${value.length ? "border-[#0874e8] text-[#0874e8]" : "border-[#bdd3ef] text-[#36527f]"}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="truncate">{label}</span>
        <ChevronDown size={14} />
      </button>
      {open && (
        <div className="absolute left-0 z-40 mt-1 w-[360px] overflow-hidden rounded-lg border border-[#d6e5f5] bg-white shadow-lg">
          <CategoryTreePanel mode="multi" value={draft} onChange={setDraft} />

          <div className="border-t border-[#eaf1fa] px-3 py-2 text-[12px] text-[#55709d]">
            <span className="line-clamp-2">已选：{formatCategorySelectionSummary(draft)}</span>
          </div>

          <div className="flex items-center justify-between gap-2 border-t border-[#eaf1fa] px-3 py-2.5">
            {onManage ? (
              <button
                type="button"
                className="text-[13px] font-semibold text-[#0874e8] hover:underline"
                onClick={() => { setOpen(false); onManage(); }}
              >
                职位分类管理
              </button>
            ) : <span />}
            <div className="flex items-center gap-2">
              <button
                type="button"
                className="h-8 rounded border border-[#bdd3ef] bg-white px-3 text-[13px] font-semibold text-[#36527f] hover:border-[#0874e8] hover:text-[#0874e8]"
                onClick={() => setDraft([])}
              >
                重置
              </button>
              <button
                type="button"
                className="primary-button !h-8 !px-4 !text-[13px]"
                onClick={() => { onChange(draft); setOpen(false); }}
              >
                确定
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function CategorySingleTreeSelect({
  value,
  onChange,
}: {
  value: string;
  onChange: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const label = value ? (findCategoryName(value) ?? "请选择职位分类") : "请选择职位分类";
  const selected = value ? [value] : [];

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        className={`flex h-10 w-full items-center justify-between gap-2 rounded-lg border bg-white px-3 text-sm ${value ? "border-[#0874e8] text-[#132e61]" : "border-[#bdd3ef] text-[#8fa3c0]"}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="truncate">{label}</span>
        <ChevronDown size={14} className="shrink-0 text-[#8fa3c0]" />
      </button>
      {open && (
        <div className="absolute left-0 z-40 mt-1 w-[min(100vw-2rem,360px)] overflow-hidden rounded-lg border border-[#d6e5f5] bg-white shadow-lg">
          <CategoryTreePanel
            mode="single"
            value={selected}
            onChange={(next) => {
              onChange(next[0] ?? "");
              setOpen(false);
            }}
            showSelectAll={false}
          />
        </div>
      )}
    </div>
  );
}

function CategoryManagementModal({
  value,
  onChange,
  onClose,
}: {
  value: string[];
  onChange: (next: string[]) => void;
  onClose: () => void;
}) {
  const [draft, setDraft] = useState(value);

  useEffect(() => {
    setDraft(value);
  }, [value]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div
        className="flex max-h-[min(720px,90vh)] w-full max-w-[480px] flex-col overflow-hidden rounded-xl bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-[#eaf1fa] px-5 py-4">
          <h3 className="m-0 text-base font-bold text-[#173568]">职位分类管理</h3>
          <button type="button" className="text-[#7890ad]" onClick={onClose} aria-label="关闭">×</button>
        </div>

        <div className="min-h-0 flex-1 overflow-hidden">
          <CategoryTreePanel
            mode="multi"
            value={draft}
            onChange={setDraft}
            showSelectAll={false}
            maxHeightClass="max-h-[min(52vh,420px)]"
          />
        </div>

        <p className="border-t border-[#eaf1fa] px-5 py-2 text-[12px] text-[#55709d]">
          已启用 {draft.length} 个分类节点 · 勾选表示在职位库中启用该分类
        </p>

        <div className="flex justify-end gap-3 border-t border-[#eaf1fa] px-5 py-4">
          <button type="button" className="outline-button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary-button"
            onClick={() => { onChange(draft); onClose(); }}
          >
            保存
          </button>
        </div>
      </div>
    </div>
  );
}

function DepartmentTreeSelect({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set(["hq", "rd", "ops"]));
  const ref = useRef<HTMLDivElement>(null);
  const label = value ? findDeptName(DEPARTMENT_TREE, value) ?? "所属部门" : "所属部门";

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  const toggleExpand = (id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const renderNodes = (nodes: DeptNode[], depth = 0): ReactNode =>
    nodes.map((node) => {
      const hasChildren = Boolean(node.children?.length);
      const isOpen = expanded.has(node.id);
      return (
        <div key={node.id}>
          <div className="flex items-center" style={{ paddingLeft: 8 + depth * 14 }}>
            {hasChildren ? (
              <button type="button" className="mr-1 grid h-5 w-5 place-items-center text-[#6b80a4]" onClick={() => toggleExpand(node.id)}>
                <ChevronRight size={12} className={isOpen ? "rotate-90" : ""} />
              </button>
            ) : (
              <span className="mr-1 w-5" />
            )}
            <button
              type="button"
              className={`flex-1 truncate py-1.5 text-left text-[13px] hover:text-[#0874e8] ${value === node.id ? "font-semibold text-[#0874e8]" : "text-[#36527f]"}`}
              onClick={() => { onChange(node.id); setOpen(false); }}
            >
              {node.name}
            </button>
          </div>
          {hasChildren && isOpen && renderNodes(node.children!, depth + 1)}
        </div>
      );
    });

  return (
    <div className="relative w-[112px]" ref={ref}>
      <button
        type="button"
        className={`flex h-10 w-full items-center justify-between gap-2 rounded border bg-white px-2.5 text-[13px] font-semibold ${value ? "border-[#0874e8] text-[#0874e8]" : "border-[#bdd3ef] text-[#36527f]"}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="truncate">{label}</span>
        <ChevronDown size={14} />
      </button>
      {open && (
        <div className="absolute left-0 z-30 mt-1 w-56 overflow-hidden rounded-lg border border-[#d6e5f5] bg-white py-2 shadow-lg">
          <button type="button" className="mb-1 block w-full px-3 py-1.5 text-left text-sm text-[#6b80a4] hover:bg-[#f5f9ff]" onClick={() => { onChange(""); setOpen(false); }}>
            全部部门
          </button>
          <div className="max-h-56 overflow-auto">{renderNodes(DEPARTMENT_TREE)}</div>
        </div>
      )}
    </div>
  );
}

function StatusMultiSelect({ values, onChange }: { values: string[]; onChange: (next: string[]) => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const label = values.length === 0
    ? "招聘状态"
    : values.length === 1
      ? (HIRE_STATUS_OPTIONS.find((o) => o.value === values[0])?.label ?? "招聘状态")
      : `已选 ${values.length} 项`;

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  const toggle = (value: string) => {
    if (values.includes(value)) onChange(values.filter((v) => v !== value));
    else onChange([...values, value]);
  };

  return (
    <div className="relative w-[112px]" ref={ref}>
      <button
        type="button"
        className={`flex h-10 w-full items-center justify-between gap-2 rounded border bg-white px-2.5 text-[13px] font-semibold ${values.length ? "border-[#0874e8] text-[#0874e8]" : "border-[#bdd3ef] text-[#36527f]"}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="truncate">{label}</span>
        <ChevronDown size={14} />
      </button>
      {open && (
        <div className="absolute left-0 z-30 mt-1 w-44 overflow-hidden rounded-lg border border-[#d6e5f5] bg-white py-1 shadow-lg">
          {HIRE_STATUS_OPTIONS.map((opt) => (
            <label key={opt.value} className="flex cursor-pointer items-center gap-2 px-3 py-2 text-[13px] text-[#36527f] hover:bg-[#f5f9ff]">
              <input type="checkbox" checked={values.includes(opt.value)} onChange={() => toggle(opt.value)} />
              {opt.label}
            </label>
          ))}
          {values.length > 0 && (
            <button type="button" className="block w-full border-t border-[#eaf1fa] px-3 py-2 text-left text-xs text-[#0874e8]" onClick={() => onChange([])}>
              清空
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function MoreFiltersPanel({
  value, onChange, onConfirm, onCancel, onReset, onCollapse,
}: {
  value: AdvancedFilters;
  onChange: (next: AdvancedFilters) => void;
  onConfirm: () => void;
  onCancel: () => void;
  onReset: () => void;
  onCollapse: () => void;
}) {
  const set = <K extends keyof AdvancedFilters>(key: K, next: AdvancedFilters[K]) => {
    onChange({ ...value, [key]: next });
  };

  return (
    <div className="relative mt-3 rounded-lg border border-[#f0f0f0] bg-white px-5 pb-4 pt-3 shadow-[0_6px_16px_rgba(0,0,0,0.08)]">
      <div className="pointer-events-none absolute -top-2 right-[220px] h-3 w-3 rotate-45 border-l border-t border-[#f0f0f0] bg-white" />

      <div className="mb-3 flex items-center justify-end gap-3">
        <button type="button" className="text-[13px] font-semibold text-[#0874e8] hover:text-[#4096ff]" onClick={onReset}>重置</button>
        <button type="button" className="text-[#36527f] hover:text-[#0874e8]" onClick={onCollapse} aria-label="收起">
          <ChevronUp size={16} />
        </button>
      </div>

      <div className="grid grid-cols-1 gap-x-6 gap-y-4 md:grid-cols-2 xl:grid-cols-4">
        <PanelField label="工作地点">
          <select className={FILTER_SELECT_CLASS} value={value.location} onChange={(e) => set("location", e.target.value)}>
            <option value="">请选择</option>
            {LOCATION_OPTIONS.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </PanelField>
        <PanelField label="工作类型">
          <select className={FILTER_SELECT_CLASS} value={value.jobType} onChange={(e) => set("jobType", e.target.value)}>
            <option value="">全部</option>
            {JOB_TYPE_OPTIONS.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </PanelField>
        <PanelField label="学历要求">
          <select className={FILTER_SELECT_CLASS} value={value.education} onChange={(e) => set("education", e.target.value)}>
            <option value="">全部</option>
            {EDUCATION_OPTIONS.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </PanelField>
        <PanelField label="薪资范围">
          <NumberRangeInput
            min={value.salaryMin}
            max={value.salaryMax}
            unit="元"
            onMin={(v) => set("salaryMin", v)}
            onMax={(v) => set("salaryMax", v)}
          />
        </PanelField>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-x-6 gap-y-4 md:grid-cols-2">
        <PanelField label="创建时间">
          <DateRangeInput
            from={value.publishedFrom}
            to={value.publishedTo}
            onFrom={(v) => set("publishedFrom", v)}
            onTo={(v) => set("publishedTo", v)}
          />
        </PanelField>
        <PanelField label="更新时间">
          <DateRangeInput
            from={value.updatedFrom}
            to={value.updatedTo}
            onFrom={(v) => set("updatedFrom", v)}
            onTo={(v) => set("updatedTo", v)}
          />
        </PanelField>
      </div>

      <div className="mt-5 flex items-center justify-between border-t border-[#f0f0f0] pt-4">
        <button type="button" className="inline-flex items-center gap-1 text-[13px] font-semibold text-[#0874e8] hover:text-[#4096ff]" onClick={onCollapse}>
          收起筛选 <ChevronUp size={14} />
        </button>
        <div className="flex items-center gap-3">
          <button
            type="button"
            className="h-10 rounded border border-[#bdd3ef] bg-white px-4 text-[13px] font-semibold text-[#36527f] hover:border-[#0874e8] hover:text-[#0874e8]"
            onClick={onCancel}
          >
            取消
          </button>
          <button
            type="button"
            className="primary-button !h-10"
            onClick={onConfirm}
          >
            确定
          </button>
        </div>
      </div>
    </div>
  );
}

function PanelField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className={FILTER_LABEL_CLASS}>{label}</span>
      {children}
    </label>
  );
}

function DateRangeInput({
  from, to, onFrom, onTo,
}: {
  from: string; to: string; onFrom: (v: string) => void; onTo: (v: string) => void;
}) {
  return (
    <div className="flex h-10 items-center gap-1 rounded border border-[#bdd3ef] bg-white px-2 text-[13px] font-semibold text-[#36527f] focus-within:border-[#0874e8]">
      <input type="date" className="min-w-0 flex-1 border-0 bg-transparent text-[13px] font-semibold text-[#36527f] outline-none" value={from} onChange={(e) => onFrom(e.target.value)} />
      <span className="text-[#8fa3c0]">~</span>
      <input type="date" className="min-w-0 flex-1 border-0 bg-transparent text-[13px] font-semibold text-[#36527f] outline-none" value={to} onChange={(e) => onTo(e.target.value)} />
      <CalendarDays size={14} className="shrink-0 text-[#8fa3c0]" />
    </div>
  );
}

function NumberRangeInput({
  min, max, unit, onMin, onMax,
}: {
  min: string; max: string; unit: string; onMin: (v: string) => void; onMax: (v: string) => void;
}) {
  return (
    <div className="flex h-10 items-center gap-1 rounded border border-[#bdd3ef] bg-white px-2 text-[13px] font-semibold text-[#36527f] focus-within:border-[#0874e8]">
      <input type="number" className="min-w-0 flex-1 border-0 bg-transparent text-[13px] font-semibold text-[#36527f] outline-none" value={min} onChange={(e) => onMin(e.target.value)} placeholder="" />
      <span className="text-[#8fa3c0]">-</span>
      <input type="number" className="min-w-0 flex-1 border-0 bg-transparent text-[13px] font-semibold text-[#36527f] outline-none" value={max} onChange={(e) => onMax(e.target.value)} placeholder="" />
      <span className="shrink-0 text-[#36527f]">{unit}</span>
    </div>
  );
}

function MetricCard({ label, value, Icon }: { label: string; value: number; Icon: React.ComponentType<{ size: number }> }) {
  return (
    <article className="metric-card">
      <span className="metric-icon"><Icon size={18} /></span>
      <div>
        <p className="m-0 text-sm font-semibold text-[#2b4775]">{label}</p>
        <p className="mb-0 mt-1 flex items-end gap-2">
          <strong className="text-[27px] leading-none text-[#09245d]">
            {value}<small className="ml-1 text-sm">个</small>
          </strong>
        </p>
      </div>
    </article>
  );
}

function DetailSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mb-4 text-xs leading-6 text-[#344f7b]">
      <h4 className="mb-1 mt-0 text-sm font-bold text-[#173568]">{title}</h4>
      {children}
    </section>
  );
}

function InfoItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-[12px] text-[#8fa3c0]">{label}</dt>
      <dd className="m-0 mt-0.5 truncate text-[13px] font-semibold text-[#173568]">{value || "--"}</dd>
    </div>
  );
}

function flattenDepartments(nodes: DeptNode[], depth = 0): { id: string; name: string; depth: number }[] {
  return nodes.flatMap((node) => [
    { id: node.id, name: node.name, depth },
    ...(node.children?.length ? flattenDepartments(node.children, depth + 1) : []),
  ]);
}

function JobFormFields({
  form,
  departmentId,
  categoryId,
  onFormChange,
  onDepartmentChange,
  onCategoryChange,
}: {
  form: JobInput;
  departmentId: string;
  categoryId: string;
  onFormChange: (key: keyof JobInput, value: string) => void;
  onDepartmentChange: (id: string) => void;
  onCategoryChange: (id: string) => void;
}) {
  const deptOptions = useMemo(() => flattenDepartments(DEPARTMENT_TREE), []);

  return (
    <div className="space-y-3">
      <Field label="职位名称" required value={form.title} onChange={(v) => onFormChange("title", v)} />
      <label className="block">
        <span className="mb-1 block text-xs font-medium text-[#36527f]">所属部门</span>
        <select
          className="h-10 w-full rounded-lg border border-[#bdd3ef] px-3 text-sm text-[#132e61] outline-none focus:border-[#0874e8]"
          value={departmentId}
          onChange={(e) => onDepartmentChange(e.target.value)}
        >
          <option value="">请选择所属部门</option>
          {deptOptions.map((item) => (
            <option key={item.id} value={item.id}>
              {"　".repeat(item.depth)}{item.name}
            </option>
          ))}
        </select>
      </label>
      <label className="block">
        <span className="mb-1 block text-xs font-medium text-[#36527f]">职位分类</span>
        <CategorySingleTreeSelect value={categoryId} onChange={onCategoryChange} />
      </label>
      <Field label="工作地点" value={form.location} onChange={(v) => onFormChange("location", v)} />
      <Field label="薪资范围" value={form.salaryRange} onChange={(v) => onFormChange("salaryRange", v)} placeholder="如：25K-35K·14薪" />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Field label="经验要求" value={form.experienceLevel} onChange={(v) => onFormChange("experienceLevel", v)} placeholder="如：5年以上" />
        <Field label="学历要求" value={form.education} onChange={(v) => onFormChange("education", v)} placeholder="如：本科及以上" />
        <label className="block">
          <span className="mb-1 block text-xs font-medium text-[#36527f]">工作类型</span>
          <select
            className="h-10 w-full rounded-lg border border-[#bdd3ef] px-3 text-sm text-[#132e61] outline-none focus:border-[#0874e8]"
            value={form.jobType}
            onChange={(e) => onFormChange("jobType", e.target.value)}
          >
            {JOB_TYPE_OPTIONS.map((item) => (
              <option key={item} value={item}>{item}</option>
            ))}
          </select>
        </label>
      </div>
      <TextareaField label="职位描述" value={form.description} onChange={(v) => onFormChange("description", v)} />
      <TextareaField label="任职要求" value={form.requirements} onChange={(v) => onFormChange("requirements", v)} placeholder="每行一条要求" />
      <TextareaField label="关键技能" value={form.skills} onChange={(v) => onFormChange("skills", v)} placeholder="逗号或空格分隔" />
      <TextareaField label="加分项" value={form.niceToHaves} onChange={(v) => onFormChange("niceToHaves", v)} />
      <TextareaField label="福利待遇" value={form.benefits} onChange={(v) => onFormChange("benefits", v)} />
    </div>
  );
}

function JobEditPanel({
  job, departmentId, categoryId, saving, onSave, onCancel,
}: {
  job: Job;
  departmentId: string;
  categoryId: string;
  saving: boolean;
  onSave: (input: JobInput, extra: JobUiExtra) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<JobInput>({
    title: job.title,
    companyName: job.companyName,
    location: job.location,
    salaryRange: job.salaryRange ?? "",
    description: job.description,
    requirements: job.requirements,
    skills: job.skills,
    experienceLevel: job.experienceLevel,
    education: job.education,
    jobType: job.jobType || "全职",
    niceToHaves: job.niceToHaves ?? "",
    benefits: job.benefits ?? "",
  });
  const [deptId, setDeptId] = useState(departmentId);
  const [catId, setCatId] = useState(categoryId);

  useEffect(() => {
    setForm({
      title: job.title,
      companyName: job.companyName,
      location: job.location,
      salaryRange: job.salaryRange ?? "",
      description: job.description,
      requirements: job.requirements,
      skills: job.skills,
      experienceLevel: job.experienceLevel,
      education: job.education,
      jobType: job.jobType || "全职",
      niceToHaves: job.niceToHaves ?? "",
      benefits: job.benefits ?? "",
    });
    setDeptId(departmentId);
    setCatId(categoryId);
  }, [job, departmentId, categoryId]);

  return (
    <div>
      <div className="mb-4 flex items-center justify-between border-b border-[#e0eaf5] pb-3">
        <h2 className="m-0 text-base font-bold text-[#173568]">编辑职位</h2>
        <button className="text-[13px] font-semibold text-[#36527f] hover:text-[#0874e8]" type="button" onClick={onCancel}>
          取消
        </button>
      </div>
      <JobFormFields
        form={form}
        departmentId={deptId}
        categoryId={catId}
        onFormChange={(key, value) => setForm((prev) => ({ ...prev, [key]: value }))}
        onDepartmentChange={setDeptId}
        onCategoryChange={setCatId}
      />
      <div className="mt-4 flex justify-end gap-3 border-t border-[#eaf1fa] pt-4">
        <button className="outline-button" type="button" onClick={onCancel} disabled={saving}>取消</button>
        <button
          className="primary-button"
          type="button"
          disabled={saving || !form.title.trim()}
          onClick={() => onSave(form, { departmentId: deptId, categoryId: catId })}
        >
          {saving ? <><Loader2 className="animate-spin" size={16} /> 保存中...</> : "保存"}
        </button>
      </div>
    </div>
  );
}

function JobEditModal({
  job, defaultCompanyName, saving, onSave, onClose,
}: {
  job: Job | null;
  defaultCompanyName: string;
  saving: boolean;
  onSave: (input: JobInput, extra: JobUiExtra) => void;
  onClose: () => void;
}) {
  const [form, setForm] = useState<JobInput>({
    title: job?.title ?? "",
    companyName: job?.companyName || defaultCompanyName,
    location: job?.location ?? "",
    salaryRange: job?.salaryRange ?? "",
    description: job?.description ?? "",
    requirements: job?.requirements ?? "",
    skills: job?.skills ?? "",
    experienceLevel: job?.experienceLevel ?? "",
    education: job?.education ?? "",
    jobType: job?.jobType ?? "全职",
    niceToHaves: job?.niceToHaves ?? "",
    benefits: job?.benefits ?? "",
  });
  const [deptId, setDeptId] = useState("");
  const [catId, setCatId] = useState("");

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.title.trim()) return;
    onSave(
      { ...form, companyName: form.companyName || defaultCompanyName || "企业" },
      { departmentId: deptId, categoryId: catId },
    );
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" onClick={onClose}>
      <div className="max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-xl bg-white p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <h2 className="mb-4 mt-0 text-lg font-bold text-[#173568]">{job ? "编辑职位" : "新建职位"}</h2>
        <form onSubmit={handleSubmit} className="space-y-3">
          <JobFormFields
            form={form}
            departmentId={deptId}
            categoryId={catId}
            onFormChange={(key, value) => setForm((prev) => ({ ...prev, [key]: value }))}
            onDepartmentChange={setDeptId}
            onCategoryChange={setCatId}
          />
          <div className="flex justify-end gap-3 pt-2">
            <button className="outline-button" type="button" onClick={onClose} disabled={saving}>取消</button>
            <button className="primary-button" type="submit" disabled={saving || !form.title.trim()}>
              {saving ? <><Loader2 className="animate-spin" size={16} /> 保存中...</> : "保存"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function Field({ label, required, value, onChange, placeholder }: {
  label: string; required?: boolean; value: string; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-[#36527f]">
        {label}{required && <span className="text-[#dc2626]"> *</span>}
      </span>
      <input
        className="h-10 w-full rounded-lg border border-[#bdd3ef] px-3 text-sm text-[#132e61] outline-none focus:border-[#0874e8]"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
    </label>
  );
}

function TextareaField({ label, value, onChange, placeholder }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-[#36527f]">{label}</span>
      <textarea
        className="min-h-[80px] w-full rounded-lg border border-[#bdd3ef] px-3 py-2 text-sm text-[#132e61] outline-none focus:border-[#0874e8]"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
    </label>
  );
}

function ConfirmModal({
  title, message, confirmLabel = "确认", danger = false, onConfirm, onCancel,
}: {
  title: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" onClick={onCancel}>
      <div className="w-full max-w-sm rounded-xl bg-white p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <h3 className="mb-2 mt-0 text-base font-bold text-[#173568]">{title}</h3>
        <p className="mb-4 text-sm text-[#4d6388]">{message}</p>
        <div className="flex justify-end gap-3">
          <button className="outline-button" type="button" onClick={onCancel}>取消</button>
          <button
            className={danger
              ? "rounded-lg bg-[#dc2626] px-4 py-2 text-sm font-semibold text-white hover:bg-[#b91c1c]"
              : "primary-button"}
            type="button"
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

function formatDate(iso: string): string {
  if (!iso) return "--";
  const d = new Date(iso);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function pad(n: number): string {
  return n.toString().padStart(2, "0");
}

function statusLabel(status: string): string {
  switch (status) {
    case "ACTIVE": return "招聘中";
    case "CLOSED": return "已结束";
    case "PENDING": return "待发布";
    case "DRAFT": return "草稿";
    default: return status;
  }
}

function findDeptName(nodes: DeptNode[], id: string): string | null {
  for (const node of nodes) {
    if (node.id === id) return node.name;
    if (node.children) {
      const found = findDeptName(node.children, id);
      if (found) return found;
    }
  }
  return null;
}

function countAdvanced(filters: AdvancedFilters): number {
  let count = 0;
  (Object.keys(filters) as (keyof AdvancedFilters)[]).forEach((key) => {
    const value = filters[key];
    if (Array.isArray(value)) {
      if (value.length > 0) count += 1;
    } else if (String(value).trim()) {
      count += 1;
    }
  });
  return count;
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
