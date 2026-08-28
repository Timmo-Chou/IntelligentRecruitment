"use client";

import {
  BriefcaseBusiness, Building2, CalendarPlus, ChevronDown, ChevronLeft,
  ChevronRight, CircleDot, Edit3, Loader2, MapPin, Plus, Search,
  TimerReset, Trash2, AlertCircle, Inbox,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { AppShell } from "@/components/layout/app-shell";
import { useWorkspace } from "@/lib/workspace-context";
import {
  fetchJobStats, fetchJobs, fetchJob, createJob, updateJob, deleteJob,
  batchUpdateStatus, batchDelete, updateJobStatus,
  readJobsCache, writeJobsCache, upsertJobInCache, removeJobFromCache,
  type Job, type JobInput, type JobStats,
} from "@/lib/job-api";

// 状态筛选选项
const STATUS_OPTIONS = [
  { label: "全部状态", value: "" },
  { label: "招聘中", value: "ACTIVE" },
  { label: "已关闭", value: "CLOSED" },
  { label: "草稿", value: "DRAFT" },
];

const PAGE_SIZE_OPTIONS = [10, 20, 50];

export default function JobsPage() {
  const { workspaceId, workspace, loading: wsLoading, notAuthenticated, error: wsError, refresh: refreshWorkspace } = useWorkspace();

  // 未登录则跳转登录页
  useEffect(() => {
    if (notAuthenticated) {
      window.location.replace("/login");
    }
  }, [notAuthenticated]);

  // 数据状态
  const [stats, setStats] = useState<JobStats | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [total, setTotal] = useState(0);
  const [dataLoading, setDataLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 筛选分页状态
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  // 选中与详情
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [detailJob, setDetailJob] = useState<Job | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // 编辑弹窗
  const [editOpen, setEditOpen] = useState(false);
  const [editJob, setEditJob] = useState<Job | null>(null);
  const [editSaving, setEditSaving] = useState(false);

  // 删除确认
  const [deleteConfirm, setDeleteConfirm] = useState<Job | null>(null);
  const [batchConfirm, setBatchConfirm] = useState<"publish" | "delete" | null>(null);
  const [batchBusy, setBatchBusy] = useState(false);

  // 搜索防抖
  const [searchInput, setSearchInput] = useState("");
  const loadRequestRef = useRef(0);
  const hydratedWorkspaceRef = useRef<string | null>(null);

  // 离开页面再回来时先恢复最近一次成功列表，避免空表闪一下或覆盖刚保存的草稿
  useEffect(() => {
    if (!workspaceId || hydratedWorkspaceRef.current === workspaceId) return;
    hydratedWorkspaceRef.current = workspaceId;
    const cached = readJobsCache(workspaceId);
    if (!cached) return;
    if (cached.search === search && cached.status === statusFilter && cached.page === page && cached.pageSize === pageSize) {
      setStats(cached.stats);
      setJobs(cached.items);
      setTotal(cached.total);
    }
  }, [workspaceId, search, statusFilter, page, pageSize]);

  // 加载数据
  const loadData = useCallback(async () => {
    if (!workspaceId) return;
    const requestId = ++loadRequestRef.current;
    setDataLoading(true);
    setError(null);
    try {
      const [statsRes, jobsRes] = await Promise.all([
        fetchJobStats(workspaceId),
        fetchJobs(workspaceId, { search: search || undefined, status: statusFilter || undefined, page, pageSize }),
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
        status: statusFilter,
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
  }, [workspaceId, search, statusFilter, page, pageSize]);

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

  // 搜索防抖：仅在关键词真正变化时重置页码，避免进入页面就触发多余刷新
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch((prev) => {
        if (prev !== searchInput) setPage(1);
        return searchInput;
      });
    }, 400);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // 查看详情
  const handleViewDetail = async (job: Job) => {
    if (!workspaceId) return;
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

  // 打开编辑弹窗
  const handleOpenEdit = (job?: Job) => {
    setEditJob(job ?? null);
    setEditOpen(true);
  };

  const syncJob = (saved: Job) => {
    if (workspaceId) upsertJobInCache(workspaceId, saved);
    setJobs((prev) => {
      const without = prev.filter((item) => item.id !== saved.id);
      if (statusFilter && saved.status !== statusFilter) return without;
      return [saved, ...without];
    });
    setDetailJob((prev) => (!prev || prev.id === saved.id ? saved : prev));
  };

  // 保存编辑
  const handleSaveEdit = async (input: JobInput) => {
    if (!workspaceId) return;
    setEditSaving(true);
    try {
      const saved = editJob?.id
        ? await updateJob(workspaceId, editJob.id, input)
        : await createJob(workspaceId, input);
      setEditOpen(false);
      setEditJob(null);
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

  // 删除
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

  // 切换选中
  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const allSelected = jobs.length > 0 && jobs.every((job) => selectedIds.has(job.id));

  const toggleSelectAll = () => {
    if (allSelected) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(jobs.map((j) => j.id)));
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const hasFilters = Boolean(search || statusFilter);

  // 加载中状态
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

  // 加载出错状态（优先于未选择 workspace 展示）
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

  // 未登录或未选择 workspace
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
    <AppShell activeItem="职位库">
      {/* 标题栏 */}
      <section className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div className="flex items-baseline gap-4">
          <h1 className="m-0 text-[25px] font-bold tracking-tight text-[#09245d]">职位库</h1>
          <p className="m-0 text-sm text-[#55709d]">
            {workspace?.name ?? "当前工作空间"} · 管理和搜索企业职位信息
          </p>
        </div>
      </section>

      {/* 搜索与操作栏 */}
      <section className="mb-4 grid gap-3 xl:grid-cols-[minmax(280px,1fr)_140px_auto_auto_auto]">
        <label className="flex h-11 items-center gap-3 rounded-lg border border-[#bdd3ef] bg-white px-4 text-[#6b80a4] shadow-sm">
          <Search size={18} />
          <input
            className="min-w-0 flex-1 border-0 bg-transparent text-sm outline-none"
            placeholder="搜索职位名称、企业、地点、技能"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
        </label>
        <div className="relative">
          <select
            className="h-11 w-full appearance-none rounded-lg border border-[#bdd3ef] bg-white px-4 text-sm text-[#36527f] shadow-sm outline-none"
            value={statusFilter}
            onChange={(e) => { setStatusFilter(e.target.value); setPage(1); }}
          >
            {STATUS_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
          <ChevronDown size={15} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-[#6b80a4]" />
        </div>
        <button className="primary-button" type="button" onClick={() => handleOpenEdit()}>
          <Plus size={16} /> 新建职位
        </button>
        <button
          className="outline-button"
          type="button"
          disabled={!allSelected || batchBusy}
          onClick={() => setBatchConfirm("publish")}
        >
          批量发布
        </button>
        <button
          className="outline-button text-[#dc2626]"
          type="button"
          disabled={!allSelected || batchBusy}
          onClick={() => setBatchConfirm("delete")}
        >
          批量删除
        </button>
      </section>

      <div className="grid gap-3 2xl:grid-cols-[minmax(700px,1fr)_420px]">
        <div className="min-w-0">
          {/* 统计卡片 */}
          <section className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4" aria-label="职位概览">
            {stats ? (
              <>
                <MetricCard label="职位总数" value={stats.total} Icon={BriefcaseBusiness} />
                <MetricCard label="招聘中" value={stats.active} Icon={TimerReset} />
                <MetricCard label="已关闭" value={stats.closed} Icon={CircleDot} />
                <MetricCard label="草稿" value={stats.draft} Icon={CalendarPlus} />
              </>
            ) : (
              <div className="col-span-4 flex items-center justify-center py-6">
                <Loader2 className="animate-spin text-[#6b80a4]" size={20} />
              </div>
            )}
          </section>

          {/* 职位表格 */}
          <section className="overflow-hidden rounded-xl border border-[#d6e5f5] bg-white shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
            {error && (
              <div className="flex items-center gap-2 bg-[#fef2f2] px-4 py-3 text-sm text-[#dc2626]">
                <AlertCircle size={16} /> {error}
                <button className="ml-auto text-[#0874e8] underline" onClick={loadData}>重试</button>
              </div>
            )}

            {dataLoading && jobs.length === 0 ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="animate-spin text-[#0874e8]" size={28} />
                <span className="ml-3 text-sm text-[#55709d]">加载职位列表...</span>
              </div>
            ) : jobs.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-16 text-[#6b80a4]">
                <Inbox size={48} className="mb-3 opacity-40" />
                <p className="text-sm font-medium">{hasFilters ? "没有符合筛选条件的职位" : "暂无职位数据"}</p>
                <p className="mt-1 text-xs">
                  {hasFilters ? "试试清空搜索或切换到「全部状态」查看草稿" : "点击「新建职位」开始创建第一个职位"}
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
                          <input
                            type="checkbox"
                            aria-label="选择全部职位"
                            checked={allSelected}
                            onChange={toggleSelectAll}
                          />
                        </th>
                        {["职位名称", "企业名称", "工作地点", "创建时间", "更新时间", "状态", "操作"].map((item) => (
                          <th key={item} className="border-b border-[#dbe8f6] px-3 py-3 font-medium">{item}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {jobs.map((job) => (
                        <tr
                          key={job.id}
                          className={detailJob?.id === job.id ? "bg-[#eafff7]" : "hover:bg-[#f8fbff]"}
                        >
                          <td className="border-b border-[#eaf1fa] px-3 py-3">
                            <input
                              type="checkbox"
                              aria-label={`选择${job.title}`}
                              checked={selectedIds.has(job.id)}
                              onChange={() => toggleSelect(job.id)}
                            />
                          </td>
                          <td className="border-b border-[#eaf1fa] px-3 py-3 font-semibold text-[#132e61]">
                            <button
                              className="text-left hover:text-[#0874e8] hover:underline"
                              onClick={() => handleViewDetail(job)}
                              type="button"
                            >
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
                              关闭
                            </button>
                            <span className="mx-1 text-[#c4d3e8]">|</span>
                            <button className="hover:underline" onClick={() => setDeleteConfirm(job)} type="button">删除</button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {/* 分页 */}
                <footer className="flex items-center justify-between px-4 py-4 text-xs text-[#4d6388]">
                  <span>共 {total} 条</span>
                  <div className="flex items-center gap-2">
                    <button
                      className="page-button"
                      disabled={page <= 1}
                      onClick={() => setPage((p) => Math.max(1, p - 1))}
                      type="button"
                    >
                      <ChevronLeft size={14} />
                    </button>
                    {buildPageNumbers(page, totalPages).map((p, i) =>
                      p === "..." ? (
                        <span key={`dots-${i}`} className="px-1 text-[#8fa3c0]">…</span>
                      ) : (
                        <button
                          key={p}
                          className={page === p ? "page-active" : "page-button"}
                          onClick={() => setPage(p as number)}
                          type="button"
                        >
                          {p}
                        </button>
                      ),
                    )}
                    <button
                      className="page-button"
                      disabled={page >= totalPages}
                      onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                      type="button"
                    >
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

        {/* 详情面板 */}
        <aside className="rounded-xl border border-[#d6e5f5] bg-white p-4 shadow-[0_6px_20px_rgba(30,92,160,0.04)]">
          {detailLoading ? (
            <div className="flex items-center justify-center py-16">
              <Loader2 className="animate-spin text-[#0874e8]" size={24} />
            </div>
          ) : detailJob ? (
            <>
              <div className="flex items-center justify-between border-b border-[#e0eaf5] pb-3">
                <h2 className="m-0 text-base font-bold text-[#173568]">职位详情</h2>
                <div className="flex gap-2">
                  <button className="outline-button !h-8 !px-3 text-[#0874e8]" type="button" onClick={() => handleOpenEdit(detailJob)}>
                    <Edit3 size={14} /> 编辑
                  </button>
                </div>
              </div>
              <div className="py-4">
                <div className="flex items-center gap-3">
                  <h3 className="m-0 text-xl font-bold text-[#102d64]">{detailJob.title}</h3>
                  <span className={`rounded-md px-2 py-1 text-xs font-semibold ${detailJob.status === "ACTIVE" ? "bg-[#dff8ee] text-[#07945f]" : detailJob.status === "CLOSED" ? "bg-[#fee2e2] text-[#dc2626]" : "bg-[#f0f4fa] text-[#6b80a4]"}`}>
                    {statusLabel(detailJob.status)}
                  </span>
                </div>
                <p className="mb-2 mt-3 flex items-center gap-2 text-sm font-semibold">
                  <Building2 size={16} /> {detailJob.companyName}
                </p>
                <p className="m-0 flex flex-wrap items-center gap-3 text-xs text-[#4b6793]">
                  <span className="flex items-center gap-1"><MapPin size={14} /> {detailJob.location}</span>
                  {detailJob.experienceLevel && <span>{detailJob.experienceLevel}</span>}
                  {detailJob.education && <span>{detailJob.education}</span>}
                  {detailJob.jobType && <span>{detailJob.jobType}</span>}
                </p>
              </div>
              {detailJob.description && (
                <DetailSection title="职位描述">
                  {detailJob.description.split("\n").filter(Boolean).map((line, i) => (
                    <p key={i} className="m-0 mb-1">{line}</p>
                  ))}
                </DetailSection>
              )}
              {detailJob.requirements && (
                <DetailSection title="任职要求">
                  <ul className="m-0 list-disc pl-4">
                    {detailJob.requirements.split("\n").filter(Boolean).map((line, i) => (
                      <li key={i}>{line}</li>
                    ))}
                  </ul>
                </DetailSection>
              )}
              {detailJob.skills && (
                <DetailSection title="关键技能">
                  <div className="flex flex-wrap gap-2">
                    {detailJob.skills.split(/[,，\s]+/).filter(Boolean).map((skill) => (
                      <span key={skill} className="skill-tag">{skill}</span>
                    ))}
                  </div>
                </DetailSection>
              )}
              <DetailSection title="创建信息">
                <p>创建时间：{formatDate(detailJob.createdAt)}　更新时间：{formatDate(detailJob.updatedAt)}</p>
              </DetailSection>
              <div className="mt-4 grid grid-cols-1 gap-3 rounded-xl border border-[#deebf7] bg-[#f9fcff] p-4 sm:grid-cols-3">
                <button className="primary-button" type="button" onClick={() => alert("AI招聘助手功能即将上线")}>
                  AI招聘助手
                </button>
                {detailJob.status === "ACTIVE" ? (
                  <button className="outline-button" type="button" onClick={() => void handleChangeStatus(detailJob, "CLOSED")}>
                    关闭职位
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

      {/* 编辑弹窗 */}
      {editOpen && (
        <JobEditModal
          job={editJob}
          saving={editSaving}
          onSave={handleSaveEdit}
          onClose={() => { setEditOpen(false); setEditJob(null); }}
        />
      )}

      {/* 删除确认弹窗 */}
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
          message={`将发布当前页已全选的 ${selectedIds.size} 个职位为招聘中，确认继续？`}
          confirmLabel={batchBusy ? "发布中..." : "确认发布"}
          onConfirm={() => void handleBatchPublish()}
          onCancel={() => !batchBusy && setBatchConfirm(null)}
        />
      )}

      {batchConfirm === "delete" && (
        <ConfirmModal
          title="批量删除"
          message={`将删除当前页已全选的 ${selectedIds.size} 个职位，此操作不可撤销。`}
          confirmLabel={batchBusy ? "删除中..." : "确认删除"}
          danger
          onConfirm={() => void handleBatchDelete()}
          onCancel={() => !batchBusy && setBatchConfirm(null)}
        />
      )}
    </AppShell>
  );
}

// --- 子组件 ---

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

function JobEditModal({
  job, saving, onSave, onClose,
}: {
  job: Job | null;
  saving: boolean;
  onSave: (input: JobInput) => void;
  onClose: () => void;
}) {
  const [form, setForm] = useState<JobInput>({
    title: job?.title ?? "",
    companyName: job?.companyName ?? "",
    location: job?.location ?? "",
    description: job?.description ?? "",
    requirements: job?.requirements ?? "",
    skills: job?.skills ?? "",
    experienceLevel: job?.experienceLevel ?? "",
    education: job?.education ?? "",
    jobType: job?.jobType ?? "全职",
  });

  const update = (key: keyof JobInput, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.title.trim() || !form.companyName.trim()) return;
    onSave(form);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" onClick={onClose}>
      <div
        className="max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-xl bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-4 mt-0 text-lg font-bold text-[#173568]">
          {job ? "编辑职位" : "新建职位"}
        </h2>
        <form onSubmit={handleSubmit} className="space-y-3">
          <Field label="职位名称" required value={form.title} onChange={(v) => update("title", v)} />
          <Field label="企业名称" required value={form.companyName} onChange={(v) => update("companyName", v)} />
          <Field label="工作地点" value={form.location} onChange={(v) => update("location", v)} />
          <div className="grid grid-cols-3 gap-3">
            <Field label="经验要求" value={form.experienceLevel} onChange={(v) => update("experienceLevel", v)} placeholder="如：5年以上" />
            <Field label="学历要求" value={form.education} onChange={(v) => update("education", v)} placeholder="如：本科及以上" />
            <Field label="工作类型" value={form.jobType} onChange={(v) => update("jobType", v)} placeholder="如：全职" />
          </div>
          <TextareaField label="职位描述" value={form.description} onChange={(v) => update("description", v)} />
          <TextareaField label="任职要求" value={form.requirements} onChange={(v) => update("requirements", v)} placeholder="每行一条要求" />
          <TextareaField label="关键技能" value={form.skills} onChange={(v) => update("skills", v)} placeholder="逗号或空格分隔" />
          <div className="flex justify-end gap-3 pt-2">
            <button className="outline-button" type="button" onClick={onClose} disabled={saving}>取消</button>
            <button className="primary-button" type="submit" disabled={saving || !form.title.trim() || !form.companyName.trim()}>
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

// --- 工具函数 ---

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
    case "CLOSED": return "已关闭";
    case "DRAFT": return "草稿";
    default: return status;
  }
}

function buildPageNumbers(current: number, total: number): (number | "...")[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1);
  }
  const pages: (number | "...")[] = [1];
  if (current > 3) pages.push("...");
  const start = Math.max(2, current - 1);
  const end = Math.min(total - 1, current + 1);
  for (let i = start; i <= end; i++) pages.push(i);
  if (current < total - 2) pages.push("...");
  pages.push(total);
  return pages;
}
