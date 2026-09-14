"use client";

// 管理端定价配置页面
// 功能：查看所有计费项、编辑单价/描述、启用/停用
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Receipt,
  Edit3,
  Check,
  X,
  ToggleLeft,
  ToggleRight,
  Loader2,
  Save,
  AlertCircle,
} from "lucide-react";
import { adminApiFetch } from "@/lib/admin-api-client";

/** 计费单位文案映射 */
const UNIT_LABEL: Record<string, string> = {
  PER_USE: "按次",
  PER_ITEM: "按份",
  PER_CANDIDATE: "按候选人",
};

/** 分 → 元 格式化 */
function formatYuan(minor: number): string {
  return (minor / 100).toFixed(2);
}

type PricingItem = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  billingUnit: string;
  unitPriceMinor: number;
  currency: string;
  status: string; // ACTIVE | DISABLED
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
};

export default function PricingPage() {
  const { data, isLoading, error, refetch } = useQuery<PricingItem[]>({
    queryKey: ["pricing-list"],
    queryFn: () => adminApiFetch<PricingItem[]>("/platform/pricing"),
  });

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-blue-500" />
        <span className="ml-2 text-slate-500">加载定价配置...</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-xl border border-red-200 bg-red-50 p-6 text-center">
        <AlertCircle className="mx-auto mb-2 h-8 w-8 text-red-500" />
        <p className="text-red-600">加载失败：{(error as Error).message}</p>
        <button
          onClick={() => refetch()}
          className="mt-3 rounded-lg bg-red-500 px-4 py-1.5 text-sm text-white hover:bg-red-600"
        >
          重试
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 页面标题 */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="flex items-center gap-2 text-2xl font-bold text-slate-800">
            <Receipt className="h-6 w-6 text-blue-600" />
            定价配置
          </h2>
          <p className="mt-1 text-sm text-slate-500">
            管理各计费功能的单价和启用状态，修改后立即生效
          </p>
        </div>
      </div>

      {/* 卡片列表 */}
      <div className="grid gap-4 lg:grid-cols-2">
        {data?.map((item) => (
          <PricingCard key={item.code} item={item} />
        ))}
      </div>
    </div>
  );
}

