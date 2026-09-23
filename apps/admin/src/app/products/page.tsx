"use client";

// 产品运营页面：管理试用规则、套餐、积分包、AI 功能规则。
// 所有配置均通过 BOSS 商业化接口提交，不在前端维护模拟商品或权限数据。
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { adminApiFetch, ApiError } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Package, Plus, Trash2, X } from "lucide-react";

type ProductKind = "trial-rules" | "plans" | "credit-packs" | "ai-feature-rules";

type ProductRow = {
  id?: string;
  status?: string;
  [key: string]: unknown;
};

type PermissionCatalogItem = {
  code: string;
  module_code?: string;
  description?: string;
  status?: string;
  frontend?: "USER" | "ADMIN";
  product_domain?: "RECRUITMENT" | "OPEN_API";
};

type ProductForm = {
  version: string;
  durationDays: string;
  seatCapacity: string;
  creditAmount: string;
  audience: "ENTERPRISE" | "PERSONAL";
  code: string;
  displayName: string;
  billingPeriod: "MONTH" | "QUARTER" | "YEAR";
  durationMonths: string;
  perSeatCreditAmount: string;
  priceMicroYuan: string;
  validUnit: "MONTH" | "YEAR";
  validCount: string;
  capabilityCode: string;
  permissionCode: string;
  entitlementFeatureCode: string;
  modelId: string;
  tokenizerId: string;
  reservationCredits: string;
  maxInputTokens: string;
  maxOutputTokens: string;
  inputTokensPerCredit: string;
  outputTokensPerCredit: string;
};

const kinds: { key: ProductKind; label: string }[] = [
  { key: "trial-rules", label: "试用规则" },
  { key: "plans", label: "套餐" },
  { key: "credit-packs", label: "积分包" },
  { key: "ai-feature-rules", label: "AI功能规则" },
];

