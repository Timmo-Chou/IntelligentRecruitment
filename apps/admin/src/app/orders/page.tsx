"use client";

// 订单管理页面：个人订单 + 企业合同订单。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";

type PersonalOrder = { id: string; order_no: string; user_id: string; product_id: string; amount: number; payment_status: string; created_at: string };
type PersonalOrderPage = { items: PersonalOrder[]; total: number; page: number; size: number };
type ContractOrder = { id: string; tenant_id: string | null; legal_name: string; unified_social_credit_code: string; contract_number: string; status: string; plan_product_id: string; plan_product_name?: string; seat_count: number; price_micro_yuan: number; starts_at: string; ends_at: string; created_at: string; updated_at: string };
type ProductPlan = { id: string; audience: string; code: string; display_name: string; status: string };
type Tenant = { id: string; legal_name: string; unified_social_credit_code: string; display_name: string; status: string };
type TenantPage = { items: Tenant[]; total: number; page: number; size: number };
type ContractForm = { legalName: string; creditCode: string; contractNumber: string; planId: string; seatCount: string; priceMicroYuan: string; startsAt: string; endsAt: string };
const emptyContractForm: ContractForm = { legalName: "", creditCode: "", contractNumber: "", planId: "", seatCount: "", priceMicroYuan: "", startsAt: "", endsAt: "" };

function errorMessage(error: unknown) { return error instanceof Error ? error.message : "操作失败，请稍后重试"; }
function statusLabel(status: string) { const labels: Record<string, string> = { PENDING: "待处理", PENDING_BINDING: "待绑定企业", READY_TO_ACTIVATE: "待激活", ACTIVE: "已生效", ENDED: "已结束", CANCELLED: "已取消", PAID: "已支付", FAILED: "支付失败" }; return labels[status] ?? status; }
function statusVariant(status: string): "success" | "warning" | "danger" | "neutral" { if (status === "ACTIVE" || status === "PAID") return "success"; if (["PENDING_BINDING", "READY_TO_ACTIVATE", "PENDING"].includes(status)) return "warning"; if (status === "FAILED") return "danger"; return "neutral"; }

