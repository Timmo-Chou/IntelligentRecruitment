"use client";

// 产品运营页面：管理试用规则、套餐、积分包、智能功能规则。
// 所有配置均通过 BOSS 商业化接口提交，不在前端维护模拟商品或权限数据。
import { useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Package, Plus, Trash2, X } from "lucide-react";
import { getDisplayLabel, resolveCatalogLabel, resolveCodeLabel } from "@/lib/display-label";
import { AdminErrorNotice } from "@/components/admin-error-notice";
import { businessEntitlementCodes, readProductEntitlements } from "@/lib/product-entitlements";

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
  agentId: "simple_recruitment_agent" | "complex_recruitment_agent";
  operation: string;
  billingMethod: "TOKEN" | "FIXED_PER_EXECUTION" | "FIXED_PER_CANDIDATE";
  fixedCreditsPerUnit: string;
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

class ProductValidationError extends Error {
  constructor(message: string) { super(message); this.name = "ProductValidationError"; }
}

const kinds: { key: ProductKind; label: string }[] = [
  { key: "trial-rules", label: "试用规则" },
  { key: "plans", label: "套餐" },
  { key: "credit-packs", label: "积分包" },
  { key: "ai-feature-rules", label: "智能功能规则" },
];

const initialForm: ProductForm = {
  version: "1", durationDays: "7", seatCapacity: "", creditAmount: "",
  audience: "ENTERPRISE", code: "", displayName: "", billingPeriod: "YEAR", durationMonths: "12",
  perSeatCreditAmount: "", priceMicroYuan: "", validUnit: "MONTH", validCount: "",
  capabilityCode: "", agentId: "simple_recruitment_agent", operation: "", billingMethod: "TOKEN", fixedCreditsPerUnit: "",
  permissionCode: "", entitlementFeatureCode: "", modelId: "", tokenizerId: "", reservationCredits: "",
  maxInputTokens: "", maxOutputTokens: "", inputTokensPerCredit: "", outputTokensPerCredit: "",
};

function numberValue(value: unknown): string {
  return typeof value === "number" ? value.toLocaleString("zh-CN") : value == null ? "-" : String(value);
}

function moneyValue(value: unknown): string {
  if (typeof value !== "number" && typeof value !== "string") return "-";
  const amount = Number(value);
  return Number.isFinite(amount) ? `¥${(amount / 1_000_000).toFixed(2)}` : "-";
}

function featureSnapshotValue(value: unknown): ReactNode {
  if (value == null) return <span>未配置</span>;
  let pairs: Array<[string, boolean]>;
  try { pairs = Object.entries(readProductEntitlements(value)); }
  catch { return <span>权益配置格式待核对</span>; }
  if (!pairs.length) return <span>未配置</span>;
  return <div className="space-y-1">{pairs.map(([code, enabled]) => {
    const permission = resolveCodeLabel("permission.user", code);
    const entitlement = resolveCodeLabel("entitlement.featureCode", code);
    const label = permission.matched ? permission.label : entitlement.matched ? entitlement.label : "名称待补充";
    return <div key={code} className="flex items-center gap-1"><span>{label}：{enabled === true ? "已包含" : enabled === false ? "未包含" : "状态待核对"}</span><details className="text-xs text-slate-400"><summary className="cursor-pointer">查看编码</summary><code>{code}</code><button type="button" className="ml-2 text-blue-600" onClick={() => void navigator.clipboard.writeText(code)}>复制</button></details></div>;
  })}</div>;
}

function statusLabel(status: unknown): { label: string; variant: "success" | "neutral" | "warning" } {
  const value = typeof status === "string" ? status : null;
  const label = getDisplayLabel("product.status", value, "产品状态暂未识别");
  return { label, variant: value === "PUBLISHED" ? "success" : value === "RETIRED" ? "neutral" : "warning" };
}

function CodeName({ domain, value, fallback }: { domain: string; value: unknown; fallback: string }) {
  const code = value == null ? "" : String(value);
  if (!code) return <span>{fallback}</span>;
  return <span>{getDisplayLabel(domain, code, fallback)}<details className="mt-1 text-xs font-normal text-slate-400"><summary className="cursor-pointer">查看编码</summary><code>{code}</code><button type="button" className="ml-2 text-blue-600" onClick={() => void navigator.clipboard.writeText(code)}>复制</button></details></span>;
}

