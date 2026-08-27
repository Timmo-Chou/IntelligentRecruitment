"use client";

// 菜单管理页面：树形表格 + 新增/编辑/删除
import { Plus, Pencil, Trash2, GripVertical, Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type MenuItem = {
  id: string;
  parentId: string | null;
  name: string;
  icon: string;
  path: string;
  sortOrder: number;
  visibleToOperator: boolean;
  children: MenuItem[];
};

export default function MenusPage() {
  const queryClient = useQueryClient();

  const { data: menus, isLoading } = useQuery({
    queryKey: ["menus"],
    queryFn: () => adminApiFetch<{ items: MenuItem[] }>("/platform/menus"),
  });

  const deleteMutation = useMutation({
    mutationFn: (menuId: string) =>
      adminApiFetch(`/platform/menus/${menuId}`, { method: "DELETE" }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["menus"] });
    },
  });

  // 扁平化树形结构用于渲染
  function flattenMenu(items: MenuItem[], level = 0): (MenuItem & { level: number })[] {
    const result: (MenuItem & { level: number })[] = [];
    for (const item of items) {
      result.push({ ...item, level });
      if (item.children && item.children.length > 0) {
        result.push(...flattenMenu(item.children, level + 1));
      }
    }
    return result;
  }

  const flatMenus = menus?.items ? flattenMenu(menus.items) : [];

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-800">菜单管理</h1>
          <p className="mt-1 text-sm text-slate-500">管理平台管理后台的菜单结构</p>
        </div>
        <Button>
          <Plus className="h-4 w-4" />
          新增一级菜单
        </Button>
      </div>

      {/* 菜单树形表格 */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : flatMenus.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无菜单数据</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3 w-10">#</th>
                <th className="px-4 py-3">菜单名称</th>
                <th className="px-4 py-3">图标</th>
                <th className="px-4 py-3">路径</th>
                <th className="px-4 py-3">操作员可见</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {flatMenus.map((menu) => (
                <tr key={menu.id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3">
                    <GripVertical className="h-4 w-4 cursor-grab text-slate-300" />
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className="text-sm font-medium text-slate-800"
                      style={{ paddingLeft: `${menu.level * 24}px` }}
                    >
                      {menu.level > 0 && "└ "}
                      {menu.name}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm font-mono text-slate-500">{menu.icon}</td>
                  <td className="px-4 py-3 text-sm font-mono text-slate-500">{menu.path}</td>
                  <td className="px-4 py-3">
                    {menu.visibleToOperator ? (
                      <Eye className="h-4 w-4 text-green-500" />
                    ) : (
                      <EyeOff className="h-4 w-4 text-slate-300" />
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1">
                      <Button size="sm" variant="ghost">
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => {
                          if (confirm("确定要删除该菜单吗？")) {
                            deleteMutation.mutate(menu.id);
                          }
                        }}
                        disabled={deleteMutation.isPending}
                      >
                        <Trash2 className="h-4 w-4 text-red-500" />
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}