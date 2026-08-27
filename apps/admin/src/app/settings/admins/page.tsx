"use client";

// 管理员管理页面：列表 + 新增/编辑/禁用
import { Plus, Pencil, Ban } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type Admin = {
  adminId: string;
  displayName: string;
  role: string;
  status: string;
  createdAt: string;
};

// 新增管理员表单校验
const createAdminSchema = z.object({
  displayName: z.string().min(1, "请输入显示名"),
  key: z.string().min(8, "密钥至少8位"),
  role: z.string().min(1, "请选择角色"),
});

type CreateAdminForm = z.infer<typeof createAdminSchema>;

export default function AdminsPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [showDialog, setShowDialog] = useState(false);

  const { data: admins, isLoading } = useQuery({
    queryKey: ["admins"],
    queryFn: () => adminApiFetch<{ items: Admin[] }>("/platform/admins"),
  });

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateAdminForm>({
    resolver: zodResolver(createAdminSchema),
    defaultValues: { role: "PLATFORM_OPERATOR" },
  });

  const createMutation = useMutation({
    mutationFn: (data: CreateAdminForm) =>
      adminApiFetch("/platform/admins", {
        method: "POST",
        body: JSON.stringify(data),
      }),
    onSuccess: () => {
      reset();
      setShowDialog(false);
      queryClient.invalidateQueries({ queryKey: ["admins"] });
    },
  });

  const disableMutation = useMutation({
    mutationFn: (adminId: string) =>
      adminApiFetch(`/platform/admins/${adminId}/status`, {
        method: "PUT",
        body: JSON.stringify({ status: "DISABLED" }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["admins"] });
    },
  });

  function getRoleBadge(role: string) {
    if (role === "SUPER_ADMIN") return <Badge variant="danger">超级管理员</Badge>;
    if (role === "PLATFORM_OPERATOR") return <Badge variant="info">平台运营</Badge>;
    return <Badge>{role}</Badge>;
  }

  function getStatusBadge(status: string) {
    if (status === "ACTIVE") return <Badge variant="success">正常</Badge>;
    if (status === "DISABLED") return <Badge variant="danger">已禁用</Badge>;
    return <Badge>{status}</Badge>;
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-800">管理员管理</h1>
          <p className="mt-1 text-sm text-slate-500">管理系统管理员账号</p>
        </div>
        <Button onClick={() => setShowDialog(true)}>
          <Plus className="h-4 w-4" />
          新增管理员
        </Button>
      </div>

      {/* 管理员列表 */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : admins?.items && admins.items.length > 0 ? (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">显示名</th>
                <th className="px-4 py-3">角色</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">创建时间</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {admins.items.map((admin) => (
                <tr key={admin.adminId} className="border-b border-slate-100">
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">
                    {admin.displayName}
                  </td>
                  <td className="px-4 py-3">{getRoleBadge(admin.role)}</td>
                  <td className="px-4 py-3">{getStatusBadge(admin.status)}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{admin.createdAt}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => router.push(`/settings/admins/${admin.adminId}`)}
                      >
                        <Pencil className="h-4 w-4" />
                        编辑
                      </Button>
                      {admin.status === "ACTIVE" && (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            if (confirm("确定要禁用该管理员吗？")) {
                              disableMutation.mutate(admin.adminId);
                            }
                          }}
                          disabled={disableMutation.isPending}
                        >
                          <Ban className="h-4 w-4" />
                          禁用
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="p-8 text-center text-sm text-slate-400">暂无管理员数据</div>
        )}
      </div>

      {/* 新增管理员对话框 */}
      {showDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
            <h2 className="mb-4 text-lg font-bold text-slate-800">新增管理员</h2>
            <form
              onSubmit={handleSubmit((data) => createMutation.mutate(data))}
              className="space-y-4"
            >
              <div>
                <label className="mb-2 block text-sm font-medium text-slate-700">显示名</label>
                <Input {...register("displayName")} placeholder="请输入显示名" />
                {errors.displayName && (
                  <p className="mt-1 text-xs text-red-500">{errors.displayName.message}</p>
                )}
              </div>
              <div>
                <label className="mb-2 block text-sm font-medium text-slate-700">管理密钥</label>
                <Input {...register("key")} type="password" placeholder="至少8位密钥" />
                {errors.key && (
                  <p className="mt-1 text-xs text-red-500">{errors.key.message}</p>
                )}
              </div>
              <div>
                <label className="mb-2 block text-sm font-medium text-slate-700">角色</label>
                <select
                  {...register("role")}
                  className="h-10 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
                >
                  <option value="PLATFORM_OPERATOR">平台运营</option>
                  <option value="SUPER_ADMIN">超级管理员</option>
                </select>
                {errors.role && (
                  <p className="mt-1 text-xs text-red-500">{errors.role.message}</p>
                )}
              </div>

              {createMutation.error && (
                <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
                  {(createMutation.error as Error).message}
                </div>
              )}

              <div className="flex justify-end gap-3">
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => {
                    setShowDialog(false);
                    reset();
                  }}
                >
                  取消
                </Button>
                <Button type="submit" disabled={createMutation.isPending}>
                  {createMutation.isPending ? "创建中…" : "创建"}
                </Button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}