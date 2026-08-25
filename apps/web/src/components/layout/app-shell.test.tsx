import { render, screen } from "@testing-library/react";
import React from "react";
import { describe, expect, it } from "vitest";
import { AppShell } from "./app-shell";

describe("AppShell", () => {
  it("renders the enterprise recruitment navigation", () => {
    render(<AppShell><div>content</div></AppShell>);
    expect(screen.getByText("AI招聘工作台")).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeInTheDocument();
    expect(screen.getByText("智能招聘")).toBeInTheDocument();
  });
});
