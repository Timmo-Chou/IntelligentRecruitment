"use client";

// 新建工单页面
import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

// 表单校验
const ticketSchema = z.object({
  creatorName: z.string().min(1, "请输入创建者名称"),
  title: z.string().min(1, "请输入工单标题"),
  category: z.string().min(1, "请选择分类"),
  priority: z.string().min(1, "请选择优先级"),
  body: z.string().min(1, "请输入工单内容"),
});

type TicketForm = z.infer<typeof ticketSchema>;

export default function NewTicketPage() {
  const router = useRouter();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<TicketForm>({
    resolver: zodResolver(ticketSchema),
    defaultValues: {
      priority: "NORMAL",
      category: "OTHER",
    },
  });

  const mutation = useMutation({
    mutationFn: (data: TicketForm) =>
      adminApiFetch("/platform/tickets", {
        method: "POST",
        body: JSON.stringify(data),
      }),
    onSuccess: () => {
      router.push("/tickets");
    },
  });

  return (
    <div>
      <Link
        href="/tickets"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ArrowLeft className="h-4 w-4" />
        返回工单列表
      </Link>

      <div className="mx-auto max-w-2xl">
        <h1 className="mb-6 text-2xl font-bold text-slate-800">新建工单</h1>

        <form
          onSubmit={handleSubmit((data) => mutation.mutate(data))}
          className="space-y-5 rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
        >
          {/* 创建者名称 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">创建者名称</label>
            <Input {...register("creatorName")} placeholder="请输入创建者名称" />
            {errors.creatorName && (
              <p className="mt-1 text-xs text-red-500">{errors.creatorName.message}</p>
            )}
          </div>

          {/* 标题 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">工单标题</label>
            <Input {...register("title")} placeholder="请输入工单标题" />
            {errors.title && (
              <p className="mt-1 text-xs text-red-500">{errors.title.message}</p>
            )}
          </div>

          {/* 分类 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">分类</label>
            <select
              {...register("category")}
              className="h-10 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
            >
              <option value="OTHER">其他</option>
              <option value="ACCOUNT">账号问题</option>
              <option value="BILLING">账单问题</option>
              <option value="TECHNICAL">技术问题</option>
              <option value="FEEDBACK">功能反馈</option>
            </select>
            {errors.category && (
              <p className="mt-1 text-xs text-red-500">{errors.category.message}</p>
            )}
          </div>

          {/* 优先级 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">优先级</label>
            <select
              {...register("priority")}
              className="h-10 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
            >
              <option value="LOW">低</option>
              <option value="NORMAL">普通</option>
              <option value="MEDIUM">中</option>
              <option value="HIGH">高</option>
              <option value="URGENT">紧急</option>
            </select>
            {errors.priority && (
              <p className="mt-1 text-xs text-red-500">{errors.priority.message}</p>
            )}
          </div>

          {/* 内容 */}
          <div>
            <label className="mb-2 block text-sm font-medium text-slate-700">工单内容</label>
            <textarea
              {...register("body")}
              className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
              rows={5}
              placeholder="请描述工单详情…"
            />
            {errors.body && (
              <p className="mt-1 text-xs text-red-500">{errors.body.message}</p>
            )}
          </div>

          {/* 错误信息 */}
          {mutation.error && (
            <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
              {(mutation.error as Error).message}
            </div>
          )}

          {/* 提交 */}
          <div className="flex justify-end gap-3">
            <Button type="button" variant="secondary" onClick={() => router.back()}>
              取消
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "提交中…" : "提交工单"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}