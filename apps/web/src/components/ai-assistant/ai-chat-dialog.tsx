"use client";

import { Bot, Send, X, Phone, Mail } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import {
  sendAssistantMessage,
  createTicket,
  getAssistantConfig,
  type ChatMessage,
  type ChatAction,
  type ChatAttachment,
  type AIAssistantStage,
} from "@/lib/ai-assistant-api";

/**
 * AI咨询助手对话弹窗
 * 支持三种场景：帮助、反馈问题、合作需求
 */
export function AIChatDialog({ onClose }: { onClose: () => void }) {
  // 对话消息列表
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  // 用户输入框内容
  const [input, setInput] = useState("");
  // AI是否正在回复
  const [loading, setLoading] = useState(false);
  // 会话ID
  const [sessionId, setSessionId] = useState<string | null>(null);
  // 当前对话阶段
  const [stage, setStage] = useState<AIAssistantStage>("GREETING");
  // 上下文信息（如反馈内容、联系方式等）
  const [context, setContext] = useState<Record<string, unknown>>({});
  // 配置信息
  const [config, setConfig] = useState<{
    helpManualUrl: string;
    cooperationQrCodeUrl: string;
    cooperationPhone: string;
    cooperationEmail: string;
  } | null>(null);
  // 工单提交状态
  const [ticketSubmitting, setTicketSubmitting] = useState(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // 自动滚动到最新消息
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  // 进入弹窗时初始化：加载配置并发送问候
  useEffect(() => {
    initChat();
    // 聚焦输入框
    setTimeout(() => inputRef.current?.focus(), 100);
    // 键盘ESC关闭
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);

  /**
   * 初始化对话：获取配置并发送问候语
   */
  const initChat = async () => {
    setLoading(true);
    try {
      // 并行获取配置和发送初始问候
      const configData = await getAssistantConfig().catch(() => null);
      if (configData) setConfig(configData);

      // 发送初始问候
      const response = await sendAssistantMessage("__GREETING__");
      setSessionId(response.sessionId);
      if (response.nextStage) setStage(response.nextStage);

      const greetingMsg: ChatMessage = {
        id: response.sessionId,
        role: "assistant",
        content: response.reply,
        timestamp: Date.now(),
        actions: response.actions,
        attachments: response.attachments,
      };
      setMessages([greetingMsg]);
    } catch (error) {
      // 出错时使用默认问候
      const defaultMsg: ChatMessage = {
        id: "fallback-" + Date.now(),
        role: "assistant",
        content: "你好！我是AI咨询助手，很高兴为你服务。请选择你需要的帮助类型：",
        timestamp: Date.now(),
        actions: [
          { id: "help", label: "我需要帮助", payload: "我需要帮助", variant: "primary" },
          { id: "feedback", label: "我想要反馈问题", payload: "我想要反馈问题" },
          { id: "cooperation", label: "我有合作需求", payload: "我有合作需求" },
        ],
      };
      setMessages([defaultMsg]);
    } finally {
      setLoading(false);
    }
  };

  /**
   * 发送消息
   */
  const sendMessage = async (text?: string, actionPayload?: string) => {
    const content = (text ?? actionPayload ?? input).trim();
    if (!content || loading) return;

    const userMsg: ChatMessage = {
      id: "u-" + Date.now(),
      role: "user",
      content,
      timestamp: Date.now(),
    };
    setMessages((prev) => [...prev, userMsg]);
    setInput("");
    setLoading(true);

    try {
      // 特殊处理：反馈提交流程
      if (stage === "FEEDBACK_CONFIRM" && actionPayload === "确认提交") {
        await submitFeedbackTicket(content);
        return;
      }

      // 特殊处理：合作需求提交
      if (stage === "COOPERATION" && actionPayload === "提交合作需求") {
        await handleCooperationSubmit(content);
        return;
      }

      // 普通对话
      const response = await sendAssistantMessage(content, sessionId ?? undefined, stage, context);
      setSessionId(response.sessionId);
      if (response.nextStage) setStage(response.nextStage);

      // 更新上下文
      const newCtx = { ...context };
      // 根据阶段保存用户输入
      if (stage === "HELP_QA") {
        newCtx.lastHelpQuestion = content;
      } else if (stage === "FEEDBACK_COLLECT") {
        newCtx.feedbackContent = content;
      } else if (stage === "FEEDBACK_CONTACT") {
        newCtx.contactInfo = content;
      } else if (stage === "COOPERATION") {
        newCtx.cooperationInfo = content;
      }
      setContext(newCtx);

      const assistantMsg: ChatMessage = {
        id: "a-" + Date.now(),
        role: "assistant",
        content: response.reply,
        timestamp: Date.now(),
        actions: response.actions,
        attachments: response.attachments,
      };
      setMessages((prev) => [...prev, assistantMsg]);
    } catch (error) {
      const errorMsg: ChatMessage = {
        id: "err-" + Date.now(),
        role: "assistant",
        content: "抱歉，AI助手暂时无法回复，请稍后再试。",
        timestamp: Date.now(),
      };
      setMessages((prev) => [...prev, errorMsg]);
    } finally {
      setLoading(false);
    }
  };

  /**
   * 提交反馈工单
   */
  const submitFeedbackTicket = async (_confirmText: string) => {
    setTicketSubmitting(true);
    try {
      const feedbackContent = (context.feedbackContent as string) || "用户反馈";
      const contactInfo = (context.contactInfo as string) || "";

      const ticket = await createTicket({
        title: feedbackContent.slice(0, 50) || "用户反馈",
        body: `${feedbackContent}${contactInfo ? "\n\n联系方式：" + contactInfo : ""}`,
        category: "FEEDBACK",
      });

      const successMsg: ChatMessage = {
        id: "success-" + Date.now(),
        role: "assistant",
        content: `✅ 您的反馈已成功提交（工单编号：${ticket.ticketNumber}）。\n我们会尽快处理您的问题，请耐心等待。如需补充信息，可以随时回复此对话。`,
        timestamp: Date.now(),
        actions: [
          { id: "new_help", label: "还有其他问题", payload: "我需要帮助", variant: "primary" },
          { id: "close", label: "结束对话", payload: "__CLOSE__" },
        ],
      };
      setMessages((prev) => [...prev, successMsg]);
      setStage("TICKET_CREATED");
      setContext({});
    } catch (error) {
      const errMsg: ChatMessage = {
        id: "err-" + Date.now(),
        role: "assistant",
        content: "抱歉，提交反馈时出现错误，请稍后重试或直接联系客服。",
        timestamp: Date.now(),
      };
      setMessages((prev) => [...prev, errMsg]);
    } finally {
      setTicketSubmitting(false);
    }
  };

  /**
   * 提交合作需求
   */
  const handleCooperationSubmit = async (_submitText: string) => {
    setTicketSubmitting(true);
    try {
      const cooperationInfo = (context.cooperationInfo as string) || "合作咨询";

      // 创建合作工单
      const ticket = await createTicket({
        title: cooperationInfo.slice(0, 50) || "合作咨询",
        body: cooperationInfo,
        category: "COOPERATION",
      });

      const successMsg: ChatMessage = {
        id: "coop-success-" + Date.now(),
        role: "assistant",
        content: `✅ 您的合作需求已提交（工单编号：${ticket.ticketNumber}）。\n我们的商务团队会尽快与您联系。\n\n如需紧急沟通，请通过以下方式联系我们：`,
        timestamp: Date.now(),
        attachments: buildCooperationAttachments(),
        actions: [
          { id: "new_help", label: "继续咨询其他问题", payload: "我需要帮助", variant: "primary" },
          { id: "close", label: "结束对话", payload: "__CLOSE__" },
        ],
      };
      setMessages((prev) => [...prev, successMsg]);
      setStage("TICKET_CREATED");
      setContext({});
    } catch (error) {
      const errMsg: ChatMessage = {
        id: "err-" + Date.now(),
        role: "assistant",
        content: "抱歉，提交合作需求时出现错误，请直接通过电话或邮件联系我们。",
        timestamp: Date.now(),
        attachments: buildCooperationAttachments(),
      };
      setMessages((prev) => [...prev, errMsg]);
    } finally {
      setTicketSubmitting(false);
    }
  };

  /**
   * 构建合作相关附件
   */
  const buildCooperationAttachments = (): ChatAttachment[] => {
    const attachments: ChatAttachment[] = [];
    if (config?.cooperationPhone) {
      attachments.push({ type: "link", text: config.cooperationPhone, label: "联系电话" });
    }
    if (config?.cooperationEmail) {
      attachments.push({ type: "link", text: config.cooperationEmail, label: "邮箱" });
    }
    if (config?.cooperationQrCodeUrl) {
      attachments.push({ type: "qr_code", url: config.cooperationQrCodeUrl, label: "扫码联系" });
    }
    return attachments;
  };

  /**
   * 点击快捷操作按钮
   */
  const handleActionClick = (action: ChatAction) => {
    if (action.payload === "__CLOSE__") {
      onClose();
      return;
    }

    // 根据按钮payload判断跳转阶段
    const payload = action.payload;
    let nextStage: AIAssistantStage | null = null;
    if (payload === "我需要帮助") nextStage = "HELP_MAIN";
    else if (payload === "我想要反馈问题") nextStage = "FEEDBACK_COLLECT";
    else if (payload === "我有合作需求") nextStage = "COOPERATION";
    else if (payload === "确认提交") nextStage = "FEEDBACK_CONFIRM";

    if (nextStage) setStage(nextStage);
    void sendMessage(action.payload);
  };

  /**
   * 渲染附件
   */
  const renderAttachment = (attachment: ChatAttachment, index: number) => {
    if (attachment.type === "qr_code" && attachment.url) {
      return (
        <div key={index} className="mt-2 flex flex-col items-center gap-1 rounded-lg bg-white p-3 shadow-sm">
          <img
            src={attachment.url}
            alt={attachment.label || "二维码"}
            className="h-32 w-32 object-contain"
          />
          <span className="text-xs text-[#7185a1]">{attachment.label}</span>
        </div>
      );
    }
    if (attachment.type === "link" && attachment.text) {
      const isPhone = attachment.text?.match(/^[\d\s-]+$/);
      return (
        <div key={index} className="mt-2 flex items-center gap-2 rounded-lg bg-white p-2 text-sm shadow-sm">
          {isPhone ? <Phone size={16} className="text-[#0ca58c]" /> : <Mail size={16} className="text-[#0ca58c]" />}
          <span className="font-medium text-[#10285b]">{attachment.label}：</span>
          <span className="text-[#27477f]">{attachment.text}</span>
        </div>
      );
    }
    return null;
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-start p-4 sm:items-end sm:justify-start">
      {/* 背景遮罩 */}
      <div
        className="absolute inset-0 bg-[#102d64]/20 backdrop-blur-[2px]"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* 对话窗口 */}
      <div className="relative flex h-[600px] w-[380px] max-h-[85vh] flex-col overflow-hidden rounded-2xl bg-white shadow-2xl sm:w-[420px]">
        {/* 头部 */}
        <div className="flex items-center justify-between border-b border-[#e5edf6] bg-gradient-to-r from-[#0ca58c] to-[#16a99b] px-4 py-3">
          <div className="flex items-center gap-2 text-white">
            <Bot size={20} />
            <span className="font-semibold">AI咨询助手</span>
            <span className="ml-2 rounded-full bg-white/20 px-2 py-0.5 text-[10px]">在线</span>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-full p-1 text-white/80 hover:bg-white/20 hover:text-white"
            aria-label="关闭"
          >
            <X size={18} />
          </button>
        </div>

        {/* 消息区域 */}
        <div className="flex-1 space-y-4 overflow-y-auto bg-[#f7fbff] p-4">
          {messages.map((msg) => (
            <div
              key={msg.id}
              className={`flex gap-2 ${msg.role === "user" ? "flex-row-reverse" : ""}`}
            >
              {/* 头像 */}
              <div
                className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${
                  msg.role === "user"
                    ? "bg-gradient-to-br from-[#dff7f4] to-[#b7d5ff] text-[#0d57aa]"
                    : "bg-gradient-to-br from-[#0ca58c] to-[#16a99b] text-white"
                }`}
              >
                {msg.role === "user" ? <span className="text-xs">我</span> : <Bot size={16} />}
              </div>

              {/* 消息内容 */}
              <div className={`flex max-w-[80%] flex-col gap-2 ${msg.role === "user" ? "items-end" : "items-start"}`}>
                <div
                  className={`rounded-2xl px-4 py-2.5 text-sm leading-relaxed whitespace-pre-wrap ${
                    msg.role === "user"
                      ? "rounded-br-sm bg-[#0ca58c] text-white"
                      : "rounded-bl-sm bg-white text-[#10285b] shadow-sm"
                  }`}
                >
                  {msg.content}
                </div>

                {/* 附件 */}
                {msg.attachments?.map((att, idx) => renderAttachment(att, idx))}

                {/* 快捷操作按钮 */}
                {msg.actions && msg.actions.length > 0 && (
                  <div className="mt-1 flex flex-wrap gap-2">
                    {msg.actions.map((action) => (
                      <button
                        key={action.id}
                        type="button"
                        onClick={() => handleActionClick(action)}
                        disabled={loading || ticketSubmitting}
                        className={`rounded-full px-3 py-1.5 text-xs font-medium transition ${
                          action.variant === "primary"
                            ? "bg-[#0ca58c] text-white hover:bg-[#0b937d]"
                            : "border border-[#d7e3ee] bg-white text-[#496587] hover:border-[#0ca58c] hover:text-[#0ca58c]"
                        } disabled:opacity-50`}
                      >
                        {action.label}
                      </button>
                    ))}
                  </div>
                )}

                {/* 时间戳 */}
                <span className="text-[10px] text-[#94a3b8]">
                  {new Date(msg.timestamp).toLocaleTimeString("zh-CN", {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </span>
              </div>
            </div>
          ))}

          {/* 加载中指示器 */}
          {(loading || ticketSubmitting) && (
            <div className="flex gap-2">
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-[#0ca58c] to-[#16a99b] text-white">
                <Bot size={16} />
              </div>
              <div className="flex items-center gap-1 rounded-2xl rounded-bl-sm bg-white px-4 py-3 shadow-sm">
                <span className="h-2 w-2 animate-bounce rounded-full bg-[#0ca58c]" style={{ animationDelay: "0ms" }} />
                <span className="h-2 w-2 animate-bounce rounded-full bg-[#0ca58c]" style={{ animationDelay: "150ms" }} />
                <span className="h-2 w-2 animate-bounce rounded-full bg-[#0ca58c]" style={{ animationDelay: "300ms" }} />
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* 输入区域 */}
        <div className="border-t border-[#e5edf6] bg-white p-3">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void sendMessage();
            }}
            className="flex items-center gap-2"
          >
            <input
              ref={inputRef}
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder={
                loading
                  ? "AI正在思考中..."
                  : stage === "HELP_QA"
                    ? "请描述你遇到的问题..."
                    : stage === "FEEDBACK_COLLECT"
                      ? "请描述你遇到的问题..."
                      : stage === "FEEDBACK_CONTACT"
                        ? "请留下你的联系方式（手机号或邮箱）..."
                        : stage === "COOPERATION"
                          ? "请描述你的合作需求和联系方式..."
                          : "请输入你的问题..."
              }
              disabled={loading || ticketSubmitting}
              className="flex-1 rounded-xl border border-[#d7e3ee] bg-[#f7fbff] px-4 py-2.5 text-sm outline-none transition focus:border-[#0ca58c] focus:ring-2 focus:ring-[#0ca58c]/20 disabled:opacity-50"
            />
            <button
              type="submit"
              disabled={loading || ticketSubmitting || !input.trim()}
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[#0ca58c] text-white transition hover:bg-[#0b937d] disabled:opacity-50"
              aria-label="发送"
            >
              <Send size={18} />
            </button>
          </form>
          {/* 底部提示 */}
          <p className="mt-2 text-center text-[10px] text-[#94a3b8]">
            AI生成内容仅供参考，请核实重要信息
          </p>
        </div>
      </div>
    </div>
  );
}
