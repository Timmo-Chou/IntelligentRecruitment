// 通用按钮组件，支持 variant 变体
import { Slot } from "@radix-ui/react-slot";
import type { ButtonHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  asChild?: boolean;
  variant?: "primary" | "confirm" | "secondary" | "danger" | "ghost" | "info";
  size?: "sm" | "md" | "lg";
};

export function Button({
  asChild,
  className,
  variant = "primary",
  size = "md",
  ...props
}: ButtonProps) {
  const Component = asChild ? Slot : "button";
  return (
    <Component
      className={cn(
        "inline-flex items-center justify-center gap-2 rounded-lg font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50",
        // 尺寸变体
        size === "sm" && "h-8 px-3 text-xs",
        size === "md" && "h-10 px-4 text-sm",
        size === "lg" && "h-12 px-6 text-base",
        // 颜色变体
        variant === "primary" && "bg-brand text-white hover:bg-blue-700",
        variant === "confirm" && "bg-ai text-white hover:bg-green-700",
        variant === "secondary" && "border border-slate-300 bg-white text-brand hover:bg-blue-50",
        variant === "danger" && "bg-red-600 text-white hover:bg-red-700",
        variant === "ghost" && "text-slate-600 hover:bg-slate-100",
        variant === "info" && "bg-blue-100 text-blue-700 hover:bg-blue-200",
        className,
      )}
      {...props}
    />
  );
}