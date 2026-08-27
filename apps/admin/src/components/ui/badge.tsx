// 状态标签组件，用于显示各类状态信息
import { type HTMLAttributes } from "react";
import { cn } from "@/lib/cn";

type BadgeProps = HTMLAttributes<HTMLSpanElement> & {
  variant?: "default" | "success" | "warning" | "danger" | "info" | "neutral";
};

const variantStyles: Record<NonNullable<BadgeProps["variant"]>, string> = {
  default: "bg-slate-100 text-slate-700",
  success: "bg-green-100 text-green-700",
  warning: "bg-amber-100 text-amber-700",
  danger: "bg-red-100 text-red-700",
  info: "bg-blue-100 text-blue-700",
  neutral: "bg-gray-100 text-gray-600",
};

export function Badge({ className, variant = "default", ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold",
        variantStyles[variant],
        className,
      )}
      {...props}
    />
  );
}