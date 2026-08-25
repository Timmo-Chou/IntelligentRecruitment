import { Slot } from "@radix-ui/react-slot";
import type { ButtonHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  asChild?: boolean;
  variant?: "primary" | "confirm" | "secondary";
};

export function Button({ asChild, className, variant = "primary", ...props }: ButtonProps) {
  const Component = asChild ? Slot : "button";
  return (
    <Component
      className={cn(
        "inline-flex h-10 items-center justify-center rounded-lg px-4 text-sm font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50",
        variant === "primary" && "bg-brand text-white hover:bg-blue-700",
        variant === "confirm" && "bg-ai text-white hover:bg-green-700",
        variant === "secondary" && "border border-slate-300 bg-white text-brand hover:bg-blue-50",
        className,
      )}
      {...props}
    />
  );
}

