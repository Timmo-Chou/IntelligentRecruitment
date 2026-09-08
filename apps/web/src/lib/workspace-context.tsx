"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { apiFetch, ApiError } from "@/lib/api-client";

export type Workspace = {
  id: string;
  companyId: string | null;
  type: "PERSONAL" | "COMPANY";
  name: string;
  ownerUserId: string;
  status: string;
  memberCount: number;
  hasDataAccess: boolean;
  currentRole: string | null;
};

export type OrgUnit = { id: string; parentId: string | null; name: string; status: string };

type BossCompanyContext = {
  companyId: string;
  tenantId: string;
  legalName: string;
  entityType: "ENTERPRISE";
  companyStatus: string;
  tenantStatus: string;
  companyOwner: boolean;
};

type BossTenantContext = {
  tenantId: string;
  tenantType: "PERSONAL" | "ENTERPRISE";
  tenantName: string;
  tenantStatus: string;
};

type BossContexts = { tenants: BossTenantContext[]; companies: BossCompanyContext[] };

type WorkspaceContextValue = {
  workspaceId: string | null;
  workspace: Workspace | null;
  workspaces: Workspace[];
  loading: boolean;
  error: string | null;
  notAuthenticated: boolean;
  refresh: () => Promise<void>;
  selectWorkspace: (workspaceId: string) => void;
  orgUnitId: string | null;
  orgUnits: OrgUnit[];
  orgUnitsLoading: boolean;
  selectOrgUnit: (orgUnitId: string | null) => Promise<void>;
  loadOrgUnits: () => Promise<void>;
};

const WorkspaceContext = createContext<WorkspaceContextValue>({
  workspaceId: null,
  workspace: null,
  workspaces: [],
  loading: true,
  error: null,
  notAuthenticated: false,
  refresh: async () => {},
  selectWorkspace: () => {},
  orgUnitId: null,
  orgUnits: [],
  orgUnitsLoading: false,
  selectOrgUnit: async () => {},
  loadOrgUnits: async () => {},
});

export function useWorkspace() {
  return useContext(WorkspaceContext);
}

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<WorkspaceContextValue>({
    workspaceId: null,
    workspace: null,
    workspaces: [],
    loading: true,
    error: null,
    notAuthenticated: false,
    refresh: async () => {},
      selectWorkspace: () => {},
      orgUnitId: null,
      orgUnits: [],
      orgUnitsLoading: false,
      selectOrgUnit: async () => {},
      loadOrgUnits: async () => {},
  });

  const loadWorkspaces = useCallback(async () => {
    setState((prev) => ({ ...prev, loading: true, error: null, notAuthenticated: false }));
    try {
      // 先验证认证状态，再请求工作空间列表，与 SessionSummary 流程对齐
      await apiFetch<unknown>("/me");
      const contexts = await apiFetch<BossContexts>("/companies/contexts");
      // A Personal Tenant is a first-class BOSS context, not a Company.
      const personalSpaces: Workspace[] = contexts.tenants
        .filter((tenant) => tenant.tenantType === "PERSONAL")
        .map((tenant) => ({
          id: tenant.tenantId,
          companyId: null,
          type: "PERSONAL",
          name: tenant.tenantName,
          ownerUserId: "",
          status: tenant.tenantStatus,
          memberCount: 1,
          hasDataAccess: tenant.tenantStatus === "ACTIVE",
          currentRole: "BOSS_TENANT_OWNER",
        }));
      const companySpaces: Workspace[] = contexts.companies.map((company) => ({
        id: company.companyId,
        companyId: company.companyId,
        type: "COMPANY",
        name: company.legalName,
        ownerUserId: "",
        status: company.companyStatus,
        memberCount: 0,
        hasDataAccess: company.companyStatus === "ACTIVE" && company.tenantStatus === "ACTIVE",
        currentRole: company.companyOwner ? "BOSS_COMPANY_OWNER" : "BOSS_COMPANY_MEMBER",
      }));
      const spaces = [...personalSpaces, ...companySpaces];
      const savedId = window.localStorage.getItem("active-boss-context-id");
      const accessible = spaces.filter((item) => item.hasDataAccess);
      // 优先匹配已保存的空间（含无数据权限的空间），其次是有数据权限的空间，最后退到任意空间，
      // 确保企业用户即使只有「无数据权限」空间时，顶部切换器仍能正常展示
      const selected = spaces.find((item) => item.id === savedId) ?? accessible[0] ?? spaces[0] ?? null;
      if (selected) window.localStorage.setItem("active-boss-context-id", selected.id);
      setState((prev) => ({
        ...prev,
        workspaceId: selected?.id ?? null,
        workspace: selected,
        workspaces: spaces,
        loading: false,
        error: null,
        notAuthenticated: false,
        refresh: prev.refresh,
      }));
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setState((prev) => ({ ...prev, loading: false, notAuthenticated: true, refresh: prev.refresh }));
      } else {
        setState((prev) => ({
          ...prev,
          loading: false,
          error: error instanceof Error ? error.message : "加载工作空间失败",
          refresh: prev.refresh,
        }));
      }
    }
  }, []);

  useEffect(() => {
    loadWorkspaces();
  }, [loadWorkspaces]);

  const selectWorkspace = useCallback((workspaceId: string) => {
    setState((previous) => {
      const selected = previous.workspaces.find((item) => item.id === workspaceId);
      if (!selected) return previous;
      window.localStorage.setItem("active-boss-context-id", selected.id);
      queryClient.clear();
      return { ...previous, workspaceId: selected.id, workspace: selected, orgUnitId: null, orgUnits: [] };
    });
  }, [queryClient]);

  const loadOrgUnits = useCallback(async () => {
    const selected = state.workspace;
    if (!selected || selected.type !== "COMPANY") {
      setState(previous => ({ ...previous, orgUnits: [], orgUnitId: null, orgUnitsLoading: false }));
      return;
    }
    setState(previous => ({ ...previous, orgUnitsLoading: true }));
    try {
      const result = await apiFetch<{ orgUnits: OrgUnit[]; recentOrgUnitId: string | null }>(`/companies/${selected.companyId}/org-units/accessible`);
      setState(previous => ({ ...previous, orgUnits: result.orgUnits ?? [], orgUnitId: result.recentOrgUnitId ?? null, orgUnitsLoading: false }));
    } catch {
      setState(previous => ({ ...previous, orgUnits: [], orgUnitId: null, orgUnitsLoading: false }));
    }
  }, [state.workspace]);

  useEffect(() => { void loadOrgUnits(); }, [loadOrgUnits]);

  const selectOrgUnit = useCallback(async (orgUnitId: string | null) => {
    const selected = state.workspace;
    if (!selected || selected.type !== "COMPANY") return;
    if (orgUnitId !== null) await apiFetch(`/companies/${selected.companyId}/org-units/recent`, {
      method: "POST", body: JSON.stringify({ orgUnitId }),
    });
    setState(previous => ({ ...previous, orgUnitId }));
  }, [state.workspace]);

  // 将 refresh 方法绑定到 state 中，对外暴露重试能力
  const value: WorkspaceContextValue = {
    ...state,
    refresh: loadWorkspaces,
    selectWorkspace,
    selectOrgUnit,
    loadOrgUnits,
  };

  return (
    <WorkspaceContext.Provider value={value}>
      {children}
    </WorkspaceContext.Provider>
  );
}
