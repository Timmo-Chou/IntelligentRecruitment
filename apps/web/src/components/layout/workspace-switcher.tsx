"use client";

import { Building2, ChevronDown } from "lucide-react";
import { useWorkspace } from "@/lib/workspace-context";

export function WorkspaceSwitcher() {
  const { workspaceId, workspace, workspaces, selectWorkspace } = useWorkspace();
  if (!workspace) return null;
  return (
    <label className="relative hidden items-center gap-2 rounded-lg border border-[#cddff1] bg-white/75 px-3 text-xs text-[#36527f] md:flex">
      <Building2 size={15} aria-hidden="true" />
      <select
        aria-label="当前工作空间"
        className="h-9 max-w-48 appearance-none bg-transparent pr-5 font-semibold outline-none"
        value={workspaceId ?? ""}
        onChange={(event) => selectWorkspace(event.target.value)}
      >
        {workspaces.map((item) => <option key={item.id} value={item.id}>{item.name}{item.hasDataAccess ? "" : "（无数据权限）"}</option>)}
      </select>
      <ChevronDown className="pointer-events-none absolute right-2" size={13} />
    </label>
  );
}
