"use client";

// 管理员详情/编辑页面
import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

type AdminDetail = {
  adminId: string;
  displayName: string;
  role: string;
  status: string;
  createdAt: string;
};

const editSchema = z.object({
  role: z.string().min(1, "请选择角色"),
  status: z.string().min(1, "请选择状态"),
});

type EditForm = z.infer<typeof editSchema>;

export default function AdminDetailPage() {
  const params = useParams();
  const router = useRouter();
  const queryClient = useQueryClient();
  const adminId = params.adminId as string;

  const { data: admin, isLoading } = useQuery({
    queryKey: ["admin", adminId],
    queryFn: () => adminApiFetch<AdminDetail>(`/platform/admins/${adminId}`),
    enabled: !!adminId,
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EditForm>({
    resolver: zodResolver(editSchema),
  });

  const updateMutation = useMutation({
    mutationFn: (data: EditForm) =>
      adminApiFetch(`/platform/admins/${adminId}`, {
        method: "PUT",
        body: JSON.stringify(data),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["admin", adminId] });
      router.push("/settings/admins");
    },
  });

  if (isLoading) {
    return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;
  }

  if (!admin) {
    return <div className="p-8 text-center text-sm text-red-500">管理员不存在</div>;
  }

  return (
    <div>
      <Link
        href="/settings/admins"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ArrowLeft className="h-4 w-4" />
        返回管理员列表
      </Link>

      <div className="mx-auto max-w-xl">
        <h1 className="mb-6 text-2xl font-bold text-slate-800">编辑管理员</h1>

        <form
          onSubmit={handleSubmit((data) => updateMutation.mutate(data))}
          className="space-y-5 rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
        >
          {/* 只读信息 */}
          <div className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <span className="text-slate-400">显示名</span>
              <p className="mt-0.5 font-medium text-slate-700">{admin.displayName}</p>
            </div>
            <div>
              <span className="text-slate-400">管理员ID</span>
              <p className="mt-0.5 font-mono text-xs text-slate-500">{admin.adminId}</p>
            </div>
            <div>
              <span className="text-slate-400">创建时间</span>
              <p className="mt-0.5 font-medium text-slate-700">{admin.createdAt}</p>
            </div>
          </div>

          <hr className="border-slate-200" />

          {/* 可编辑字段 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">角色</label>
            <select
              {...register("role")}
              defaultValue={admin.role}
              className="h-10 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
            >
              <option value="OPERATOR">操作员</option>
              <option value="ADMIN">管理员</option>
              <option value="SUPER_ADMIN">超级管理员</option>
            </select>
            {errors.role && (
              <p className="mt-1 text-xs text-red-500">{errors.role.message}</p>
            )}
          </div>

          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">状态</label>
            <select
              {...register("status")}
              defaultValue={admin.status}
              className="h-10 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
            >
              <option value="ACTIVE">正常</option>
              <option value="DISABLED">已禁用</option>
            </select>
            {errors.status && (
              <p className="mt-1 text-xs text-red-500">{errors.status.message}</p>
            )}
          </div>

          {updateMutation.error && (
            <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
              {(updateMutation.error as Error).message}
            </div>
          )}

          <div className="flex justify-end gap-3">
            <Button type="button" variant="secondary" onClick={() => router.back()}>
              取消
            </Button>
            <Button type="submit" disabled={updateMutation.isPending}>
              {updateMutation.isPending ? "保存中…" : "保存"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}