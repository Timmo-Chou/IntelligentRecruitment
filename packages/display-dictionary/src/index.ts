import sourceEntries from "./dictionary-data.json";

export type DictionaryEntry = {
  domain: string;
  code: string;
  label: string;
  applicationClass: string;
};

export type DictionaryUse = "display" | "diagnostic" | "suggestion";

export type DisplayResult = {
  label: string;
  matched: boolean;
  classification?: string;
};

const entries = sourceEntries as DictionaryEntry[];
const index = new Map(entries.map((entry) => [`${entry.domain}\u0000${entry.code}`, entry]));

/**
 * Resolves a stored/API code for display only. The caller must supply the
 * business domain; a bare value such as ACTIVE is intentionally ambiguous.
 * This function never normalizes or changes the value sent back to an API.
 */
export function resolveCodeLabel(
  domain: string,
  value: string | number | boolean | null | undefined,
  unknownLabel = "暂未识别",
  use: DictionaryUse = "display",
): DisplayResult {
  if (value === null || value === undefined || value === "") {
    return { label: unknownLabel, matched: false };
  }

  const entry = index.get(`${domain}\u0000${String(value)}`);
  if (!entry) return { label: unknownLabel, matched: false };

  if (entry.applicationClass.startsWith("旧示例") && use !== "diagnostic") {
    return { label: unknownLabel, matched: false, classification: entry.applicationClass };
  }
  if (entry.applicationClass.startsWith("术语建议") && use !== "suggestion") {
    return { label: unknownLabel, matched: false, classification: entry.applicationClass };
  }
  if (entry.applicationClass.startsWith("原值保留")) {
    return { label: unknownLabel, matched: false, classification: entry.applicationClass };
  }

  return { label: entry.label, matched: true, classification: entry.applicationClass };
}

/** Display-only convenience function. Always pass the field's business domain. */
export function getDisplayLabel(
  domain: string,
  value: string | number | boolean | null | undefined,
  unknownLabel = "暂未识别",
  use: DictionaryUse = "display",
): string {
  return resolveCodeLabel(domain, value, unknownLabel, use).label;
}

/** Safe UI error text: never pass arbitrary server messages or unknown codes through to end users. */
export function getSafeErrorMessage(
  code?: string | null,
  requestId?: string | null,
  fallback = "操作暂未完成，请稍后重试。",
): string {
  const known = code ? resolveCodeLabel("error.common", code) : null;
  if (known?.matched) return known.label;
  return requestId ? `${fallback} 诊断编号：${requestId}` : fallback;
}

/**
 * Resolves a dynamic catalog label without guessing from an English code/name.
 * Use displayNameIsCanonical when the API field is the business record's
 * authoritative display name, including operator-entered plan and pack names.
 * Do not use it for technical codes or labels inferred from an English code.
 */
export function resolveCatalogLabel(input: {
  code: string | null | undefined;
  dictionaryDomain?: string;
  displayNameZh?: string | null;
  displayName?: string | null;
  displayNameIsCanonical?: boolean;
  missingLabel: string;
}): DisplayResult {
  const localized = input.displayNameZh?.trim();
  if (localized) return { label: localized, matched: true, classification: "动态中文目录名称" };

  const staticLabel = input.dictionaryDomain && input.code
    ? resolveCodeLabel(input.dictionaryDomain, input.code)
    : null;
  if (staticLabel?.matched) return staticLabel;

  const canonicalName = input.displayName?.trim();
  // Some directories expose an authoritative user-facing name field. Names
  // entered for a product are business data and may be in any language.
  if (canonicalName && /\p{Script=Han}/u.test(canonicalName)) {
    return { label: canonicalName, matched: true, classification: "动态中文目录名称" };
  }
  if (input.displayNameIsCanonical && canonicalName) {
    return { label: canonicalName, matched: true, classification: "已核实的目录名称" };
  }

  return { label: input.missingLabel, matched: false };
}

/** Maps controlled category codes and preserves user-authored Chinese categories. */
export function resolveFreeTextLabel(domain: string, value: string | null | undefined, unknownLabel: string) {
  if (value == null || value.trim() === "") return unknownLabel;
  const mapped = resolveCodeLabel(domain, value, "");
  if (mapped.matched) return mapped.label;
  return /\p{Script=Han}/u.test(value) ? value : unknownLabel;
}

/** Current and historical resume status values share one display path. */
export function getResumeParseStatusLabel(value: string | null | undefined) {
  const current = resolveCodeLabel("resume.parseStatus", value, "");
  if (current.matched) return current.label;
  return resolveCodeLabel("resume.statusCompatibility", value, "解析状态暂未识别").label;
}

/** User-authored names/text are content, not enum codes. Preserve them verbatim. */
export function displayUserContent(value: string | null | undefined, emptyLabel = "暂无") {
  return value == null || value === "" ? emptyLabel : value;
}

/** Returns the unchanged source value for request/filter bindings. */
export function preserveCode<T extends string | number | boolean | null | undefined>(value: T): T {
  return value;
}

/** Static dictionary metadata is for audit/tools; do not use it to generate selectable options. */
export function getDictionaryEntry(domain: string, code: string): DictionaryEntry | undefined {
  return index.get(`${domain}\u0000${code}`);
}

export function getDictionaryDomains(): string[] {
  return [...new Set(entries.map((entry) => entry.domain))];
}
