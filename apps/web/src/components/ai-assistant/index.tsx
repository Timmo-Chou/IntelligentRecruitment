"use client";

import { useState } from "react";
import { AIAssistantFloat } from "./ai-assistant-float";
import { AIChatDialog } from "./ai-chat-dialog";

/**
 * AI咨询助手组件（悬浮按钮 + 对话弹窗）
 * 在全局布局中使用，提供AI咨询服务入口
 */
export function AIAssistant() {
  const [open, setOpen] = useState(false);

  return (
    <>
      {/* 悬浮按钮 */}
      {!open && (
        <AIAssistantFloat onClick={() => setOpen(true)} unreadCount={0} />
      )}
      {/* 对话弹窗 */}
      {open && <AIChatDialog onClose={() => setOpen(false)} />}
    </>
  );
}
