"use client";

// 账本管理页面：企业 → 工作空间级联选择 + 账本流水 + 调整
import { Search, Wallet, Building2, Briefcase, ChevronDown, X, Loader2, Coins, Clock } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

// —— 类型定义 ——
type CompanySummary = {
  companyId: string;
  companyName: string;
  shortName: string;
  verificationStatus: string;
  managementStatus: string;
  memberCount: number;
  createdAt: string;
};

type CompanyDetail = {
  companyId: string;
  legalName: string;
  displayName: string;
  verificationStatus: string;
  managementStatus: string;
  workspaces: {
    workspaceId: string;
    workspaceName: string;
    status: string;
    memberCount: number;
  }[];
};

type LedgerEntry = {
  id: string;
  entryType: string;
  amountMinor: number;
  businessReference: string;
  reason: string;
  createdAt: string;
  operatorName: string;
};

type LedgerPage = {
  items: LedgerEntry[];
  total: number;
  page: number;
  pageSize: number;
};

// 账户余额视图（管理员视角）
type AdminCreditLot = {
  id: string;
  sourceType: string; // TRIAL / MANUAL_ADJUSTMENT
  originalAmountMinor: number;
  availableAmountMinor: number;
  expiresAt: string;
  status: string;
};

type AdminBillingView = {
  currency: string;
  availableAmountMinor: number;
  reservedAmountMinor: number;
  creditLots: AdminCreditLot[];
};

// 调整表单校验（后端以分 minor 为单位，前端以元为单位，×100 提交）
const adjustmentSchema = z.object({
  amount: z.coerce.number().refine((v) => v !== 0, "金额不能为 0"),
  reference: z.string().min(1, "请输入凭证号"),
  reason: z.string().min(1, "请输入调整原因"),
});

type AdjustmentForm = z.infer<typeof adjustmentSchema>;

