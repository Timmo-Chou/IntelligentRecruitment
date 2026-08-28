"use client";

import { apiFetch } from "@/lib/api-client";

// AI咨询助手对话消息
export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: number;
  // 可选的快捷操作按钮
  actions?: ChatAction[];
  // 可选的附件（如二维码图片URL、链接等）
  attachments?: ChatAttachment[];
}

export interface ChatAction {
  id: string;
  label: string;
  // 点击后发送的消息或触发的操作
  payload: string;
  variant?: "primary" | "secondary";
}

export interface ChatAttachment {
  type: "link" | "image" | "qr_code";
  url?: string;
  text?: string;
  label?: string;
}

// AI咨询助手会话状态
export interface AIAssistantSession {
  id: string;
  stage: AIAssistantStage;
  context?: Record<string, unknown>;
}

export type AIAssistantStage =
  | "GREETING"          // 初始问候
  | "HELP_MAIN"         // 帮助主菜单
  | "HELP_QA"           // 帮助问答
  | "FEEDBACK_COLLECT"  // 收集反馈内容
  | "FEEDBACK_CONFIRM"  // 确认反馈内容
  | "FEEDBACK_CONTACT"  // 收集联系方式
  | "COOPERATION"       // 合作需求
  | "TICKET_CREATED";   // 工单已创建

// 发送消息到AI咨询助手
export async function sendAssistantMessage(
  message: string,
  sessionId?: string,
  stage?: string,
  context?: Record<string, unknown>
): Promise<{
  reply: string;
  actions?: ChatAction[];
  attachments?: ChatAttachment[];
  nextStage?: AIAssistantStage;
  sessionId: string;
}> {
  const payload = {
    message,
    sessionId: sessionId || null,
    stage: stage || null,
    context: context || {},
  };

  return apiFetch("/ai-assistant/chat", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

// 创建工单（用户反馈提交）
export async function createTicket(params: {
  title: string;
  body: string;
  category: string;
  contactInfo?: string;
}): Promise<{ id: string; ticketNumber: string }> {
  return apiFetch("/me/tickets", {
    method: "POST",
    body: JSON.stringify({
      title: params.title,
      body: params.body,
      category: params.category,
    }),
  });
}

// 获取配置信息（帮助手册链接、合作二维码、电话等）
export async function getAssistantConfig(): Promise<{
  helpManualUrl: string;
  cooperationQrCodeUrl: string;
  cooperationPhone: string;
  cooperationEmail: string;
}> {
  return apiFetch("/ai-assistant/config");
}