function RuleModelDetails({ row }: { row: ProductRow }) {
  if (row.agent_id === "complex_recruitment_agent") {
    return <div className="text-xs text-slate-600">深度</div>;
  }
  const model = typeof row.model_id === "string" && row.model_id.trim() ? row.model_id : "未配置";
  const tokenizer = typeof row.tokenizer_id === "string" && row.tokenizer_id.trim() ? row.tokenizer_id : "未配置";
  return <>
    <div className="text-xs text-slate-600">{model}</div>
    <div className="text-xs text-slate-400">计量器：{tokenizer}</div>
    <div className="text-xs text-slate-400">输入 {numberValue(row.max_input_tokens)} / 输出 {numberValue(row.max_output_tokens)}</div>
  </>;
}

function ProductCode({ label, value }: { label: string; value: unknown }) {
  const code = value == null ? "" : String(value).trim();
  if (!code) return <span>{label}暂未获取</span>;
  return <details className="mt-1 text-xs font-normal text-slate-400"><summary className="cursor-pointer">查看{label}</summary><code>{code}</code><button type="button" className="ml-2 text-blue-600" onClick={() => void navigator.clipboard.writeText(code)}>复制</button></details>;
}

function numericFormValue(value: string, label: string, minimum: number): number | null {
  if (!value.trim()) return null;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < minimum) throw new ProductValidationError(`${label}必须是大于等于 ${minimum} 的整数`);
  return parsed;
}

