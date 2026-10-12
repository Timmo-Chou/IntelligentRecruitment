import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CategoryTreePanel } from "./category-tree-panel";

describe("CategoryTreePanel", () => {
  it("shows a Chinese professional label while returning the unchanged category id", () => {
    const onChange = vi.fn();
    render(<CategoryTreePanel mode="single" tree={[{ id: "cat-equip-auto-plc", name: "PLC" }]} value={[]} onChange={onChange} showSearch={false} />);

    fireEvent.click(screen.getByRole("button", { name: "可编程逻辑控制器（PLC）" }));

    expect(onChange).toHaveBeenCalledWith(["cat-equip-auto-plc"]);
  });
});
