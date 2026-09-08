"use client";

import { Building2, ChevronDown, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { useWorkspace } from "@/lib/workspace-context";

export function WorkspaceSwitcher() {
  const { workspace, orgUnitId, orgUnits, orgUnitsLoading, selectOrgUnit } = useWorkspace();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const visible = useMemo(() => orgUnits.filter(item => item.name.toLowerCase().includes(query.trim().toLowerCase())), [orgUnits, query]);
  if (!workspace || workspace.type !== "COMPANY" || (!orgUnitsLoading && orgUnits.length === 0)) return null;
  const selectedName = orgUnits.find(item => item.id === orgUnitId)?.name ?? "全部 Org Unit";
  return (
    <div className="relative hidden md:block">
      <button type="button" aria-expanded={open} onClick={() => setOpen(value => !value)} className="flex h-9 items-center gap-2 rounded-lg border border-[#cddff1] bg-white/75 px-3 text-xs text-[#36527f]">
      <Building2 size={15} aria-hidden="true" />
      <span className="max-w-44 truncate font-semibold">Org Unit：{orgUnitsLoading ? "加载中…" : selectedName}</span><ChevronDown size={13} />
      </button>
      {open && <div className="absolute right-0 z-50 mt-2 w-72 rounded-xl border border-[#d6e5f5] bg-white p-3 shadow-xl">
        <label className="relative block"><Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[#7187a8]"/><input autoFocus value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索 Org Unit" className="h-9 w-full rounded-lg border border-[#cddbea] pl-8 pr-3 text-xs outline-none"/></label>
        <div className="mt-2 max-h-64 overflow-y-auto"><button type="button" onClick={() => { void selectOrgUnit(null); setOpen(false); }} className={`block w-full rounded-lg px-3 py-2 text-left text-sm ${orgUnitId === null ? "bg-[#edf5ff] text-[#176ce5]" : "hover:bg-[#f6f9fd]"}`}>全部 Org Unit</button>{visible.map(item => <button key={item.id} type="button" onClick={() => { void selectOrgUnit(item.id); setOpen(false); }} className={`block w-full rounded-lg px-3 py-2 text-left text-sm ${orgUnitId === item.id ? "bg-[#edf5ff] text-[#176ce5]" : "hover:bg-[#f6f9fd]"}`}>{item.name}</button>)}{!visible.length && <p className="px-3 py-2 text-xs text-[#7187a8]">未找到匹配的 Org Unit</p>}</div>
      </div>}
    </div>
  );
}
