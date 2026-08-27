"use client";

// 账本管理页面：搜索 + 账本条目 + 调整表单
import { Search, Wallet } from "lucide-react";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type LedgerEntry = {
  id: string;
  workspaceId: string;
  workspaceName: string;
  amount: number;
  type: string;
  reference: string;
  reason: string;
  createdAt: string;
};

// 调整表单校验
const adjustmentSchema = z.object({
  workspaceId: z.string().min(1, "请输入工作空间ID"),
  amount: z.coerce.number().refine((v) => v !== 0, "金额不能为0"),
  reference: z.string().min(1, "请输入凭证号"),
  reason: z.string().min(1, "请输入调整原因"),
});

type AdjustmentForm = z.infer<typeof adjustmentSchema>;

export default function BillingPage() {
  const queryClient = useQueryClient();
  const [searchId, setSearchId] = useState("");
  const [searchTrigger, setSearchTrigger] = useState("");

  const { data: entries, isLoading } = useQuery({
    queryKey: ["billing", searchTrigger],
    queryFn: async () => {
      const params = new URLSearchParams();
      if (searchTrigger) params.set("workspaceId", searchTrigger);
      params.set("pageSize", "50");
      return adminApiFetch<{ items: LedgerEntry[] }>(`/platform/billing?${params.toString()}`);
    },
    enabled: !!searchTrigger || searchTrigger === "",
  });

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
      adminApiFetch("/platform/billing/adjust", {
        method: "POST",
        body: JSON.stringify(data),
      }),
    onSuccess: () => {
      reset();
      queryClient.invalidateQueries({ queryKey: ["billing"] });
    },
  });

  function getTypeBadge(type: string) {
    if (type === "INCOME") return <Badge variant="success">收入</Badge>;
    if (type === "EXPENSE") return <Badge variant="danger">支出</Badge>;
    if (type === "ADJUSTMENT") return <Badge variant="warning">调整</Badge>;
    return <Badge>{type}</Badge>;
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">账本管理</h1>
        <p className="mt-1 text-sm text-slate-500">查看和管理账本流水</p>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_380px]">
        {/* 左侧：账本条目 */}
        <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
          <div className="border-b border-slate-200 p-4">
            <div className="flex gap-3">
              <div className="relative flex-1">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <Input
                  placeholder="输入工作空间ID搜索…"
                  value={searchId}
                  onChange={(e) => setSearchId(e.target.value)}
                  className="pl-10"
                />
              </div>
              <Button onClick={() => setSearchTrigger(searchId)}>搜索</Button>
            </div>
          </div>
          <div className="overflow-x-auto">
            {isLoading ? (
              <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
            ) : entries?.items && entries.items.length > 0 ? (
              <table className="w-full">
                <thead>
                  <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                    <th className="px-4 py-3">工作空间</th>
                    <th className="px-4 py-3">金额</th>
                    <th className="px-4 py-3">类型</th>
                    <th className="px-4 py-3">凭证号</th>
                    <th className="px-4 py-3">原因</th>
                    <th className="px-4 py-3">时间</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.items.map((entry) => (
                    <tr key={entry.id} className="border-b border-slate-50">
                      <td className="px-4 py-3 text-sm text-slate-600">{entry.workspaceName}</td>
                      <td
                        className={`px-4 py-3 text-sm font-medium ${
                          entry.amount >= 0 ? "text-green-600" : "text-red-600"
                        }`}
                      >
                        {entry.amount >= 0 ? "+" : ""}
                        {entry.amount.toFixed(2)}
                      </td>
                      <td className="px-4 py-3">{getTypeBadge(entry.type)}</td>
                      <td className="px-4 py-3 text-sm text-slate-500">{entry.reference}</td>
                      <td className="px-4 py-3 text-sm text-slate-500">{entry.reason}</td>
                      <td className="px-4 py-3 text-sm text-slate-500">{entry.createdAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : searchTrigger ? (
              <div className="p-8 text-center text-sm text-slate-400">暂无账本记录</div>
            ) : (
              <div className="p-8 text-center text-sm text-slate-400">请输入工作空间ID进行搜索</div>
            )}
          </div>
        </section>

        {/* 右侧：调整表单 */}
        <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-700">
            <Wallet className="h-5 w-5 text-blue-500" />
            余额调整
          </h2>
          <form
            onSubmit={handleSubmit((data) => adjustmentMutation.mutate(data))}
            className="space-y-4"
          >
            <div>
              <label className="mb-2 block text-sm font-medium text-slate-700">工作空间ID</label>
              <Input {...register("workspaceId")} placeholder="请输入工作空间ID" />
              {errors.workspaceId && (
                <p className="mt-1 text-xs text-red-500">{errors.workspaceId.message}</p>
              )}
            </div>
            <div>
              <label className="mb-2 block text-sm font-medium text-slate-700">
                金额（正数为收入，负数为支出）
              </label>
              <Input {...register("amount")} type="number" step="0.01" placeholder="例如: 100.00 或 -50.00" />
              {errors.amount && (
                <p className="mt-1 text-xs text-red-500">{errors.amount.message}</p>
              )}
            </div>
            <div>
              <label className="mb-2 block text-sm font-medium text-slate-700">凭证号</label>
              <Input {...register("reference")} placeholder="请输入凭证号" />
              {errors.reference && (
                <p className="mt-1 text-xs text-red-500">{errors.reference.message}</p>
              )}
            </div>
            <div>
              <label className="mb-2 block text-sm font-medium text-slate-700">调整原因</label>
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

            <Button type="submit" className="w-full" disabled={adjustmentMutation.isPending}>
              {adjustmentMutation.isPending ? "处理中…" : "提交调整"}
            </Button>
          </form>
        </section>
      </div>
    </div>
  );
}