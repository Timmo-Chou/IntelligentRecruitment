"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { apiFetch, ApiError } from "@/lib/api-client";

export type Tenant = {
  id: string;
  productDomain: "RECRUITMENT";
  type: "PERSONAL" | "ENTERPRISE";
  name: string;
  status: string;
  roleCode: string | null;
  owner: boolean;
  seatAssigned: boolean;
};

type TenantContextValue = {
  tenantId: string | null;
  tenant: Tenant | null;
  tenants: Tenant[];
  loading: boolean;
  error: string | null;
  notAuthenticated: boolean;
  refresh: () => Promise<void>;
  selectTenant: (tenantId: string) => void;
};

const TenantContext = createContext<TenantContextValue>({ tenantId: null, tenant: null, tenants: [], loading: true, error: null, notAuthenticated: false, refresh: async () => {}, selectTenant: () => {} });
export function useTenant() { return useContext(TenantContext); }

type TenantContextResponse = { tenantId: string; productDomain: string; tenantType: "PERSONAL" | "ENTERPRISE"; tenantName: string; tenantStatus: string; roleCode?: string | null; owner: boolean; seatAssigned: boolean };

export function TenantProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<Omit<TenantContextValue, "refresh" | "selectTenant">>({ tenantId: null, tenant: null, tenants: [], loading: true, error: null, notAuthenticated: false });

  const refresh = useCallback(async () => {
    setState(previous => ({ ...previous, loading: true, error: null, notAuthenticated: false }));
    try {
      const response = await apiFetch<{ tenants: TenantContextResponse[] }>("/tenants/contexts");
      const tenants = response.tenants.filter(item => item.productDomain === "RECRUITMENT").map(item => ({
        id: item.tenantId, productDomain: "RECRUITMENT" as const, type: item.tenantType, name: item.tenantName,
        status: item.tenantStatus, roleCode: item.roleCode ?? null, owner: item.owner, seatAssigned: item.seatAssigned,
      }));
      const savedId = window.localStorage.getItem("active-tenant-id");
      const selected = tenants.find(item => item.id === savedId) ?? tenants.find(item => item.type === "PERSONAL") ?? tenants[0] ?? null;
      if (selected) window.localStorage.setItem("active-tenant-id", selected.id);
      setState({ tenantId: selected?.id ?? null, tenant: selected, tenants, loading: false, error: null, notAuthenticated: false });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) setState(previous => ({ ...previous, loading: false, notAuthenticated: true }));
      else setState(previous => ({ ...previous, loading: false, error: error instanceof Error ? error.message : "加载租户失败" }));
    }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  const selectTenant = useCallback((tenantId: string) => {
    setState(previous => {
      const tenant = previous.tenants.find(item => item.id === tenantId);
      if (!tenant) return previous;
      window.localStorage.setItem("active-tenant-id", tenant.id);
      queryClient.clear();
      return { ...previous, tenantId: tenant.id, tenant };
    });
  }, [queryClient]);
  return <TenantContext.Provider value={{ ...state, refresh, selectTenant }}>{children}</TenantContext.Provider>;
}
