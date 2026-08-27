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

type WorkspaceContextValue = {
  workspaceId: string | null;
  workspace: Workspace | null;
  workspaces: Workspace[];
  loading: boolean;
  error: string | null;
  notAuthenticated: boolean;
  refresh: () => void;
  selectWorkspace: (workspaceId: string) => void;
};

const WorkspaceContext = createContext<WorkspaceContextValue>({
  workspaceId: null,
  workspace: null,
  workspaces: [],
  loading: true,
  error: null,
  notAuthenticated: false,
  refresh: () => {},
  selectWorkspace: () => {},
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
    refresh: () => {},
    selectWorkspace: () => {},
  });

  const loadWorkspaces = useCallback(async () => {
    setState((prev) => ({ ...prev, loading: true, error: null, notAuthenticated: false }));
    try {
      // 先验证认证状态，再请求工作空间列表，与 SessionSummary 流程对齐
      await apiFetch<unknown>("/me");
      const spaces = await apiFetch<Workspace[]>("/workspaces");
      const savedId = window.localStorage.getItem("active-workspace-id");
      const accessible = spaces.filter((item) => item.hasDataAccess);
      // 优先匹配已保存的空间（含无数据权限的空间），其次是有数据权限的空间，最后退到任意空间，
      // 确保企业用户即使只有「无数据权限」空间时，顶部切换器仍能正常展示
      const selected = spaces.find((item) => item.id === savedId) ?? accessible[0] ?? spaces[0] ?? null;
      if (selected) window.localStorage.setItem("active-workspace-id", selected.id);
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
      window.localStorage.setItem("active-workspace-id", selected.id);
      queryClient.clear();
      return { ...previous, workspaceId: selected.id, workspace: selected };
    });
  }, [queryClient]);

  // 将 refresh 方法绑定到 state 中，对外暴露重试能力
  const value: WorkspaceContextValue = {
    ...state,
    refresh: loadWorkspaces,
    selectWorkspace,
  };

  return (
    <WorkspaceContext.Provider value={value}>
      {children}
    </WorkspaceContext.Provider>
  );
}