export default function ProductsPage() {
  const [activeKind, setActiveKind] = useState<ProductKind>("plans");
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<ProductForm>(initialForm);
  const [featureSnapshot, setFeatureSnapshot] = useState<Record<string, boolean>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [formErrorCause, setFormErrorCause] = useState<unknown>(null);
  const [retireError, setRetireError] = useState<unknown>(null);
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

  const configuringEntitlements = showForm && canEdit && (activeKind === "plans" || activeKind === "trial-rules");
  const { data: entitlementRules, isLoading: entitlementLoading, isError: entitlementErrorState, error: entitlementError } = useQuery({
    queryKey: ["product-entitlement-rules"],
    queryFn: () => adminApiFetch<ProductRow[]>("/platform/recruitment/ai-feature-rules"),
    enabled: configuringEntitlements,
  });
  const entitlementCodes = useMemo(() => businessEntitlementCodes(entitlementRules ?? []), [entitlementRules]);

  const publishMutation = useMutation({
    mutationFn: async () => {
      const serializedFeatures = JSON.stringify(featureSnapshot);
      if ((activeKind === "plans" || activeKind === "trial-rules") && (permissionLoading || entitlementLoading || permissionErrorState || entitlementErrorState)) {
        throw new ProductValidationError("请等待功能权限和业务权益配置加载成功后再发布");
      }
      if (activeKind === "trial-rules") {
        const version = numericFormValue(form.version, "版本", 1);
        const durationDays = numericFormValue(form.durationDays, "试用有效期", 1);
        const seatCapacity = numericFormValue(form.seatCapacity, "席位数", 0);
        const creditAmount = numericFormValue(form.creditAmount, "积分数", 0);
        if (version == null || durationDays == null || seatCapacity == null || creditAmount == null) throw new ProductValidationError("请完整填写试用规则");
        if (![3, 7, 10].includes(durationDays)) throw new ProductValidationError("试用有效期只能配置为 3 天、7 天或 10 天");
        return adminApiFetch("/platform/recruitment/trial-rules", { method: "POST", body: JSON.stringify({ version, durationDays, seatCapacity, creditAmount, featureSnapshot: serializedFeatures }) });
      }

      if (activeKind === "plans") {
        const version = numericFormValue(form.version, "版本", 1);
        const durationMonths = numericFormValue(form.durationMonths, "有效期月份", 1);
        const perSeatCreditAmount = numericFormValue(form.perSeatCreditAmount, "席位积分", 0);
        const priceMicroYuan = numericFormValue(form.priceMicroYuan, "价格（微元）", 0);
        if (version == null || durationMonths == null || perSeatCreditAmount == null || priceMicroYuan == null) throw new ProductValidationError("请完整填写套餐配置");
        if (!form.code.trim() || !form.displayName.trim()) throw new ProductValidationError("请填写套餐编码和名称");
        const expectedMonths = { MONTH: 1, QUARTER: 3, YEAR: 12 }[form.billingPeriod];
        if (durationMonths !== expectedMonths) throw new ProductValidationError("计费周期与有效期月份不一致");
        return adminApiFetch("/platform/recruitment/plans", { method: "POST", body: JSON.stringify({ audience: form.audience, code: form.code.trim(), version, displayName: form.displayName.trim(), billingPeriod: form.billingPeriod, durationMonths, perSeatCreditAmount, priceMicroYuan, featureSnapshot: serializedFeatures }) });
      }

      if (activeKind === "credit-packs") {
        const version = numericFormValue(form.version, "版本", 1);
        const creditAmount = numericFormValue(form.creditAmount, "积分数", 1);
        const validCount = numericFormValue(form.validCount, "有效期数量", 1);
        const priceMicroYuan = numericFormValue(form.priceMicroYuan, "价格（微元）", 0);
        if (version == null || creditAmount == null || validCount == null || priceMicroYuan == null) throw new ProductValidationError("请完整填写积分包配置");
        if (!form.code.trim() || !form.displayName.trim()) throw new ProductValidationError("请填写积分包编码和名称");
        return adminApiFetch("/platform/recruitment/credit-packs", { method: "POST", body: JSON.stringify({ audience: form.audience, code: form.code.trim(), version, displayName: form.displayName.trim(), creditAmount, validUnit: form.validUnit, validCount, priceMicroYuan }) });
      }

      const fixed = form.billingMethod !== "TOKEN";
      const complex = form.agentId === "complex_recruitment_agent";
      if (!form.capabilityCode.trim() || !form.permissionCode.trim() || !form.entitlementFeatureCode.trim()) throw new ProductValidationError("请填写能力码、权限码和权益码");
      if (complex && !fixed) throw new ProductValidationError("高级招聘执行服务仅支持固定积分计费");
      const operation = form.operation.trim() || ({ JD_GENERATION: "create", RESUME_PARSING: "analyze", CANDIDATE_SCREENING: "match", JD_IN_PLACE_REVISION: "revise", INTERVIEW_KIT_GENERATION: "generate" } as Record<string, string>)[form.capabilityCode.trim()] || "execute";
      const fixedCreditsPerUnit = fixed ? numericFormValue(form.fixedCreditsPerUnit, "每计费单元积分", 1) : null;
      if (fixed && fixedCreditsPerUnit == null) throw new ProductValidationError("请配置固定计费积分");
      if (form.billingMethod === "FIXED_PER_CANDIDATE" && form.capabilityCode !== "CANDIDATE_SCREENING") throw new ProductValidationError("按候选人计费只适用于候选人匹配");
      const maxInputTokens = complex ? null : numericFormValue(form.maxInputTokens, "最大输入词元数", 1);
      const maxOutputTokens = complex ? null : numericFormValue(form.maxOutputTokens, "最大输出词元数", 1);
      const inputTokensPerCredit = complex ? null : numericFormValue(form.inputTokensPerCredit, "输入词元兑换比", 1);
      const outputTokensPerCredit = complex ? null : numericFormValue(form.outputTokensPerCredit, "输出词元兑换比", 1);
      if (!complex && (!form.modelId.trim() || !form.tokenizerId.trim() || [maxInputTokens,maxOutputTokens,inputTokensPerCredit,outputTokensPerCredit].some((value) => value == null))) throw new ProductValidationError("标准招聘执行服务必须配置模型、计量器和词元限制");
      const tokenReserve = !complex && form.billingMethod === "TOKEN" ? Math.ceil(maxInputTokens! / inputTokensPerCredit!) + Math.ceil(maxOutputTokens! / outputTokensPerCredit!) : 1;
      const reservationCredits = Math.max(tokenReserve, numericFormValue(form.reservationCredits, "预占积分", 1) ?? 1, fixedCreditsPerUnit ?? 1);
      if (form.billingMethod === "TOKEN" && reservationCredits < tokenReserve) throw new ProductValidationError(`预占积分不能低于 ${tokenReserve}`);
      return adminApiFetch("/platform/recruitment/ai-feature-rules", { method: "POST", body: JSON.stringify({ capabilityCode: form.capabilityCode.trim(), agentId: form.agentId, operation, billingMethod: form.billingMethod, fixedCreditsPerUnit, permissionCode: form.permissionCode.trim(), entitlementFeatureCode: form.entitlementFeatureCode.trim(), modelId: complex ? null : form.modelId.trim(), tokenizerId: complex ? null : form.tokenizerId.trim(), reservationCredits, maxInputTokens, maxOutputTokens, inputTokensPerCredit, outputTokensPerCredit }) });
    },
    onSuccess: () => {
      setMessage("配置已发布"); setFormError(null); setFormErrorCause(null); setShowForm(false); setForm(initialForm); setFeatureSnapshot({});
      queryClient.invalidateQueries({ queryKey: ["products", activeKind] });
    },
    onError: (error) => {
      if (error instanceof ProductValidationError) {
        setFormError(error.message);
        setFormErrorCause(null);
      } else {
        setFormError(null);
        setFormErrorCause(error);
      }
    },
  });

  const retireMutation = useMutation({
    mutationFn: ({ kind, id }: { kind: ProductKind; id: string }) => adminApiFetch(`/platform/recruitment/${kind}/${id}`, { method: "DELETE" }),
    onSuccess: (_, variables) => { setMessage("配置已下线"); setRetireError(null); queryClient.invalidateQueries({ queryKey: ["products", variables.kind] }); },
    onError: (error) => { setMessage(null); setRetireError(error); },
  });

  function resetForm() { setShowForm(false); setForm(initialForm); setFeatureSnapshot({}); setFormError(null); setFormErrorCause(null); }
  function selectKind(kind: ProductKind) { setActiveKind(kind); resetForm(); setMessage(null); }
  function openForm() { setMessage(null); setFormError(null); setFormErrorCause(null); setRetireError(null); setShowForm(true); }

  function copyPlan(row: ProductRow) {
    try {
      const copiedFeatures = readProductEntitlements(row.feature_snapshot);
      const versions = (products ?? []).filter(item => item.code === row.code && item.audience === row.audience).map(item => Number(item.version) || 0);
      setForm({ ...initialForm, audience: row.audience as ProductForm["audience"], code: String(row.code ?? ""), displayName: String(row.display_name ?? ""),
        version: String(Math.max(Number(row.version) || 0, ...versions) + 1), billingPeriod: row.billing_period as ProductForm["billingPeriod"],
        durationMonths: String(row.duration_months ?? ""), perSeatCreditAmount: String(row.per_seat_credit_amount ?? ""), priceMicroYuan: String(row.price_micro_yuan ?? "") });
      setFeatureSnapshot(copiedFeatures); setFormError(null); setFormErrorCause(null); setMessage(null); setShowForm(true);
    } catch {
      setMessage("复制失败：原套餐权益配置无法解析，请核对后再创建新版本。");
    }
  }

  function renderFeatureSelector() {
    return <div>
      <label className="mb-1 block text-sm font-medium text-slate-700">功能权限码</label>
      <div className="rounded-lg border border-slate-300 p-3">
        {permissionLoading ? <p className="text-sm text-slate-400">加载权限目录…</p> : permissionErrorState ? <AdminErrorNotice error={permissionError} fallback="权限目录加载暂未成功，请稍后重试。" className="text-sm text-red-600" /> : activePermissions.length === 0 ? <p className="text-sm text-amber-600">暂无可用功能权限，请先核对服务端权限目录。</p> : <div className="grid max-h-44 grid-cols-1 gap-2 overflow-y-auto md:grid-cols-2">{activePermissions.map((permission) => <label key={permission.code} className="flex items-start gap-2 text-sm text-slate-700"><input type="checkbox" checked={featureSnapshot[permission.code] === true} onChange={(event) => setFeatureSnapshot((current) => ({ ...current, [permission.code]: event.target.checked }))} /><span><span className="font-medium">{getDisplayLabel("permission.user", permission.code, "权限名称待配置")}</span><details className="text-xs text-slate-400"><summary className="cursor-pointer">查看权限代码</summary><code>{permission.code}</code><button type="button" className="ml-2 text-blue-600" onClick={() => void navigator.clipboard.writeText(permission.code)}>复制</button></details>{permission.description && <span className="ml-1 text-xs text-slate-400">{permission.description}</span>}</span></label>)}</div>}
      </div>
      <p className="mt-1 text-xs text-slate-400">功能权限决定成员可使用哪些操作，还需同时满足角色、席位和企业状态要求。</p>
      <fieldset className="mt-4 rounded-lg border border-slate-300 p-3">
        <legend className="px-1 text-sm font-medium text-slate-700">业务权益</legend>
        {entitlementLoading ? <p className="text-sm text-slate-400">加载业务权益…</p> : entitlementErrorState ? <AdminErrorNotice error={entitlementError} fallback="业务权益加载暂未成功，请稍后重试。" /> : <div className="grid grid-cols-1 gap-2 md:grid-cols-2">{entitlementCodes.map(code => <label key={code} className="flex items-start gap-2 text-sm text-slate-700"><input type="checkbox" checked={featureSnapshot[code] === true} onChange={event => setFeatureSnapshot(current => ({ ...current, [code]: event.target.checked }))} /><span>{getDisplayLabel("entitlement.featureCode", code, code)}<details className="text-xs text-slate-400"><summary>查看权益代码</summary><code>{code}</code></details></span></label>)}</div>}
        {!entitlementLoading && !entitlementErrorState && !entitlementCodes.length && <p className="text-sm text-amber-600">暂无已发布智能功能规则，请先发布相应规则。</p>}
        <p className="mt-2 text-xs text-slate-500">选项来自已发布智能功能规则。使用简历解析时，需同时包含“简历解析”业务权益和“使用智能招聘工具”功能权限。</p>
      </fieldset>
      {Object.keys(featureSnapshot).filter(code => !activePermissions.some(item => item.code === code) && !entitlementCodes.includes(code)).length > 0 && <div className="mt-3 rounded-lg border border-slate-200 p-3 text-sm"><p className="mb-2 font-medium">其他已配置权益（保留原值）</p>{Object.entries(featureSnapshot).filter(([code]) => !activePermissions.some(item => item.code === code) && !entitlementCodes.includes(code)).map(([code, enabled]) => <div key={code}>{getDisplayLabel("entitlement.featureCode", code, code)}：{enabled ? "已包含" : "未包含"}</div>)}</div>}
      <p className="mt-2 text-xs text-slate-500">发布新版本用于后续套餐开通。已生效的企业套餐权益不会自动改变，需通过合同订单办理套餐变更。</p>
    </div>;
  }

  function renderForm() {
    const inputClass = "w-full rounded-lg border border-slate-300 px-3 py-2 text-sm";
    const labelClass = "mb-1 block text-sm font-medium text-slate-700";
    const field = (label: string, key: keyof ProductForm, type: "text" | "number" = "text", placeholder?: string, required = false) => <div><label className={labelClass}>{label}{required && <span className="ml-1 text-red-600">*</span>}</label><input type={type} required={required} value={form[key] as string} onChange={(event) => setForm((current) => ({ ...current, [key]: event.target.value }))} placeholder={placeholder} className={inputClass} /></div>;
    return <div className="mb-6 rounded-xl border border-blue-100 bg-blue-50/40 p-6 shadow-sm">
      <div className="mb-4 flex items-center justify-between"><h2 className="text-base font-semibold text-slate-700">发布{kinds.find((item) => item.key === activeKind)?.label}</h2><button onClick={resetForm} className="text-slate-400 hover:text-slate-600" aria-label="关闭配置表单"><X className="h-5 w-5" /></button></div>
      <div className="space-y-4">
        {activeKind === "trial-rules" && <><div className="grid grid-cols-1 gap-4 md:grid-cols-4">{field("版本", "version", "number")}<div><label className={labelClass}>试用有效期</label><select value={form.durationDays} onChange={(e) => setForm({ ...form, durationDays: e.target.value })} className={inputClass}><option value="3">3 天</option><option value="7">7 天</option><option value="10">10 天</option></select></div>{field("席位数", "seatCapacity", "number")}{field("积分数", "creditAmount", "number")}</div>{renderFeatureSelector()}</>}
        {activeKind === "plans" && <><div className="grid grid-cols-1 gap-4 md:grid-cols-2"><div><label className={labelClass}>适用对象</label><select value={form.audience} onChange={(e) => setForm({ ...form, audience: e.target.value as ProductForm["audience"] })} className={inputClass}><option value="ENTERPRISE">企业租户</option><option value="PERSONAL">个人租户</option></select></div>{field("套餐编码", "code", "text", "请填写唯一套餐代码")}{field("套餐名称", "displayName", "text", "请输入套餐名称", true)}{field("版本", "version", "number")}<div><label className={labelClass}>计费周期</label><select value={form.billingPeriod} onChange={(e) => setForm({ ...form, billingPeriod: e.target.value as ProductForm["billingPeriod"] })} className={inputClass}><option value="MONTH">月</option><option value="QUARTER">季</option><option value="YEAR">年</option></select></div>{field("有效期月份", "durationMonths", "number")}{field("每席套餐积分", "perSeatCreditAmount", "number")}{field("价格（微元）", "priceMicroYuan", "number", "1 元 = 1000000 微元")}</div>{renderFeatureSelector()}</>}
        {activeKind === "credit-packs" && <div className="grid grid-cols-1 gap-4 md:grid-cols-2"><div><label className={labelClass}>适用对象</label><select value={form.audience} onChange={(e) => setForm({ ...form, audience: e.target.value as ProductForm["audience"] })} className={inputClass}><option value="ENTERPRISE">企业租户</option><option value="PERSONAL">个人租户</option></select></div>{field("积分包编码", "code", "text", "请填写唯一积分包代码")}{field("积分包名称", "displayName", "text", "请输入积分包名称", true)}{field("版本", "version", "number")}{field("积分数量", "creditAmount", "number")}<div><label className={labelClass}>有效期单位</label><select value={form.validUnit} onChange={(e) => setForm({ ...form, validUnit: e.target.value as ProductForm["validUnit"] })} className={inputClass}><option value="MONTH">月</option><option value="YEAR">年</option></select></div>{field("有效期数量", "validCount", "number")}{field("价格（微元）", "priceMicroYuan", "number", "1 元 = 1000000 微元")}</div>}
        {activeKind === "ai-feature-rules" && <><div className="grid grid-cols-1 gap-4 md:grid-cols-3"><div>{field("智能能力", "capabilityCode", "text", "填写服务端定义的能力代码")}<p className="mt-1 text-xs text-slate-500">能力名称：{getDisplayLabel("ai.capability", form.capabilityCode, "能力名称待配置")}</p></div><div><label className={labelClass}>执行服务</label><select value={form.agentId} onChange={(e) => setForm({ ...form, agentId: e.target.value as ProductForm["agentId"], billingMethod: e.target.value === "complex_recruitment_agent" ? "FIXED_PER_EXECUTION" : form.billingMethod })} className={inputClass}><option value="simple_recruitment_agent">标准招聘执行服务</option><option value="complex_recruitment_agent">高级招聘执行服务</option></select></div><div><label className={labelClass}>计费方式</label><select value={form.billingMethod} onChange={(e) => setForm({ ...form, billingMethod: e.target.value as ProductForm["billingMethod"] })} className={inputClass}><option value="TOKEN" disabled={form.agentId === "complex_recruitment_agent"}>按模型用量计费</option><option value="FIXED_PER_EXECUTION">按次固定积分</option><option value="FIXED_PER_CANDIDATE">按候选人固定积分</option></select></div>{field("操作", "operation", "text", "留空时将依据所选能力自动确定")}<div><label className={labelClass}>权限码</label><select value={form.permissionCode} onChange={(e) => setForm({ ...form, permissionCode: e.target.value })} className={inputClass}><option value="">请选择已启用权限码</option>{activePermissions.map((permission) => <option key={permission.code} value={permission.code}>{getDisplayLabel("permission.user", permission.code, "权限名称待配置")}</option>)}</select></div><div>{field("套餐权益代码", "entitlementFeatureCode", "text", "填写服务端权益目录中的代码")}<p className="mt-1 text-xs text-slate-500">权益名称：{getDisplayLabel("entitlement.featureCode", form.entitlementFeatureCode, "权益名称待配置")}。该代码不等同于功能权限。</p></div>{form.billingMethod !== "TOKEN" && field("每计费单元积分", "fixedCreditsPerUnit", "number", "正式价格由运营确认后填写")}</div>{form.agentId === "simple_recruitment_agent" && <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-3">{field("模型编号", "modelId", "text", "标准执行服务规则必填")}{field("计量器编号", "tokenizerId", "text", "标准执行服务规则必填")}{field("预占积分", "reservationCredits", "number")}{field("最大输入词元数", "maxInputTokens", "number")}{field("最大输出词元数", "maxOutputTokens", "number")}{field("输入词元/积分", "inputTokensPerCredit", "number")}{field("输出词元/积分", "outputTokensPerCredit", "number")}</div>}{form.agentId === "complex_recruitment_agent" && <p className="mt-3 text-xs text-slate-500">高级执行服务规则绑定 Recruitment Distribution v1.1.0 外部契约与文件、文本上限；模型和计量器不适用。按候选人计费仅用于候选人匹配。权益代码与功能权限代码分别维护，须按对应服务端目录填写。</p>}</>}
        {formError && <div role="alert" className="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">{formError}</div>}
        {formErrorCause != null && <AdminErrorNotice error={formErrorCause} fallback="配置发布暂未成功，请检查填写内容后重试。" className="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700" />}
        <div className="flex gap-2"><button onClick={() => publishMutation.mutate()} disabled={publishMutation.isPending} className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50">{publishMutation.isPending ? "发布中…" : "发布配置"}</button><button onClick={resetForm} className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 hover:bg-white">取消</button></div>
      </div>
    </div>;
  }

  function retire(row: ProductRow) {
    if (!row.id || row.status !== "PUBLISHED") return;
    if (typeof window !== "undefined" && !window.confirm("确认下线此配置吗？下线后不会影响已生效的套餐权益。")) return;
    retireMutation.mutate({ kind: activeKind, id: row.id });
  }

  function renderAction(row: ProductRow) {
    const status = statusLabel(row.status);
    return <><Badge variant={status.variant}>{status.label}</Badge>{canEdit && activeKind === "plans" && <button type="button" onClick={() => copyPlan(row)} className="ml-3 text-xs text-blue-600">创建新版本</button>}{canEdit && row.status === "PUBLISHED" && row.id && <button onClick={() => retire(row)} disabled={retireMutation.isPending} className="ml-3 inline-flex items-center gap-1 text-xs text-red-600 hover:text-red-700 disabled:opacity-50"><Trash2 className="h-3 w-3" /> 下线</button>}</>;
  }

  function renderTable() {
    const rows = products ?? [];
    const tableHead = "border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold text-slate-500 [&>th]:px-4 [&>th]:py-3";
    const tableRow = "border-b border-slate-100 text-sm text-slate-600 hover:bg-slate-50 [&>td]:px-4 [&>td]:py-3";
    if (activeKind === "trial-rules") return <table className="w-full"><thead><tr className={tableHead}><th>版本</th><th>有效期</th><th>席位数</th><th>积分</th><th>套餐权益</th><th>状态/操作</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)} className={tableRow}><td>v{numberValue(row.version)}</td><td>{numberValue(row.duration_days)} 天</td><td>{numberValue(row.seat_capacity)}</td><td>{numberValue(row.credit_amount)}</td><td className="max-w-xs truncate">{featureSnapshotValue(row.feature_snapshot)}</td><td>{renderAction(row)}</td></tr>)}</tbody></table>;
    if (activeKind === "plans") return <table className="w-full"><thead><tr className={tableHead}><th>套餐名称</th><th>适用对象</th><th>周期</th><th>每席积分</th><th>价格</th><th>套餐权益</th><th>状态/操作</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)} className={tableRow}><td><div className="flex items-center gap-2"><Package className="h-4 w-4 text-slate-400"/><div><div className="font-medium text-slate-800">{resolveCatalogLabel({ code: String(row.code ?? ""), displayName: String(row.display_name ?? ""), displayNameIsCanonical: true, missingLabel: "套餐名称待补充" }).label}</div><div className="text-xs text-slate-400">版本 {numberValue(row.version)}<ProductCode label="套餐编码" value={row.code}/></div></div></div></td><td>{getDisplayLabel("product.audience", String(row.audience ?? ""), "适用对象暂未识别")}</td><td>{getDisplayLabel("plan.billingPeriod", String(row.billing_period ?? ""), "周期暂未识别")} / {numberValue(row.duration_months)} 个月</td><td>{numberValue(row.per_seat_credit_amount)}</td><td>{moneyValue(row.price_micro_yuan)}</td><td>{featureSnapshotValue(row.feature_snapshot)}</td><td>{renderAction(row)}</td></tr>)}</tbody></table>;
    if (activeKind === "credit-packs") return <table className="w-full"><thead><tr className={tableHead}><th>积分包</th><th>适用对象</th><th>积分</th><th>有效期</th><th>价格</th><th>状态/操作</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)} className={tableRow}><td><div className="font-medium text-slate-800">{resolveCatalogLabel({ code: String(row.code ?? ""), displayName: String(row.display_name ?? ""), displayNameIsCanonical: true, missingLabel: "积分包名称待补充" }).label}</div><div className="text-xs text-slate-400">版本 {numberValue(row.version)}<ProductCode label="积分包编码" value={row.code}/></div></td><td>{getDisplayLabel("product.audience", String(row.audience ?? ""), "适用对象暂未识别")}</td><td>{numberValue(row.credit_amount)}</td><td>{numberValue(row.valid_count)} {getDisplayLabel("creditPack.validUnit", String(row.valid_unit ?? ""), "有效期单位暂未识别")}</td><td>{moneyValue(row.price_micro_yuan)}</td><td>{renderAction(row)}</td></tr>)}</tbody></table>;
    return <table className="w-full"><thead><tr className={tableHead}><th>招聘能力/路由</th><th>计费方式</th><th>权限/权益</th><th>模型与输入输出限制</th><th>价格</th><th>状态/操作</th></tr></thead><tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)} className={tableRow}><td className="font-medium text-slate-800"><CodeName domain="ai.capability" value={row.capability_code} fallback="能力名称待配置"/><div className="text-xs text-slate-400"><CodeName domain="ai.agentId" value={row.agent_id} fallback="执行路由待配置"/> / <CodeName domain="commercial.operation" value={row.operation} fallback="操作名称待配置"/></div></td><td>{getDisplayLabel("ai.billingMethod", String(row.billing_method ?? ""), "计费方式暂未识别")}</td><td><div className="text-xs text-slate-600">权限：<CodeName domain="permission.user" value={row.permission_code} fallback="权限名称待配置"/></div><div className="text-xs text-slate-400">权益：<CodeName domain="entitlement.featureCode" value={row.entitlement_feature_code} fallback="权益名称待配置"/></div></td><td><RuleModelDetails row={row}/></td><td>{row.billing_method === "TOKEN" ? `预占 ${numberValue(row.reservation_credits)} 积分` : `${numberValue(row.fixed_credits_per_unit)} 积分 / 单元（上限 ${numberValue(row.maximum_charge)}）`}</td><td>{renderAction(row)}</td></tr>)}</tbody></table>;
  }

  return <div>
    <div className="mb-6 flex items-start justify-between"><div><h1 className="text-2xl font-bold text-slate-800">产品运营</h1><p className="mt-1 text-sm text-slate-500">管理平台商业化产品配置</p></div>{canEdit && <button onClick={openForm} className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700"><Plus className="h-4 w-4" /> 发布配置</button>}</div>
    {message && <div className={`mb-4 rounded-lg px-4 py-2 text-sm ${message.includes("失败") || message.includes(":") ? "bg-red-50 text-red-700" : "bg-green-50 text-green-700"}`}>{message}</div>}
    {retireError != null && <AdminErrorNotice error={retireError} fallback="配置下线暂未成功，请稍后重试。" className="mb-4 rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700" />}
    <div className="mb-4 flex flex-wrap gap-2">{kinds.map((kind) => <button key={kind.key} onClick={() => selectKind(kind.key)} className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${activeKind === kind.key ? "bg-blue-600 text-white" : "bg-slate-100 text-slate-600 hover:bg-slate-200"}`}>{kind.label}</button>)}</div>
    {showForm && renderForm()}
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">{isLoading ? <div className="p-8 text-center text-sm text-slate-400">加载中…</div> : isError ? <div className="p-8 text-sm text-red-600"><AdminErrorNotice error={productsError} fallback="产品配置加载暂未成功，请稍后重试。" /></div> : !products || products.length === 0 ? <div className="p-8 text-center text-sm text-slate-400">暂无产品配置</div> : renderTable()}</div>
  </div>;
}