export default function OrdersPage() {
  const [activeTab, setActiveTab] = useState<"PERSONAL" | "CONTRACT">("PERSONAL");
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(1);
  const [showContractForm, setShowContractForm] = useState(false);
  const [contractForm, setContractForm] = useState<ContractForm>(emptyContractForm);
  const [bindTenantByOrder, setBindTenantByOrder] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_ORDER_EDIT");

  const personalOrders = useQuery({
    queryKey: ["orders", statusFilter, page], enabled: activeTab === "PERSONAL",
    queryFn: () => { const params = new URLSearchParams(); if (statusFilter) params.set("status", statusFilter); params.set("page", String(page)); params.set("size", "20"); return adminApiFetch<PersonalOrderPage>(`/platform/recruitment/personal-orders?${params.toString()}`); },
  });
  const contractOrders = useQuery({ queryKey: ["contract-orders"], enabled: activeTab === "CONTRACT", queryFn: () => adminApiFetch<ContractOrder[]>("/platform/recruitment/contract-orders") });
  const plans = useQuery({ queryKey: ["contract-order-plans"], enabled: activeTab === "CONTRACT" && showContractForm, queryFn: () => adminApiFetch<ProductPlan[]>("/platform/recruitment/plans") });
  const tenants = useQuery({
    queryKey: ["contract-order-tenants"],
    enabled: activeTab === "CONTRACT" && (showContractForm || (Array.isArray(contractOrders.data) && contractOrders.data.some((order) => order.status === "PENDING_BINDING"))),
    queryFn: () => adminApiFetch<TenantPage>("/platform/recruitment/tenants?status=ACTIVE&page=1&size=100"),
  });

  const updatePaymentMutation = useMutation({
    mutationFn: ({ orderId, status }: { orderId: string; status: string }) => adminApiFetch(`/platform/recruitment/personal-orders/${orderId}/payment-status`, { method: "POST", body: JSON.stringify({ status }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["orders"] }), onError: (error) => setMessage(errorMessage(error)),
  });
  const createContractMutation = useMutation({
    mutationFn: () => {
      if (!contractForm.legalName.trim() || !contractForm.creditCode.trim() || !contractForm.contractNumber.trim() || !contractForm.planId || !contractForm.seatCount || !contractForm.priceMicroYuan || !contractForm.startsAt || !contractForm.endsAt) throw new Error("请完整填写合同订单信息");
      const startsAt = new Date(contractForm.startsAt); const endsAt = new Date(contractForm.endsAt); if (!(endsAt > startsAt)) throw new Error("合同结束时间必须晚于开始时间");
      return adminApiFetch("/platform/recruitment/contract-orders", { method: "POST", body: JSON.stringify({ legalName: contractForm.legalName.trim(), creditCode: contractForm.creditCode.trim(), contractNumber: contractForm.contractNumber.trim(), planId: contractForm.planId, seatCount: Number(contractForm.seatCount), priceMicroYuan: Number(contractForm.priceMicroYuan), startsAt: startsAt.toISOString(), endsAt: endsAt.toISOString() }) });
    },
    onSuccess: () => { setMessage("合同订单已创建"); setContractForm(emptyContractForm); setShowContractForm(false); queryClient.invalidateQueries({ queryKey: ["contract-orders"] }); }, onError: (error) => setMessage(errorMessage(error)),
  });
  const bindContractMutation = useMutation({
    mutationFn: ({ tenantId }: { tenantId: string }) => { if (!tenantId) throw new Error("请选择要绑定的企业"); return adminApiFetch(`/platform/recruitment/tenants/${tenantId}/bind-contract-orders`, { method: "POST" }); },
    onSuccess: () => { setMessage("合同订单已提交绑定"); queryClient.invalidateQueries({ queryKey: ["contract-orders"] }); }, onError: (error) => setMessage(errorMessage(error)),
  });
  const activateContractMutation = useMutation({
    mutationFn: (orderId: string) => adminApiFetch(`/platform/recruitment/contract-orders/${orderId}/activate`, { method: "POST" }),
    onSuccess: () => { setMessage("合同已激活"); queryClient.invalidateQueries({ queryKey: ["contract-orders"] }); }, onError: (error) => setMessage(errorMessage(error)),
  });

  const personalItems = personalOrders.data?.items ?? [];
  const totalPages = Math.ceil((personalOrders.data?.total ?? 0) / 20);
  const contractItems = contractOrders.data ?? [];
  function selectTab(tab: "PERSONAL" | "CONTRACT") { setActiveTab(tab); setMessage(null); setStatusFilter(""); setPage(1); }
  const inputClass = "mt-1 w-full rounded-lg border border-slate-300 px-3 py-2";

  return <div>
    <div className="mb-6 flex items-start justify-between"><div><h1 className="text-2xl font-bold text-slate-800">订单管理</h1><p className="mt-1 text-sm text-slate-500">管理个人订单和企业合同订单</p></div>{activeTab === "CONTRACT" && canEdit && <button onClick={() => { setMessage(null); setShowContractForm((value) => !value); }} className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700">{showContractForm ? "关闭创建" : "创建合同订单"}</button>}</div>
    <div className="mb-4 flex gap-2"><button onClick={() => selectTab("PERSONAL")} className={`rounded-lg px-4 py-2 text-sm font-medium ${activeTab === "PERSONAL" ? "bg-blue-600 text-white" : "bg-slate-100 text-slate-600"}`}>个人订单</button><button onClick={() => selectTab("CONTRACT")} className={`rounded-lg px-4 py-2 text-sm font-medium ${activeTab === "CONTRACT" ? "bg-blue-600 text-white" : "bg-slate-100 text-slate-600"}`}>企业合同订单</button></div>
    {message && <div className={`mb-4 rounded-lg px-4 py-2 text-sm ${message.includes("失败") || message.includes("必须") || message.includes("请") ? "bg-red-50 text-red-700" : "bg-green-50 text-green-700"}`}>{message}</div>}
    {activeTab === "PERSONAL" ? <>
      <div className="mb-4 flex gap-3"><select value={statusFilter} onChange={(e) => { setStatusFilter(e.target.value); setPage(1); }} className="h-10 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700"><option value="">全部状态</option><option value="PENDING">待支付</option><option value="PAID">已支付</option><option value="FAILED">支付失败</option><option value="CANCELLED">已取消</option></select></div>
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">{personalOrders.isLoading ? <div className="p-8 text-center text-sm text-slate-400">加载中…</div> : personalOrders.isError ? <div className="p-8 text-center text-sm text-red-600">加载失败：{errorMessage(personalOrders.error)}</div> : personalItems.length === 0 ? <div className="p-8 text-center text-sm text-slate-400">暂无订单</div> : <table className="w-full"><thead><tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold text-slate-500"><th className="px-4 py-3">订单号</th><th className="px-4 py-3">用户ID</th><th className="px-4 py-3">金额</th><th className="px-4 py-3">支付状态</th><th className="px-4 py-3">创建时间</th><th className="px-4 py-3">操作</th></tr></thead><tbody>{personalItems.map((order) => <tr key={order.id} className="border-b border-slate-100 hover:bg-slate-50"><td className="px-4 py-3 text-sm font-mono text-slate-600">{order.order_no ?? order.id}</td><td className="px-4 py-3 text-sm text-slate-600">{order.user_id}</td><td className="px-4 py-3 text-sm font-medium text-slate-800">¥{(order.amount ?? 0).toFixed(2)}</td><td className="px-4 py-3"><Badge variant={statusVariant(order.payment_status)}>{statusLabel(order.payment_status)}</Badge></td><td className="px-4 py-3 text-sm text-slate-500">{order.created_at}</td><td className="px-4 py-3">{order.payment_status === "PENDING" && canEdit && <button onClick={() => updatePaymentMutation.mutate({ orderId: order.id, status: "PAID" })} className="text-xs text-green-600 hover:text-green-700">标记已支付</button>}</td></tr>)}</tbody></table>}</div>
      {totalPages > 1 && <div className="mt-4 flex items-center justify-between"><p className="text-sm text-slate-500">共 {personalOrders.data?.total ?? 0} 条，第 {page}/{totalPages} 页</p><div className="flex gap-2"><button onClick={() => setPage((value) => Math.max(1, value - 1))} disabled={page <= 1} className="rounded-lg border border-slate-300 px-3 py-1 text-sm disabled:opacity-40">上一页</button><button onClick={() => setPage((value) => Math.min(totalPages, value + 1))} disabled={page >= totalPages} className="rounded-lg border border-slate-300 px-3 py-1 text-sm disabled:opacity-40">下一页</button></div></div>}
    </> : <>
      {showContractForm && canEdit && <div className="mb-6 rounded-xl border border-blue-100 bg-blue-50/40 p-6 shadow-sm"><h2 className="mb-4 text-base font-semibold text-slate-700">创建企业合同订单</h2><div className="grid grid-cols-1 gap-4 md:grid-cols-2"><label className="text-sm text-slate-700">企业名称<input value={contractForm.legalName} onChange={(e) => setContractForm({ ...contractForm, legalName: e.target.value })} className={inputClass} /></label><label className="text-sm text-slate-700">统一社会信用代码<input value={contractForm.creditCode} onChange={(e) => setContractForm({ ...contractForm, creditCode: e.target.value })} className={inputClass} /></label><label className="text-sm text-slate-700">合同编号<input value={contractForm.contractNumber} onChange={(e) => setContractForm({ ...contractForm, contractNumber: e.target.value })} className={inputClass} /></label><label className="text-sm text-slate-700">企业套餐<select value={contractForm.planId} onChange={(e) => setContractForm({ ...contractForm, planId: e.target.value })} className={inputClass}><option value="">请选择已发布的企业套餐</option>{(plans.data ?? []).filter((plan) => plan.audience === "ENTERPRISE" && plan.status === "PUBLISHED").map((plan) => <option key={plan.id} value={plan.id}>{plan.display_name}（{plan.code}）</option>)}</select></label><label className="text-sm text-slate-700">席位数<input type="number" min="1" value={contractForm.seatCount} onChange={(e) => setContractForm({ ...contractForm, seatCount: e.target.value })} className={inputClass} /></label><label className="text-sm text-slate-700">金额（微元）<input type="number" min="0" value={contractForm.priceMicroYuan} onChange={(e) => setContractForm({ ...contractForm, priceMicroYuan: e.target.value })} className={inputClass} /></label><label className="text-sm text-slate-700">开始时间<input type="datetime-local" value={contractForm.startsAt} onChange={(e) => setContractForm({ ...contractForm, startsAt: e.target.value })} className={inputClass} /></label><label className="text-sm text-slate-700">结束时间<input type="datetime-local" value={contractForm.endsAt} onChange={(e) => setContractForm({ ...contractForm, endsAt: e.target.value })} className={inputClass} /></label></div><button onClick={() => createContractMutation.mutate()} disabled={createContractMutation.isPending} className="mt-5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">{createContractMutation.isPending ? "提交中…" : "创建合同订单"}</button></div>}
      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">{contractOrders.isLoading ? <div className="p-8 text-center text-sm text-slate-400">加载中…</div> : contractOrders.isError ? <div className="p-8 text-center text-sm text-red-600">加载失败：{errorMessage(contractOrders.error)}</div> : contractItems.length === 0 ? <div className="p-8 text-center text-sm text-slate-400">暂无合同订单</div> : <table className="w-full"><thead><tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold text-slate-500"><th className="px-4 py-3">企业/信用代码</th><th className="px-4 py-3">合同编号</th><th className="px-4 py-3">套餐</th><th className="px-4 py-3">席位/金额</th><th className="px-4 py-3">合同周期</th><th className="px-4 py-3">状态</th><th className="px-4 py-3">操作</th></tr></thead><tbody>{contractItems.map((order) => <tr key={order.id} className="border-b border-slate-100 hover:bg-slate-50"><td className="px-4 py-3 text-sm text-slate-700"><div className="font-medium">{order.legal_name}</div><div className="text-xs text-slate-400">{order.unified_social_credit_code}</div></td><td className="px-4 py-3 text-sm font-mono text-slate-600">{order.contract_number}</td><td className="px-4 py-3 text-sm text-slate-600">{order.plan_product_name ?? order.plan_product_id}</td><td className="px-4 py-3 text-sm text-slate-600">{order.seat_count} 席 / ¥{(Number(order.price_micro_yuan) / 1_000_000).toFixed(2)}</td><td className="px-4 py-3 text-xs text-slate-500">{order.starts_at}<br />至 {order.ends_at}</td><td className="px-4 py-3"><Badge variant={statusVariant(order.status)}>{statusLabel(order.status)}</Badge>{order.tenant_id && <div className="mt-1 text-xs text-slate-400">Tenant: {order.tenant_id}</div>}</td><td className="px-4 py-3 text-xs">{order.status === "PENDING_BINDING" && canEdit && <div className="flex min-w-52 gap-2"><select value={bindTenantByOrder[order.id] ?? ""} onChange={(e) => setBindTenantByOrder({ ...bindTenantByOrder, [order.id]: e.target.value })} className="rounded border border-slate-300 px-2 py-1"><option value="">选择企业</option>{(tenants.data?.items ?? []).map((tenant) => <option key={tenant.id} value={tenant.id}>{tenant.legal_name || tenant.display_name}</option>)}</select><button onClick={() => bindContractMutation.mutate({ tenantId: bindTenantByOrder[order.id] ?? "" })} className="text-blue-600 hover:text-blue-700">绑定</button></div>}{order.status === "READY_TO_ACTIVATE" && canEdit && <button onClick={() => activateContractMutation.mutate(order.id)} className="text-green-600 hover:text-green-700">激活</button>}</td></tr>)}</tbody></table>}</div>
    </>}
  </div>;
}
