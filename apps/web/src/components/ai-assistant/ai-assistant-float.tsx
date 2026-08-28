"use client";

import { Bot } from "lucide-react";

/**
 * AI咨询助手悬浮按钮
 * 固定在屏幕左下角，点击弹出对话窗口
 */
export function AIAssistantFloat({ onClick, unreadCount = 0 }: { onClick: () => void; unreadCount?: number }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group fixed bottom-6 left-6 z-40 flex h-14 w-14 items-center justify-center rounded-full bg-gradient-to-br from-[#0ca58c] to-[#16a99b] text-white shadow-lg shadow-[#0ca58c]/30 transition-all hover:scale-105 hover:shadow-xl hover:shadow-[#0ca58c]/40 active:scale-95"
      aria-label="AI咨询助手"
      title="AI咨询助手"
    >
      <Bot size={28} strokeWidth={2} />
      {/* 未读消息红点 */}
      {unreadCount > 0 && (
        <span className="absolute -left-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-[10px] font-bold text-white ring-2 ring-white">
          {unreadCount > 99 ? "99+" : unreadCount}
        </span>
      )}
      {/* 脉冲动画提示 */}
      <span className="absolute inset-0 -z-10 animate-ping rounded-full bg-[#0ca58c] opacity-20"></span>
    </button>
  );
}
