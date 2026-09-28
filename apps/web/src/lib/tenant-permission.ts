"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api-client";

/** Reads the BOSS-owned permission decision for the active Tenant. */
export function useTenantPermission(tenantId: string | null, permissionCode: string) {
  const [allowed, setAllowed] = useState(false);
  const [loadedTenantId, setLoadedTenantId] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    if (!tenantId) return () => { active = false; };
    apiFetch<{ allowed: boolean }>(`/tenants/${tenantId}/permissions/${encodeURIComponent(permissionCode)}`)
      .then(result => {
        if (active) {
          setAllowed(result.allowed === true);
          setLoadedTenantId(tenantId);
        }
      })
      .catch(() => {
        if (active) {
          setAllowed(false);
          setLoadedTenantId(tenantId);
        }
      });
    return () => { active = false; };
  }, [tenantId, permissionCode]);

  return {
    allowed: Boolean(tenantId && loadedTenantId === tenantId && allowed),
    loading: Boolean(tenantId && loadedTenantId !== tenantId),
  };
}