/** 单个计费项卡片 */
function PricingCard({ item }: { item: PricingItem }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [statusLoading, setStatusLoading] = useState(false);

  // 编辑表单状态
  const [formName, setFormName] = useState(item.name);
  const [formDesc, setFormDesc] = useState(item.description ?? "");
  const [formPriceFen, setFormPriceFen] = useState(String(item.unitPriceMinor));
  const [formSort, setFormSort] = useState(String(item.sortOrder));

  // 外部 item 变化时同步（比如保存后）
  useEffect(() => {
    if (!editing) {
      setFormName(item.name);
      setFormDesc(item.description ?? "");
      setFormPriceFen(String(item.unitPriceMinor));
      setFormSort(String(item.sortOrder));
    }
  }, [item, editing]);

  const isActive = item.status === "ACTIVE";

  async function handleSave() {
    const priceMinor = parseInt(formPriceFen, 10);
    const sortOrder = parseInt(formSort, 10);
    if (isNaN(priceMinor) || priceMinor < 0) {
      alert("请输入有效的单价（分，不能为负）");
      return;
    }

    setSaving(true);
    try {
      await adminApiFetch<PricingItem>(`/platform/pricing/${item.code}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: formName.trim(),
          description: formDesc.trim() || null,
          unitPriceMinor: priceMinor,
          sortOrder: isNaN(sortOrder) ? 0 : sortOrder,
        }),
      });
      setEditing(false);
      qc.invalidateQueries({ queryKey: ["pricing-list"] });
    } catch (e) {
      alert("保存失败：" + (e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function handleToggleStatus() {
    setStatusLoading(true);
    try {
      const nextStatus = isActive ? "DISABLED" : "ACTIVE";
      // 通过 PUT /{code} 带 status 字段切换（与编辑价格共用同一个端点）
      await adminApiFetch<PricingItem>(`/platform/pricing/${item.code}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: nextStatus }),
      });
      qc.invalidateQueries({ queryKey: ["pricing-list"] });
    } catch (e) {
      alert("操作失败：" + (e as Error).message);
    } finally {
      setStatusLoading(false);
    }
  }

  function handleCancel() {
    // 还原表单
    setFormName(item.name);
    setFormDesc(item.description ?? "");
    setFormPriceFen(String(item.unitPriceMinor));
    setFormSort(String(item.sortOrder));
    setEditing(false);
  }

  return (
    <div
      className={
        "rounded-xl border bg-white p-5 shadow-sm transition-colors " +
        (isActive ? "border-slate-200" : "border-slate-200 opacity-75")
      }
    >
      {/* 头部：名称 + 状态 + 开关 */}
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            {editing ? (
              <input
                value={formName}
                onChange={(e) => setFormName(e.target.value)}
                className="flex-1 rounded-md border border-blue-300 px-2 py-1 text-base font-semibold focus:outline-none focus:ring-2 focus:ring-blue-200"
              />
            ) : (
              <h3 className="truncate text-base font-semibold text-slate-800">{item.name}</h3>
            )}
            <span
              className={
                "shrink-0 rounded-full px-2 py-0.5 text-xs font-medium " +
                (isActive
                  ? "bg-green-100 text-green-700"
                  : "bg-slate-200 text-slate-500")
              }
            >
              {isActive ? "已启用" : "已停用"}
            </span>
          </div>
          <p className="mt-0.5 text-xs text-slate-400">
            代码：<code className="rounded bg-slate-100 px-1 text-slate-600">{item.code}</code>
            <span className="mx-1.5">·</span>
            {UNIT_LABEL[item.billingUnit] ?? item.billingUnit}
          </p>
        </div>

        {/* 状态开关 */}
        <button
          onClick={handleToggleStatus}
          disabled={statusLoading}
          className="shrink-0 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 disabled:opacity-50"
          title={isActive ? "点击停用" : "点击启用"}
        >
          {statusLoading ? (
            <Loader2 className="h-5 w-5 animate-spin" />
          ) : isActive ? (
            <ToggleRight className="h-6 w-6 text-green-500" />
          ) : (
            <ToggleLeft className="h-6 w-6 text-slate-400" />
          )}
        </button>
      </div>

      {/* 价格区域 */}
      <div className="mt-4 flex items-baseline gap-2">
        {editing ? (
          <div className="flex items-center gap-2">
            <span className="text-lg font-semibold text-slate-400">¥</span>
            <input
              type="number"
              value={formPriceFen}
              onChange={(e) => setFormPriceFen(e.target.value)}
              className="w-28 rounded-md border border-blue-300 px-2 py-1 text-2xl font-bold focus:outline-none focus:ring-2 focus:ring-blue-200"
            />
            <span className="text-sm text-slate-400">分</span>
            <span className="text-sm text-slate-400">
              （¥{formatYuan(parseInt(formPriceFen || "0", 10))}）
            </span>
          </div>
        ) : (
          <>
            <span className="text-2xl font-bold text-slate-800">
              ¥{formatYuan(item.unitPriceMinor)}
            </span>
            <span className="text-sm text-slate-400">
              （{item.unitPriceMinor} 分 / {UNIT_LABEL[item.billingUnit] ?? item.billingUnit}）
            </span>
          </>
        )}
      </div>

      {/* 描述 */}
      <div className="mt-3">
        {editing ? (
          <textarea
            value={formDesc}
            onChange={(e) => setFormDesc(e.target.value)}
            rows={2}
            placeholder="功能说明（可选）"
            className="w-full resize-none rounded-md border border-blue-300 px-2 py-1 text-sm text-slate-600 focus:outline-none focus:ring-2 focus:ring-blue-200"
          />
        ) : (
          <p className="text-sm text-slate-500">
            {item.description || <span className="text-slate-300">暂无说明</span>}
          </p>
        )}
      </div>

      {/* 排序（仅编辑时显示） */}
      {editing && (
        <div className="mt-3 flex items-center gap-2 text-sm">
          <span className="text-slate-500">排序：</span>
          <input
            type="number"
            value={formSort}
            onChange={(e) => setFormSort(e.target.value)}
            className="w-20 rounded-md border border-blue-300 px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-200"
          />
        </div>
      )}

      {/* 操作按钮 */}
      <div className="mt-4 flex items-center justify-between border-t border-slate-100 pt-3">
        <span className="text-xs text-slate-400">
          更新于 {new Date(item.updatedAt).toLocaleString("zh-CN")}
        </span>

        {editing ? (
          <div className="flex gap-2">
            <button
              onClick={handleCancel}
              disabled={saving}
              className="flex items-center gap-1 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-50"
            >
              <X className="h-4 w-4" /> 取消
            </button>
            <button
              onClick={handleSave}
              disabled={saving}
              className="flex items-center gap-1 rounded-lg bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {saving ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Save className="h-4 w-4" />
              )}
              保存
            </button>
          </div>
        ) : (
          <button
            onClick={() => setEditing(true)}
            className="flex items-center gap-1 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-blue-50 hover:text-blue-600"
          >
            <Edit3 className="h-4 w-4" /> 编辑
          </button>
        )}
      </div>
    </div>
  );
}
