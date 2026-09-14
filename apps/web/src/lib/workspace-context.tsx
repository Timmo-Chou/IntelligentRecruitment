"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { apiFetch, ApiError } from "@/lib/api-client";

export type Workspace = {
  id: string;
  productDomain: "RECRUITMENT";
  type: "PERSONAL" | "ENTERPRISE";
  name: string;
  status: string;
  roleCode: string | null;
  owner: boolean;
  seatAssigned: boolean;
};

type WorkspaceContextValue = {
  workspaceId: string | null;
  workspace: Workspace | null;
  workspaces: Workspace[];
  loading: boolean;
  error: string | null;
  notAuthenticated: boolean;
  refresh: () => Promise<void>;
  selectWorkspace: (workspaceId: string) => void;
};

const WorkspaceContext = createContext<WorkspaceContextValue>({ workspaceId: null, workspace: null, workspaces: [], loading: true, error: null, notAuthenticated: false, refresh: async () => {}, selectWorkspace: () => {} });
export function useWorkspace() { return useContext(WorkspaceContext); }

type TenantContextResponse = { tenantId: string; productDomain: string; tenantType: "PERSONAL" | "ENTERPRISE"; tenantName: string; tenantStatus: string; roleCode?: string | null; owner: boolean; seatAssigned: boolean };

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<Omit<WorkspaceContextValue, "refresh" | "selectWorkspace">>({ workspaceId: null, workspace: null, workspaces: [], loading: true, error: null, notAuthenticated: false });

  const refresh = useCallback(async () => {
    setState(previous => ({ ...previous, loading: true, error: null, notAuthenticated: false }));
    try {
      const response = await apiFetch<{ tenants: TenantContextResponse[] }>("/tenants/contexts");
      const workspaces = response.tenants.filter(item => item.productDomain === "RECRUITMENT").map(item => ({
        id: item.tenantId, productDomain: "RECRUITMENT" as const, type: item.tenantType, name: item.tenantName,
        status: item.tenantStatus, roleCode: item.roleCode ?? null, owner: item.owner, seatAssigned: item.seatAssigned,
      }));
      const savedId = window.localStorage.getItem("active-tenant-id");
      const selected = workspaces.find(item => item.id === savedId) ?? workspaces.find(item => item.type === "PERSONAL") ?? workspaces[0] ?? null;
      if (selected) window.localStorage.setItem("active-tenant-id", selected.id);
      setState({ workspaceId: selected?.id ?? null, workspace: selected, workspaces, loading: false, error: null, notAuthenticated: false });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) setState(previous => ({ ...previous, loading: false, notAuthenticated: true }));
      else setState(previous => ({ ...previous, loading: false, error: error instanceof Error ? error.message : "加载租户失败" }));
    }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  const selectWorkspace = useCallback((workspaceId: string) => {
    setState(previous => {
      const workspace = previous.workspaces.find(item => item.id === workspaceId);
      if (!workspace) return previous;
      window.localStorage.setItem("active-tenant-id", workspace.id);
      queryClient.clear();
      return { ...previous, workspaceId: workspace.id, workspace };
    });
  }, [queryClient]);
  return <WorkspaceContext.Provider value={{ ...state, refresh, selectWorkspace }}>{children}</WorkspaceContext.Provider>;
}
