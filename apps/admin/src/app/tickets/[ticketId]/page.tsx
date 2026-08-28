"use client";

// 工单详情页：聊天式布局 + 工单信息面板
import { ArrowLeft, Send } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

type TicketMessage = {
  id: string;
  senderType: string; // USER | PLATFORM_ADMIN
  senderName: string;
  body: string;
  createdAt: string;
};

type TicketRow = {
  id: string;
  ticketNumber: string;
  creatorUserId?: string;
  creatorName: string;
  companyId?: string;
  companyName?: string;
  title: string;
  category: string;
  priority: string;
  status: string;
  createdAt: string;
};

type TicketDetail = {
  ticket: TicketRow;
  messages: TicketMessage[];
};

export default function TicketDetailPage() {
  const params = useParams();
  const queryClient = useQueryClient();
  const ticketId = params.ticketId as string;

  const [message, setMessage] = useState("");

  const { data: detail, isLoading } = useQuery({
    queryKey: ["ticket", ticketId],
    queryFn: () => adminApiFetch<TicketDetail>(`/platform/tickets/${ticketId}`),
    enabled: !!ticketId,
  });

  const ticket = detail?.ticket;
  const messages = detail?.messages ?? [];

  // 发送消息
  const sendMessage = useMutation({
    mutationFn: async (content: string) => {
      return adminApiFetch(`/platform/tickets/${ticketId}/messages`, {
        method: "POST",
        body: JSON.stringify({ content }),
      });
    },
    onSuccess: () => {
      setMessage("");
      queryClient.invalidateQueries({ queryKey: ["ticket", ticketId] });
    },
  });

  // 修改状态
  const updateStatus = useMutation({
    mutationFn: async (status: string) => {
      return adminApiFetch(`/platform/tickets/${ticketId}/status`, {
        method: "POST",
        body: JSON.stringify({ status }),
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["ticket", ticketId] });
    },
  });

  function getStatusBadge(status: string) {
    if (status === "OPEN") return <Badge variant="warning">待处理</Badge>;
    if (status === "IN_PROGRESS") return <Badge variant="info">处理中</Badge>;
    if (status === "RESOLVED") return <Badge variant="success">已解决</Badge>;
    if (status === "CLOSED") return <Badge variant="neutral">已关闭</Badge>;
    return <Badge>{status}</Badge>;
  }

  function getPriorityBadge(priority: string) {
    if (priority === "URGENT") return <Badge variant="danger">紧急</Badge>;
    if (priority === "HIGH") return <Badge variant="warning">高</Badge>;
    if (priority === "NORMAL") return <Badge variant="info">普通</Badge>;
    if (priority === "MEDIUM") return <Badge variant="info">中</Badge>;
    return <Badge variant="neutral">低</Badge>;
  }

  function getCategoryLabel(category: string) {
    const map: Record<string, string> = {
      ACCOUNT: "账号问题",
      BILLING: "账单问题",
      TECHNICAL: "技术问题",
      FEEDBACK: "功能反馈",
      OTHER: "其他",
    };
    return map[category] ?? category;
  }

  if (isLoading) {
    return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;
  }

  if (!ticket) {
    return <div className="p-8 text-center text-sm text-red-500">工单不存在</div>;
  }

  return (
    <div>
      <Link
        href="/tickets"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ArrowLeft className="h-4 w-4" />
        返回工单列表
      </Link>

      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        {/* 左侧：聊天区域 */}
        <div className="flex flex-col rounded-xl border border-slate-200 bg-white shadow-sm">
          {/* 聊天头部 */}
          <div className="border-b border-slate-200 px-6 py-4">
            <h1 className="text-lg font-bold text-slate-800">{ticket.title}</h1>
            <p className="mt-1 text-sm text-slate-500">
              创建者：{ticket.creatorName} | {ticket.createdAt}
            </p>
          </div>

          {/* 消息列表 */}
          <div className="flex-1 space-y-4 overflow-y-auto p-6" style={{ maxHeight: "500px" }}>
            {messages.map((msg: TicketMessage) => (
              <div
                key={msg.id}
                className={`flex ${msg.senderType === "PLATFORM_ADMIN" ? "justify-end" : "justify-start"}`}
              >
                <div
                  className={`max-w-[70%] rounded-2xl px-4 py-3 ${
                    msg.senderType === "PLATFORM_ADMIN"
                      ? "bg-blue-600 text-white"
                      : "bg-slate-100 text-slate-800"
                  }`}
                >
                  <p className="text-xs font-semibold opacity-70">
                    {msg.senderName}
                    {msg.senderType === "PLATFORM_ADMIN" ? " (平台管理员)" : ""}
                  </p>
                  <p className="mt-1 text-sm">{msg.body}</p>
                  <p className="mt-1 text-right text-xs opacity-60">{msg.createdAt}</p>
                </div>
              </div>
            ))}
            {messages.length === 0 && (
              <p className="text-center text-sm text-slate-400">暂无消息</p>
            )}
          </div>

          {/* 消息输入框 */}
          <div className="border-t border-slate-200 p-4">
            <div className="flex gap-3">
              <textarea
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                className="flex-1 resize-none rounded-lg border border-slate-300 px-4 py-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
                rows={2}
                placeholder="输入回复消息…"
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    if (message.trim()) sendMessage.mutate(message.trim());
                  }
                }}
              />
              <Button
                onClick={() => message.trim() && sendMessage.mutate(message.trim())}
                disabled={sendMessage.isPending || !message.trim()}
                className="self-end"
              >
                <Send className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </div>

          {/* 右侧：工单信息面板 */}
        <div className="space-y-4">
          <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="mb-3 text-base font-bold text-slate-700">工单信息</h2>
            <div className="space-y-3 text-sm">
              <div className="flex justify-between">
                <span className="text-slate-500">状态</span>
                {getStatusBadge(ticket.status)}
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">分类</span>
                <span className="font-medium text-slate-700">
                  {getCategoryLabel(ticket.category)}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">优先级</span>
                {getPriorityBadge(ticket.priority)}
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">工单编号</span>
                <span className="font-mono text-xs text-slate-500">{ticket.ticketNumber}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">提交人</span>
                <span className="font-medium text-slate-700">{ticket.creatorName}</span>
              </div>
              {ticket.companyName && (
                <div className="flex justify-between">
                  <span className="text-slate-500">所属企业</span>
                  <span className="font-medium text-slate-700">🏢 {ticket.companyName}</span>
                </div>
              )}
              <div className="flex justify-between">
                <span className="text-slate-500">创建时间</span>
                <span className="text-slate-600">{ticket.createdAt}</span>
              </div>
            </div>
          </div>

          {/* 操作面板 */}
          <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="mb-3 text-base font-bold text-slate-700">工单操作</h2>
            <div className="space-y-2">
              <Button
                size="sm"
                variant="info"
                className="w-full"
                onClick={() => updateStatus.mutate("IN_PROGRESS")}
                disabled={ticket.status === "IN_PROGRESS" || updateStatus.isPending}
              >
                标记为处理中
              </Button>
              <Button
                size="sm"
                variant="confirm"
                className="w-full"
                onClick={() => updateStatus.mutate("RESOLVED")}
                disabled={ticket.status === "RESOLVED" || updateStatus.isPending}
              >
                标记为已解决
              </Button>
              <Button
                size="sm"
                variant="secondary"
                className="w-full"
                onClick={() => updateStatus.mutate("CLOSED")}
                disabled={ticket.status === "CLOSED" || updateStatus.isPending}
              >
                关闭工单
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}