const initialForm: ProductForm = {
  version: "1", durationDays: "7", seatCapacity: "", creditAmount: "",
  audience: "ENTERPRISE", code: "", displayName: "", billingPeriod: "YEAR", durationMonths: "12",
  perSeatCreditAmount: "", priceMicroYuan: "", validUnit: "MONTH", validCount: "",
  capabilityCode: "", permissionCode: "", entitlementFeatureCode: "", modelId: "", tokenizerId: "", reservationCredits: "",
  maxInputTokens: "", maxOutputTokens: "", inputTokensPerCredit: "", outputTokensPerCredit: "",
};

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.body.code}: ${error.body.message}`;
  if (error instanceof Error) return error.message;
  return "操作失败，请稍后重试";
}

function numberValue(value: unknown): string {
  return typeof value === "number" ? value.toLocaleString("zh-CN") : value == null ? "-" : String(value);
}

function moneyValue(value: unknown): string {
  if (typeof value !== "number" && typeof value !== "string") return "-";
  const amount = Number(value);
  return Number.isFinite(amount) ? `¥${(amount / 1_000_000).toFixed(2)}` : "-";
}

function featureSnapshotValue(value: unknown): string {
  if (Array.isArray(value)) return value.map(String).join("、") || "无权限码";
  if (typeof value !== "string") return value == null ? "-" : String(value);
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.map(String).join("、") || "无权限码" : value;
  } catch {
    return value;
  }
}

function statusLabel(status: unknown): { label: string; variant: "success" | "neutral" } {
  return status === "PUBLISHED" ? { label: "已发布", variant: "success" } : { label: "已下线", variant: "neutral" };
}

function numericFormValue(value: string, label: string, minimum: number): number | null {
  if (!value.trim()) return null;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < minimum) throw new Error(`${label}必须是大于等于 ${minimum} 的整数`);
  return parsed;
}

export default function ProductsPage() {
  const [activeKind, setActiveKind] = useState<ProductKind>("plans");
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<ProductForm>(initialForm);
  const [selectedFeatureCodes, setSelectedFeatureCodes] = useState<string[]>([]);
  const [formError, setFormError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_PRODUCT_EDIT");

  const { data: products, isLoading, isError, error: productsError } = useQuery({
    queryKey: ["products", activeKind],
    queryFn: () => adminApiFetch<ProductRow[]>(`/platform/recruitment/${activeKind}`),
  });

  const { data: permissionCatalog, isLoading: permissionLoading, isError: permissionErrorState, error: permissionError } = useQuery({
    queryKey: ["platform-permission-catalog"],
    queryFn: () => adminApiFetch<PermissionCatalogItem[]>("/platform/recruitment/permission-catalog"),
    enabled: showForm && canEdit,
  });
  const activePermissions = useMemo(
    () => (permissionCatalog ?? []).filter((item) => item.frontend === "USER" && item.status === "ACTIVE" && item.product_domain === "RECRUITMENT"),
    [permissionCatalog],
  );

  const publishMutation = useMutation({
    mutationFn: async () => {
      const featureSnapshot = JSON.stringify(selectedFeatureCodes);
      if (activeKind === "trial-rules") {
        const version = numericFormValue(form.version, "版本", 1);
        const durationDays = numericFormValue(form.durationDays, "Trial 有效期", 1);
        const seatCapacity = numericFormValue(form.seatCapacity, "席位数", 0);
        const creditAmount = numericFormValue(form.creditAmount, "积分数", 0);
        if (version == null || durationDays == null || seatCapacity == null || creditAmount == null) throw new Error("请完整填写试用规则");
        if (![3, 7, 10].includes(durationDays)) throw new Error("Trial 有效期只能配置为 3 天、7 天或 10 天");
        return adminApiFetch("/platform/recruitment/trial-rules", { method: "POST", body: JSON.stringify({ version, durationDays, seatCapacity, creditAmount, featureSnapshot }) });
      }

      if (activeKind === "plans") {
        const version = numericFormValue(form.version, "版本", 1);
        const durationMonths = numericFormValue(form.durationMonths, "有效期月份", 1);
        const perSeatCreditAmount = numericFormValue(form.perSeatCreditAmount, "席位积分", 0);
        const priceMicroYuan = numericFormValue(form.priceMicroYuan, "价格（微元）", 0);
        if (version == null || durationMonths == null || perSeatCreditAmount == null || priceMicroYuan == null) throw new Error("请完整填写套餐配置");
        if (!form.code.trim() || !form.displayName.trim()) throw new Error("请填写套餐编码和名称");
        const expectedMonths = { MONTH: 1, QUARTER: 3, YEAR: 12 }[form.billingPeriod];
        if (durationMonths !== expectedMonths) throw new Error("计费周期与有效期月份不一致");
        return adminApiFetch("/platform/recruitment/plans", { method: "POST", body: JSON.stringify({ audience: form.audience, code: form.code.trim(), version, displayName: form.displayName.trim(), billingPeriod: form.billingPeriod, durationMonths, perSeatCreditAmount, priceMicroYuan, featureSnapshot }) });
      }

      if (activeKind === "credit-packs") {
        const version = numericFormValue(form.version, "版本", 1);
        const creditAmount = numericFormValue(form.creditAmount, "积分数", 1);
        const validCount = numericFormValue(form.validCount, "有效期数量", 1);
        const priceMicroYuan = numericFormValue(form.priceMicroYuan, "价格（微元）", 0);
        if (version == null || creditAmount == null || validCount == null || priceMicroYuan == null) throw new Error("请完整填写积分包配置");
        if (!form.code.trim() || !form.displayName.trim()) throw new Error("请填写积分包编码和名称");
        return adminApiFetch("/platform/recruitment/credit-packs", { method: "POST", body: JSON.stringify({ audience: form.audience, code: form.code.trim(), version, displayName: form.displayName.trim(), creditAmount, validUnit: form.validUnit, validCount, priceMicroYuan }) });
      }

      const reservationCredits = numericFormValue(form.reservationCredits, "预占积分", 1);
      const maxInputTokens = numericFormValue(form.maxInputTokens, "最大输入 Token", 1);
      const maxOutputTokens = numericFormValue(form.maxOutputTokens, "最大输出 Token", 1);
      const inputTokensPerCredit = numericFormValue(form.inputTokensPerCredit, "输入 Token 兑换比", 1);
      const outputTokensPerCredit = numericFormValue(form.outputTokensPerCredit, "输出 Token 兑换比", 1);
      if ([reservationCredits, maxInputTokens, maxOutputTokens, inputTokensPerCredit, outputTokensPerCredit].some((value) => value == null)) throw new Error("请完整填写 AI 功能规则");
      if (!form.capabilityCode.trim() || !form.permissionCode.trim() || !form.entitlementFeatureCode.trim() || !form.modelId.trim() || !form.tokenizerId.trim()) throw new Error("请填写能力码、权限码、权益码、模型和 Tokenizer");
      const minimumReservation = Math.ceil(maxInputTokens! / inputTokensPerCredit!) + Math.ceil(maxOutputTokens! / outputTokensPerCredit!);
      if (reservationCredits! < minimumReservation) throw new Error(`预占积分不能低于 ${minimumReservation}`);
      return adminApiFetch("/platform/recruitment/ai-feature-rules", { method: "POST", body: JSON.stringify({ capabilityCode: form.capabilityCode.trim(), permissionCode: form.permissionCode.trim(), entitlementFeatureCode: form.entitlementFeatureCode.trim(), modelId: form.modelId.trim(), tokenizerId: form.tokenizerId.trim(), reservationCredits, maxInputTokens, maxOutputTokens, inputTokensPerCredit, outputTokensPerCredit }) });
    },
    onSuccess: () => {
      setMessage("配置已发布"); setFormError(null); setShowForm(false); setForm(initialForm); setSelectedFeatureCodes([]);
      queryClient.invalidateQueries({ queryKey: ["products", activeKind] });
    },
    onError: (error) => setFormError(errorMessage(error)),
  });

  const retireMutation = useMutation({
    mutationFn: ({ kind, id }: { kind: ProductKind; id: string }) => adminApiFetch(`/platform/recruitment/${kind}/${id}`, { method: "DELETE" }),
    onSuccess: (_, variables) => { setMessage("配置已下线"); queryClient.invalidateQueries({ queryKey: ["products", variables.kind] }); },
    onError: (error) => setMessage(errorMessage(error)),
  });

  function resetForm() { setShowForm(false); setForm(initialForm); setSelectedFeatureCodes([]); setFormError(null); }
  function selectKind(kind: ProductKind) { setActiveKind(kind); resetForm(); setMessage(null); }
  function openForm() { setMessage(null); setFormError(null); setShowForm(true); }

  function renderFeatureSelector() {
    return <div>
      <label className="mb-1 block text-sm font-medium text-slate-700">功能权限码</label>
      <div className="rounded-lg border border-slate-300 p-3">
        {permissionLoading ? <p className="text-sm text-slate-400">加载权限目录…</p> : permissionErrorState ? <p className="text-sm text-red-600">权限目录加载失败：{errorMessage(permissionError)}</p> : activePermissions.length === 0 ? <p className="text-sm text-amber-600">暂无可用权限码，请先在“权益与权限”中发布权限目录。</p> : <div className="grid max-h-44 grid-cols-1 gap-2 overflow-y-auto md:grid-cols-2">{activePermissions.map((permission) => <label key={permission.code} className="flex items-start gap-2 text-sm text-slate-700"><input type="checkbox" checked={selectedFeatureCodes.includes(permission.code)} onChange={(event) => setSelectedFeatureCodes((current) => event.target.checked ? [...current, permission.code] : current.filter((code) => code !== permission.code))} /><span><span className="font-medium">{permission.code}</span>{permission.description && <span className="ml-1 text-xs text-slate-400">{permission.description}</span>}</span></label>)}</div>}
      </div>
      <p className="mt-1 text-xs text-slate-400">将以权限码数组快照保存到套餐或 Trial 配置中。</p>
    </div>;
  }

  function renderForm() {
    const inputClass = "w-full rounded-lg border border-slate-300 px-3 py-2 text-sm";
    const labelClass = "mb-1 block text-sm font-medium text-slate-700";
    const field = (label: string, key: keyof ProductForm, type: "text" | "number" = "text", placeholder?: string) => <div><label className={labelClass}>{label}</label><input type={type} value={form[key] as string} onChange={(event) => setForm((current) => ({ ...current, [key]: event.target.value }))} placeholder={placeholder} className={inputClass} /></div>;
    return <div className="mb-6 rounded-xl border border-blue-100 bg-blue-50/40 p-6 shadow-sm">
      <div className="mb-4 flex items-center justify-between"><h2 className="text-base font-semibold text-slate-700">发布{kinds.find((item) => item.key === activeKind)?.label}</h2><button onClick={resetForm} className="text-slate-400 hover:text-slate-600" aria-label="关闭配置表单"><X className="h-5 w-5" /></button></div>
      <div className="space-y-4">
        {activeKind === "trial-rules" && <><div className="grid grid-cols-1 gap-4 md:grid-cols-4">{field("版本", "version", "number")}<div><label className={labelClass}>Trial 有效期</label><select value={form.durationDays} onChange={(e) => setForm({ ...form, durationDays: e.target.value })} className={inputClass}><option value="3">3 天</option><option value="7">7 天</option><option value="10">10 天</option></select></div>{field("席位数", "seatCapacity", "number")}{field("积分数", "creditAmount", "number")}</div>{renderFeatureSelector()}</>}
        {activeKind === "plans" && <><div className="grid grid-cols-1 gap-4 md:grid-cols-2"><div><label className={labelClass}>适用对象</label><select value={form.audience} onChange={(e) => setForm({ ...form, audience: e.target.value as ProductForm["audience"] })} className={inputClass}><option value="ENTERPRISE">企业租户</option><option value="PERSONAL">个人租户</option></select></div>{field("套餐编码", "code", "text", "例如：RECRUITMENT_YEARLY")}{field("套餐名称", "displayName", "text", "面向运营人员展示")}{field("版本", "version", "number")}<div><label className={labelClass}>计费周期</label><select value={form.billingPeriod} onChange={(e) => setForm({ ...form, billingPeriod: e.target.value as ProductForm["billingPeriod"] })} className={inputClass}><option value="MONTH">月</option><option value="QUARTER">季</option><option value="YEAR">年</option></select></div>{field("有效期月份", "durationMonths", "number")}{field("每席套餐积分", "perSeatCreditAmount", "number")}{field("价格（微元）", "priceMicroYuan", "number", "1 元 = 1000000 微元")}</div>{renderFeatureSelector()}</>}
        {activeKind === "credit-packs" && <div className="grid grid-cols-1 gap-4 md:grid-cols-2"><div><label className={labelClass}>适用对象</label><select value={form.audience} onChange={(e) => setForm({ ...form, audience: e.target.value as ProductForm["audience"] })} className={inputClass}><option value="ENTERPRISE">企业租户</option><option value="PERSONAL">个人租户</option></select></div>{field("积分包编码", "code", "text", "例如：CREDIT_PACK_1000")}{field("积分包名称", "displayName", "text")}{field("版本", "version", "number")}{field("积分数量", "creditAmount", "number")}<div><label className={labelClass}>有效期单位</label><select value={form.validUnit} onChange={(e) => setForm({ ...form, validUnit: e.target.value as ProductForm["validUnit"] })} className={inputClass}><option value="MONTH">月</option><option value="YEAR">年</option></select></div>{field("有效期数量", "validCount", "number")}{field("价格（微元）", "priceMicroYuan", "number", "1 元 = 1000000 微元")}</div>}
        {activeKind === "ai-feature-rules" && <><div className="grid grid-cols-1 gap-4 md:grid-cols-3">{field("能力码", "capabilityCode", "text", "请输入已定义的能力码")}<div><label className={labelClass}>权限码</label><select value={form.permissionCode} onChange={(e) => setForm({ ...form, permissionCode: e.target.value })} className={inputClass}><option value="">请选择已启用权限码</option>{activePermissions.map((permission) => <option key={permission.code} value={permission.code}>{permission.code}</option>)}</select></div>{field("权益码", "entitlementFeatureCode", "text", "请输入已定义的权益码")}{field("模型 ID", "modelId", "text", "由运营配置的实际模型标识")}{field("Tokenizer ID", "tokenizerId", "text", "由运营配置的 Tokenizer 标识")}</div><div className="grid grid-cols-1 gap-4 md:grid-cols-3">{field("预占积分", "reservationCredits", "number")}{field("最大输入 Token", "maxInputTokens", "number")}{field("最大输出 Token", "maxOutputTokens", "number")}{field("输入 Token/积分", "inputTokensPerCredit", "number")}{field("输出 Token/积分", "outputTokensPerCredit", "number")}</div><p className="text-xs text-slate-500">预占积分必须覆盖最大输入和最大输出 Token 按兑换比计算的总消耗；模型和 Tokenizer 由运营配置，不在代码中写死。</p></>}
        {formError && <div className="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{formError}</div>}
        <div className="flex gap-2"><button onClick={() => publishMutation.mutate()} disabled={publishMutation.isPending} className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50">{publishMutation.isPending ? "发布中…" : "发布配置"}</button><button onClick={resetForm} className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 hover:bg-white">取消</button></div>
      </div>
    </div>;
  }

  function retire(row: ProductRow) {
    if (!row.id || row.status !== "PUBLISHED") return;
    if (typeof window !== "undefined" && !window.confirm("确认下线此配置吗？下线后不会影响已生效的历史权益快照。")) return;
    retireMutation.mutate({ kind: activeKind, id: row.id });
  }

  function renderAction(row: ProductRow) {
    const status = statusLabel(row.status);
    return <><Badge variant={status.variant}>{status.label}</Badge>{canEdit && row.status === "PUBLISHED" && row.id && <button onClick={() => retire(row)} disabled={retireMutation.isPending} className="ml-3 inline-flex items-center gap-1 text-xs text-red-600 hover:text-red-700 disabled:opacity-50"><Trash2 className="h-3 w-3" /> 下线</button>}</>;
  }

  function renderTable() {
    const rows = products ?? [];
    const tableHead = "border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold text-slate-500 [&>th]:px-4 [&>th]:py-3";
    const tableRow = "border-b border-slate-100 text-sm text-slate-600 hover:bg-slate-50 [&>td]:px-4 [&>td]:py-3";
    if (activeKind === "trial-rules") return <table className="w-full"><thead><tr className={tableHead}><th>版本</th><th>有效期</th><th>席位数</th><th>积分</th><th>功能权限</th><th>状态/操作</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)} className={tableRow}><td>v{numberValue(row.version)}</td><td>{numberValue(row.duration_days)} 天</td><td>{numberValue(row.seat_capacity)}</td><td>{numberValue(row.credit_amount)}</td><td className="max-w-xs truncate">{featureSnapshotValue(row.feature_snapshot)}</td><td>{renderAction(row)}</td></tr>)}</tbody></table>;
    if (activeKind === "plans") return <table className="w-full"><thead><tr className={tableHead}><th>名称/编码</th><th>适用对象</th><th>周期</th><th>每席积分</th><th>价格</th><th>状态/操作</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)} className={tableRow}><td><div className="flex items-center gap-2"><Package className="h-4 w-4 text-slate-400" /><div><div className="font-medium text-slate-800">{String(row.display_name ?? row.code ?? "未命名")}</div><div className="text-xs text-slate-400">{String(row.code ?? "-")} · v{numberValue(row.version)}</div></div></div></td><td>{row.audience === "PERSONAL" ? "个人" : "企业"}</td><td>{String(row.billing_period ?? "-")} / {numberValue(row.duration_months)} 个月</td><td>{numberValue(row.per_seat_credit_amount)}</td><td>{moneyValue(row.price_micro_yuan)}</td><td>{renderAction(row)}</td></tr>)}</tbody></table>;
    if (activeKind === "credit-packs") return <table className="w-full"><thead><tr className={tableHead}><th>名称/编码</th><th>适用对象</th><th>积分</th><th>有效期</th><th>价格</th><th>状态/操作</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)} className={tableRow}><td><div className="font-medium text-slate-800">{String(row.display_name ?? row.code ?? "未命名")}</div><div className="text-xs text-slate-400">{String(row.code ?? "-")} · v{numberValue(row.version)}</div></td><td>{row.audience === "PERSONAL" ? "个人" : "企业"}</td><td>{numberValue(row.credit_amount)}</td><td>{numberValue(row.valid_count)} {row.valid_unit === "YEAR" ? "年" : "月"}</td><td>{moneyValue(row.price_micro_yuan)}</td><td>{renderAction(row)}</td></tr>)}</tbody></table>;
    return <table className="w-full"><thead><tr className={tableHead}><th>能力码</th><th>权限/权益码</th><th>模型/Tokenizer</th><th>预占积分</th><th>Token 上限</th><th>兑换比</th><th>状态/操作</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)} className={tableRow}><td className="font-medium text-slate-800">{String(row.capability_code ?? "-")}</td><td><div className="text-xs text-slate-600">权限：{String(row.permission_code ?? "-")}</div><div className="text-xs text-slate-400">权益：{String(row.entitlement_feature_code ?? "-")}</div></td><td><div className="text-xs text-slate-600">模型：{String(row.model_id ?? "-")}</div><div className="text-xs text-slate-400">Tokenizer：{String(row.tokenizer_id ?? "-")}</div></td><td>{numberValue(row.reservation_credits)}</td><td>输入 {numberValue(row.max_input_tokens)} / 输出 {numberValue(row.max_output_tokens)}</td><td>输入 {numberValue(row.input_tokens_per_credit)} / 输出 {numberValue(row.output_tokens_per_credit)}</td><td>{renderAction(row)}</td></tr>)}</tbody></table>;
  }

  return <div>
    <div className="mb-6 flex items-start justify-between"><div><h1 className="text-2xl font-bold text-slate-800">产品运营</h1><p className="mt-1 text-sm text-slate-500">管理平台商业化产品配置</p></div>{canEdit && <button onClick={openForm} className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700"><Plus className="h-4 w-4" /> 发布配置</button>}</div>
    {message && <div className={`mb-4 rounded-lg px-4 py-2 text-sm ${message.includes("失败") || message.includes(":") ? "bg-red-50 text-red-700" : "bg-green-50 text-green-700"}`}>{message}</div>}
    <div className="mb-4 flex flex-wrap gap-2">{kinds.map((kind) => <button key={kind.key} onClick={() => selectKind(kind.key)} className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${activeKind === kind.key ? "bg-blue-600 text-white" : "bg-slate-100 text-slate-600 hover:bg-slate-200"}`}>{kind.label}</button>)}</div>
    {showForm && renderForm()}
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">{isLoading ? <div className="p-8 text-center text-sm text-slate-400">加载中…</div> : isError ? <div className="p-8 text-center text-sm text-red-600">加载失败：{errorMessage(productsError)}</div> : !products || products.length === 0 ? <div className="p-8 text-center text-sm text-slate-400">暂无产品配置</div> : renderTable()}</div>
  </div>;
}