// —— 企业搜索下拉组件（带防抖） ——
function CompanyCombobox({
  selectedId,
  onSelect,
  onClear,
}: {
  selectedId: string | null;
  onSelect: (c: CompanySummary) => void;
  onClear: () => void;
}) {
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  // 显示的标签
  const [label, setLabel] = useState("");

  // 点击外部关闭
  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, []);

  // 防抖 300ms
  useEffect(() => {
    const t = setTimeout(() => setDebounced(query), 300);
    return () => clearTimeout(t);
  }, [query]);

  // 当选中时显示名称
  useEffect(() => {
    if (selectedId) {
      setQuery("");
    }
  }, [selectedId]); // eslint-disable-line react-hooks/exhaustive-deps

  const { data, isFetching } = useQuery({
    queryKey: ["billing-companies", debounced],
    queryFn: async () => {
      const params = new URLSearchParams();
      if (debounced) params.set("search", debounced);
      params.set("pageSize", "20");
      const res = await adminApiFetch<{ items: CompanySummary[] }>(
        `/platform/companies?${params.toString()}`,
      );
      return res.items;
    },
    enabled: !selectedId, // 选中后不再搜索
  });

  if (selectedId) {
    return (
      <div className="relative" ref={wrapperRef}>
        <div className="flex h-10 items-center justify-between rounded-lg border border-slate-300 bg-white px-3">
          <div className="flex items-center gap-2 text-sm text-slate-700">
            <Building2 className="h-4 w-4 text-blue-500" />
            <span className="font-medium">{label || "已选择企业"}</span>
          </div>
          <button
            type="button"
            onClick={() => {
              onClear();
              setLabel("");
            }}
            className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        {/* 隐藏的 label 透传选中值 */}
        <input type="hidden" value={selectedId} />
      </div>
    );
  }

  const items = data ?? [];

  return (
    <div className="relative" ref={wrapperRef}>
      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
        <Input
          placeholder="输入企业名称搜索…"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          className="pl-10"
        />
        {isFetching && (
          <Loader2 className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 animate-spin text-slate-400" />
        )}
      </div>
      {open && (
        <div className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-lg border border-slate-200 bg-white shadow-lg">
          {items.length === 0 ? (
            <div className="px-3 py-2 text-sm text-slate-400">
              {debounced ? "未找到匹配企业" : "请输入企业名称搜索"}
            </div>
          ) : (
            items.map((c) => (
              <button
                key={c.companyId}
                type="button"
                onClick={() => {
                  onSelect(c);
                  setLabel(c.companyName || c.shortName);
                  setOpen(false);
                }}
                className="block w-full px-3 py-2 text-left text-sm hover:bg-blue-50"
              >
                <div className="font-medium text-slate-800">{c.companyName}</div>
                {c.shortName && (
                  <div className="text-xs text-slate-400">简称：{c.shortName}</div>
                )}
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}

// —— 主页面 ——
export default function BillingPage() {
  const queryClient = useQueryClient();

  const [selectedCompany, setSelectedCompany] = useState<CompanySummary | null>(null);
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<string | null>(null);

  // 加载企业详情（含 workspace 列表）
  const { data: companyDetail } = useQuery({
    queryKey: ["billing-company-detail", selectedCompany?.companyId],
    queryFn: async () =>
      adminApiFetch<CompanyDetail>(`/platform/companies/${selectedCompany!.companyId}`),
    enabled: !!selectedCompany,
  });

  // 自动选中唯一的 workspace
  useEffect(() => {
    if (companyDetail && companyDetail.workspaces.length > 0) {
      if (companyDetail.workspaces.length === 1) {
        setSelectedWorkspaceId(companyDetail.workspaces[0].workspaceId);
      } else if (
        selectedWorkspaceId &&
        !companyDetail.workspaces.some((w) => w.workspaceId === selectedWorkspaceId)
      ) {
        setSelectedWorkspaceId(null);
      }
    } else {
      setSelectedWorkspaceId(null);
    }
  }, [companyDetail]); // eslint-disable-line react-hooks/exhaustive-deps

  // 查询账户余额和额度批次
  const { data: billingView, isLoading: billingLoading } = useQuery({
    queryKey: ["billing-view", selectedWorkspaceId],
    queryFn: async () =>
      adminApiFetch<AdminBillingView>(`/platform/workspaces/${selectedWorkspaceId}/billing`),
    enabled: !!selectedWorkspaceId,
  });

  // 查询账本流水
  const { data: ledger, isLoading: ledgerLoading } = useQuery({
    queryKey: ["billing-ledger", selectedWorkspaceId],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("workspaceId", selectedWorkspaceId!);
      params.set("pageSize", "100");
      return adminApiFetch<LedgerPage>(`/platform/billing?${params.toString()}`);
    },
    enabled: !!selectedWorkspaceId,
  });

  // 调整表单
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<AdjustmentForm>({
    resolver: zodResolver(adjustmentSchema),
  });

  const adjustmentMutation = useMutation({
    mutationFn: (data: AdjustmentForm) =>
      adminApiFetch(
        `/platform/workspaces/${selectedWorkspaceId}/billing/adjustments`,
        {
          method: "POST",
          body: JSON.stringify({
            // 元 → 分
            amountMinor: Math.round(data.amount * 100),
            reference: data.reference,
            reason: data.reason,
          }),
        },
      ),
    onSuccess: () => {
      reset();
      queryClient.invalidateQueries({ queryKey: ["billing-ledger", "billing-view"] });
    },
  });

  function getTypeBadge(type: string) {
    if (type === "INCOME" || type === "GRANT") return <Badge variant="success">收入</Badge>;
    if (type === "EXPENSE" || type === "SETTLEMENT") return <Badge variant="danger">支出</Badge>;
    if (type === "ADJUSTMENT") return <Badge variant="warning">调整</Badge>;
    if (type === "RESERVE") return <Badge>冻结</Badge>;
    if (type === "RELEASE") return <Badge variant="success">释放</Badge>;
    if (type === "EXPIRE") return <Badge variant="neutral">过期</Badge>;
    return <Badge>{type}</Badge>;
  }

  // 元单位格式化
  function formatYuan(amountMinor: number) {
    const yuan = amountMinor / 100;
    return `${yuan >= 0 ? "+" : ""}${yuan.toFixed(2)}`;
  }

  // 当前选中的 workspace
  const selectedWorkspace = companyDetail?.workspaces.find(
    (w) => w.workspaceId === selectedWorkspaceId,
  );

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">账本管理</h1>
        <p className="mt-1 text-sm text-slate-500">按企业 → 工作空间查询与调整账本流水</p>
      </div>

      {/* 级联选择器 */}
      <section className="mb-6 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="grid gap-4 md:grid-cols-2">
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">1. 选择企业</label>
            <CompanyCombobox
              selectedId={selectedCompany?.companyId ?? null}
              onSelect={(c) => setSelectedCompany(c)}
              onClear={() => {
                setSelectedCompany(null);
                setSelectedWorkspaceId(null);
              }}
            />
          </div>
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">2. 选择工作空间</label>
            {!selectedCompany ? (
              <div className="flex h-10 items-center rounded-lg border border-dashed border-slate-300 bg-slate-50 px-3 text-sm text-slate-400">
                请先选择企业
              </div>
            ) : !companyDetail ? (
              <div className="flex h-10 items-center rounded-lg border border-dashed border-slate-300 bg-slate-50 px-3 text-sm text-slate-400">
                加载中…
              </div>
            ) : companyDetail.workspaces.length === 0 ? (
              <div className="flex h-10 items-center rounded-lg border border-dashed border-slate-300 bg-slate-50 px-3 text-sm text-slate-400">
                该企业暂无工作空间
              </div>
            ) : (
              <div className="relative">
                <Briefcase className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <div className="relative">
                  <select
                    value={selectedWorkspaceId ?? ""}
                    onChange={(e) => setSelectedWorkspaceId(e.target.value || null)}
                    className="flex h-10 w-full appearance-none items-center rounded-lg border border-slate-300 bg-white px-3 pl-10 pr-10 text-sm text-slate-900 focus:border-brand focus:outline-none focus:ring-3 focus:ring-brand/10"
                  >
                    <option value="" disabled>
                      {companyDetail.workspaces.length === 1
                        ? "已自动选中"
                        : "请选择工作空间"}
                    </option>
                    {companyDetail.workspaces.map((w) => (
                      <option key={w.workspaceId} value={w.workspaceId}>
                        {w.workspaceName} ({w.status})
                      </option>
                    ))}
                  </select>
                  <ChevronDown className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                </div>
              </div>
            )}
          </div>
        </div>

        {selectedWorkspace && (
          <div className="mt-4 flex flex-wrap items-center gap-4 rounded-lg bg-blue-50 px-4 py-2 text-xs text-slate-600">
            <span>
              <span className="text-slate-400">企业：</span>
              <span className="font-medium">{selectedCompany?.companyName}</span>
            </span>
            <span>
              <span className="text-slate-400">工作空间：</span>
              <span className="font-medium">{selectedWorkspace.workspaceName}</span>
            </span>
            <span>
              <span className="text-slate-400">成员数：</span>
              <span className="font-medium">{selectedWorkspace.memberCount}</span>
            </span>
          </div>
        )}

        {/* 余额详情卡片 */}
        {selectedWorkspaceId && billingView && !billingLoading && (
          <div className="mt-4 grid gap-4 sm:grid-cols-3">
            {/* 可用余额 */}
            <div className="rounded-lg border border-green-200 bg-green-50 p-4">
              <div className="flex items-center gap-2 text-sm text-green-700">
                <Coins className="h-4 w-4" />
                <span>可用余额</span>
              </div>
              <p className="mt-2 text-2xl font-bold text-green-700">
                ¥{(billingView.availableAmountMinor / 100).toFixed(2)}
              </p>
            </div>
            {/* 冻结金额 */}
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-4">
              <div className="flex items-center gap-2 text-sm text-amber-700">
                <Clock className="h-4 w-4" />
                <span>冻结金额</span>
              </div>
              <p className="mt-2 text-2xl font-bold text-amber-700">
                ¥{(billingView.reservedAmountMinor / 100).toFixed(2)}
              </p>
            </div>
            {/* 总额 */}
            <div className="rounded-lg border border-blue-200 bg-blue-50 p-4">
              <div className="flex items-center gap-2 text-sm text-blue-700">
                <Wallet className="h-4 w-4" />
                <span>总额</span>
              </div>
              <p className="mt-2 text-2xl font-bold text-blue-700">
                ¥{((billingView.availableAmountMinor + billingView.reservedAmountMinor) / 100).toFixed(2)}
              </p>
            </div>
          </div>
        )}

        {/* 额度批次明细 */}
        {selectedWorkspaceId && billingView && !billingLoading && billingView.creditLots.length > 0 && (
          <div className="mt-4">
            <h3 className="mb-2 text-sm font-semibold text-slate-700">额度批次明细</h3>
            <div className="grid gap-3 sm:grid-cols-2">
              {/* 试用额度 */}
              {(() => {
                const trialLots = billingView.creditLots.filter((l) => l.sourceType === "TRIAL");
                const trialTotal = trialLots.reduce((s, l) => s + l.availableAmountMinor, 0);
                if (trialLots.length === 0) return null;
                return (
                  <div className="rounded-lg border border-slate-200 bg-white p-4">
                    <div className="mb-2 flex items-center justify-between">
                      <span className="text-sm font-medium text-slate-600">试用额度</span>
                      <span className="text-lg font-bold text-purple-600">
                        ¥{(trialTotal / 100).toFixed(2)}
                      </span>
                    </div>
                    {trialLots.map((lot) => (
                      <div key={lot.id} className="flex items-center justify-between border-t border-slate-100 py-2 text-xs">
                        <span className="text-slate-500">到期: {new Date(lot.expiresAt).toLocaleDateString("zh-CN")}</span>
                        <span className="text-slate-700">¥{(lot.availableAmountMinor / 100).toFixed(2)}</span>
                      </div>
                    ))}
                  </div>
                );
              })()}
              {/* 充值/调整额度 */}
              {(() => {
                const manualLots = billingView.creditLots.filter((l) => l.sourceType === "MANUAL_ADJUSTMENT");
                const manualTotal = manualLots.reduce((s, l) => s + l.availableAmountMinor, 0);
                if (manualLots.length === 0) return null;
                return (
                  <div className="rounded-lg border border-slate-200 bg-white p-4">
                    <div className="mb-2 flex items-center justify-between">
                      <span className="text-sm font-medium text-slate-600">充值额度</span>
                      <span className="text-lg font-bold text-indigo-600">
                        ¥{(manualTotal / 100).toFixed(2)}
                      </span>
                    </div>
                    {manualLots.map((lot) => (
                      <div key={lot.id} className="flex items-center justify-between border-t border-slate-100 py-2 text-xs">
                        <span className="text-slate-500">到期: {new Date(lot.expiresAt).toLocaleDateString("zh-CN")}</span>
                        <span className="text-slate-700">¥{(lot.availableAmountMinor / 100).toFixed(2)}</span>
                      </div>
                    ))}
                  </div>
                );
              })()}
              {billingView.creditLots.filter((l) => l.sourceType !== "TRIAL" && l.sourceType !== "MANUAL_ADJUSTMENT").map((lot) => (
                <div key={lot.id} className="rounded-lg border border-slate-200 bg-white p-4">
                  <div className="mb-2 flex items-center justify-between">
                    <span className="text-sm font-medium text-slate-600">{lot.sourceType}</span>
                    <span className="text-lg font-bold text-slate-700">
                      ¥{(lot.availableAmountMinor / 100).toFixed(2)}
                    </span>
                  </div>
                  <div className="flex items-center justify-between border-t border-slate-100 py-2 text-xs">
                    <span className="text-slate-500">到期: {new Date(lot.expiresAt).toLocaleDateString("zh-CN")}</span>
                    <span className="text-slate-700">¥{(lot.availableAmountMinor / 100).toFixed(2)}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
        {selectedWorkspaceId && billingView && !billingLoading && billingView.creditLots.length === 0 && (
          <div className="mt-4 rounded-lg border border-dashed border-slate-300 bg-slate-50 p-4 text-center text-sm text-slate-400">
            暂无有效额度批次
          </div>
        )}
      </section>

      <div className="grid gap-6 lg:grid-cols-[1fr_380px]">
        {/* 左侧：账本条目 */}
        <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
          <div className="border-b border-slate-200 p-4">
            <h2 className="text-base font-bold text-slate-700">账本流水</h2>
          </div>
          <div className="overflow-x-auto">
            {!selectedWorkspaceId ? (
              <div className="p-8 text-center text-sm text-slate-400">
                请选择企业和工作空间以查看账本记录
              </div>
            ) : ledgerLoading ? (
              <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
            ) : ledger && ledger.items.length > 0 ? (
              <table className="w-full">
                <thead>
                  <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                    <th className="px-4 py-3">类型</th>
                    <th className="px-4 py-3">金额</th>
                    <th className="px-4 py-3">凭证号</th>
                    <th className="px-4 py-3">原因</th>
                    <th className="px-4 py-3">操作人</th>
                    <th className="px-4 py-3">时间</th>
                  </tr>
                </thead>
                <tbody>
                  {ledger.items.map((entry) => (
                    <tr key={entry.id} className="border-b border-slate-50">
                      <td className="px-4 py-3">{getTypeBadge(entry.entryType)}</td>
                      <td
                        className={`px-4 py-3 text-sm font-medium ${
                          entry.amountMinor >= 0 ? "text-green-600" : "text-red-600"
                        }`}
                      >
                        {formatYuan(entry.amountMinor)}
                      </td>
                      <td className="px-4 py-3 text-sm text-slate-500">
                        {entry.businessReference || "—"}
                      </td>
                      <td className="px-4 py-3 text-sm text-slate-500">
                        {entry.reason || "—"}
                      </td>
                      <td className="px-4 py-3 text-sm text-slate-500">
                        {entry.operatorName || "系统"}
                      </td>
                      <td className="px-4 py-3 text-sm text-slate-500">
                        {new Date(entry.createdAt).toLocaleString("zh-CN")}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <div className="p-8 text-center text-sm text-slate-400">该工作空间暂无账本记录</div>
            )}
          </div>
        </section>

        {/* 右侧：调整表单 */}
        <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-700">
            <Wallet className="h-5 w-5 text-blue-500" />
            余额调整
          </h2>
          {!selectedWorkspaceId ? (
            <div className="rounded-lg bg-slate-50 px-4 py-6 text-center text-sm text-slate-400">
              请先选择企业和工作空间
            </div>
          ) : (
            <form
              onSubmit={handleSubmit((data) => adjustmentMutation.mutate(data))}
              className="space-y-4"
            >
              <div className="rounded-lg bg-blue-50 px-3 py-2 text-xs text-slate-600">
                目标：
                <span className="font-medium">
                  {selectedWorkspace?.workspaceName}
                </span>
              </div>
              <div>
                <label className="mb-2 block text-sm font-medium text-slate-700">
                  金额（元，正数为收入，负数为支出）
                </label>
                <Input
                  {...register("amount")}
                  type="number"
                  step="0.01"
                  placeholder="例如: 100.00 或 -50.00"
                />
                {errors.amount && (
                  <p className="mt-1 text-xs text-red-500">{errors.amount.message}</p>
                )}
              </div>
              <div>
                <label className="mb-2 block text-sm font-medium text-slate-700">
                  凭证号
                </label>
                <Input {...register("reference")} placeholder="请输入凭证号" />
                {errors.reference && (
                  <p className="mt-1 text-xs text-red-500">{errors.reference.message}</p>
                )}
              </div>
              <div>
                <label className="mb-2 block text-sm font-medium text-slate-700">
                  调整原因
                </label>
                <textarea
                  {...register("reason")}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
                  rows={2}
                  placeholder="请输入调整原因…"
                />
                {errors.reason && (
                  <p className="mt-1 text-xs text-red-500">{errors.reason.message}</p>
                )}
              </div>

              {adjustmentMutation.error && (
                <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
                  {(adjustmentMutation.error as Error).message}
                </div>
              )}
              {adjustmentMutation.isSuccess && (
                <div className="rounded-lg bg-green-50 px-4 py-3 text-sm text-green-700">
                  调整成功
                </div>
              )}

              <Button
                type="submit"
                className="w-full"
                disabled={adjustmentMutation.isPending}
              >
                {adjustmentMutation.isPending ? "处理中…" : "提交调整"}
              </Button>
            </form>
          )}
        </section>
      </div>
    </div>
  );
}
