"use client";

// 产品运营页面：管理试用规则、套餐、积分包、AI功能规则
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";
import { Package, Plus, Trash2 } from "lucide-react";

type ProductKind = "trial-rules" | "plans" | "credit-packs" | "ai-feature-rules";

const kinds: { key: ProductKind; label: string }[] = [
  { key: "trial-rules", label: "试用规则" },
  { key: "plans", label: "套餐" },
  { key: "credit-packs", label: "积分包" },
  { key: "ai-feature-rules", label: "AI功能规则" },
];

export default function ProductsPage() {
  const [activeKind, setActiveKind] = useState<ProductKind>("plans");
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_PRODUCT_EDIT");

  const { data: products, isLoading } = useQuery({
    queryKey: ["products", activeKind],
    queryFn: () => adminApiFetch<any[]>(`/platform/recruitment/${activeKind}`),
  });

  const retireMutation = useMutation({
    mutationFn: (id: string) =>
      adminApiFetch(`/platform/recruitment/${activeKind}/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["products", activeKind] }),
  });

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">产品运营</h1>
        <p className="mt-1 text-sm text-slate-500">管理平台商业化产品配置</p>
      </div>

      {/* 类型切换 */}
      <div className="mb-4 flex gap-2">
        {kinds.map((k) => (
          <button
            key={k.key}
            onClick={() => setActiveKind(k.key)}
            className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
              activeKind === k.key ? "bg-blue-600 text-white" : "bg-slate-100 text-slate-600 hover:bg-slate-200"
            }`}
          >
            {k.label}
          </button>
        ))}
      </div>

      {/* 产品列表 */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : !products || products.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无产品</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">名称/编码</th>
                <th className="px-4 py-3">版本</th>
                <th className="px-4 py-3">价格</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {products.map((p: any, i: number) => (
                <tr key={p.id ?? i} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Package className="h-4 w-4 text-slate-400" />
                      <span className="text-sm font-medium text-slate-800">{p.display_name ?? p.code ?? "未命名"}</span>
                    </div>
                    {p.code && <span className="text-xs text-slate-400">{p.code}</span>}
                  </td>
                  <td className="px-4 py-3 text-sm text-slate-600">v{p.version ?? "-"}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">
                    {p.price_micro_yuan != null ? `¥${(p.price_micro_yuan / 1_000_000).toFixed(2)}` : "-"}
                  </td>
                  <td className="px-4 py-3">
                    <Badge variant={p.status === "ACTIVE" ? "success" : "neutral"}>
                      {p.status === "ACTIVE" ? "上架" : "下架"}
                    </Badge>
                  </td>
                  <td className="px-4 py-3">
                    {canEdit && p.status === "ACTIVE" && (
                      <button
                        onClick={() => p.id && retireMutation.mutate(p.id)}
                        disabled={retireMutation.isPending}
                        className="flex items-center gap-1 text-xs text-red-600 hover:text-red-700"
                      >
                        <Trash2 className="h-3 w-3" /> 下架
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
