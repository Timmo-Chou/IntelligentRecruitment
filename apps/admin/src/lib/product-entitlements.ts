/** Preserve both enabled and disabled entries when publishing a new plan version. */
export function readProductEntitlements(value: unknown): Record<string, boolean> {
  let parsed: unknown = typeof value === "string" ? JSON.parse(value) : value;
  // JdbcTemplate product rows serialize PostgreSQL JSONB as { type, value }.
  if (parsed && typeof parsed === "object" && !Array.isArray(parsed)
      && "type" in parsed && parsed.type === "jsonb" && "value" in parsed && typeof parsed.value === "string") {
    parsed = JSON.parse(parsed.value);
  }
  if (Array.isArray(parsed)) {
    if (parsed.some(code => typeof code !== "string" || !code.trim())) throw new Error("套餐权益包含无效代码");
    return Object.fromEntries(parsed.map(code => [code, true]));
  }
  if (!parsed || typeof parsed !== "object" || Object.entries(parsed).some(([code, enabled]) => !code.trim() || typeof enabled !== "boolean")) {
    throw new Error("套餐权益配置格式无效，不能复制发布");
  }
  return { ...(parsed as Record<string, boolean>) };
}

/** Reuse published AI rules as the source of business entitlement codes. */
export function businessEntitlementCodes(rules: Array<Record<string, unknown>>): string[] {
  return [...new Set(rules.filter(rule => rule.status === "PUBLISHED")
    .map(rule => rule.entitlement_feature_code)
    .filter((code): code is string => typeof code === "string" && !!code.trim()))].sort();
}
