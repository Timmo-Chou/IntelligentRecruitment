"use client";

import { ChevronDown, ChevronRight, Search } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  JOB_CATEGORY_TREE,
  collectExpandedIdsForSearch,
  collectLeafIds,
  filterCategoryTree,
  type CategoryNode,
} from "@/lib/job-categories";

type SelectionMode = "multi" | "single";

type CategoryTreePanelProps = {
  mode?: SelectionMode;
  value: string[];
  onChange: (next: string[]) => void;
  tree?: CategoryNode[];
  showSearch?: boolean;
  showSelectAll?: boolean;
  maxHeightClass?: string;
};

export function CategoryTreePanel({
  mode = "multi",
  value,
  onChange,
  tree = JOB_CATEGORY_TREE,
  showSearch = true,
  showSelectAll = mode === "multi",
  maxHeightClass = "max-h-72",
}: CategoryTreePanelProps) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [search, setSearch] = useState("");
  const filteredTree = useMemo(() => filterCategoryTree(tree, search), [tree, search]);
  const searchActive = search.trim().length > 0;

  useEffect(() => {
    if (!searchActive) return;
    setExpanded(collectExpandedIdsForSearch(filteredTree, search));
  }, [filteredTree, search, searchActive]);

  const toggleExpand = (id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleLeaf = (leafId: string) => {
    if (mode === "single") {
      onChange(value.includes(leafId) ? [] : [leafId]);
      return;
    }
    onChange(value.includes(leafId) ? value.filter((id) => id !== leafId) : [...value, leafId]);
  };

  const toggleBranch = (node: CategoryNode) => {
    const leafIds = collectLeafIds(node);
    if (mode === "single") {
      onChange(value.some((id) => leafIds.includes(id) || id === node.id) ? [] : [node.id]);
      return;
    }
    const allSelected = leafIds.every((id) => value.includes(id));
    if (allSelected) onChange(value.filter((id) => !leafIds.includes(id)));
    else onChange(Array.from(new Set([...value, ...leafIds])));
  };

  const toggleNode = (node: CategoryNode) => {
    if (node.children?.length) toggleBranch(node);
    else toggleLeaf(node.id);
  };

  const nodeState = (node: CategoryNode): "all" | "some" | "none" => {
    if (mode === "single") {
      if (value.includes(node.id)) return "all";
      if (node.children?.length) {
        const leafIds = collectLeafIds(node);
        if (leafIds.some((id) => value.includes(id))) return "some";
      }
      return "none";
    }
    if (!node.children?.length) return value.includes(node.id) ? "all" : "none";
    const leafIds = collectLeafIds(node);
    const selectedCount = leafIds.filter((id) => value.includes(id)).length;
    if (selectedCount === 0) return "none";
    if (selectedCount === leafIds.length) return "all";
    return "some";
  };

  const renderNodes = (nodes: CategoryNode[], depth = 0): ReactNode =>
    nodes.map((node) => {
      const hasChildren = Boolean(node.children?.length);
      const isExpanded = expanded.has(node.id);
      const state = nodeState(node);

      return (
        <div key={node.id}>
          <div className="flex items-center pr-2" style={{ paddingLeft: 8 + depth * 14 }}>
            {hasChildren ? (
              <button
                type="button"
                className="mr-1 grid h-6 w-6 shrink-0 place-items-center text-[#36527f] hover:text-[#0874e8]"
                onClick={() => toggleExpand(node.id)}
                aria-label={isExpanded ? "收起" : "展开"}
              >
                {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              </button>
            ) : (
              <span className="mr-1 grid h-6 w-6 shrink-0" aria-hidden />
            )}

            <input
              type="checkbox"
              className="mr-2 h-4 w-4 shrink-0 rounded border-[#c5d4e8] accent-[#0874e8]"
              checked={state === "all"}
              ref={(el) => {
                if (el) el.indeterminate = state === "some";
              }}
              onChange={() => toggleNode(node)}
              aria-label={node.name}
            />

            <button
              type="button"
              className="min-w-0 flex-1 truncate py-1.5 text-left text-[13px] font-semibold text-[#36527f] hover:text-[#0874e8]"
              onClick={() => toggleNode(node)}
            >
              {node.name}
            </button>
          </div>
          {hasChildren && isExpanded && renderNodes(node.children!, depth + 1)}
        </div>
      );
    });

  return (
    <div>
      {showSearch && (
        <div className="border-b border-[#eaf1fa] px-3 py-2.5">
          <label className="flex items-center gap-2 rounded border border-[#bdd3ef] bg-white px-2.5 py-1.5 text-[#36527f]">
            <Search size={14} className="shrink-0 text-[#8fa3c0]" />
            <input
              className="min-w-0 flex-1 border-0 bg-transparent text-[13px] font-semibold text-[#36527f] outline-none placeholder:font-normal placeholder:text-[#8fa3c0]"
              placeholder="搜索职位分类"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>
        </div>
      )}

      {showSelectAll && (
        <label className="flex cursor-pointer items-center gap-2 border-b border-[#eaf1fa] px-3 py-2.5 text-[13px] font-semibold text-[#36527f] hover:bg-[#f5f9ff]">
          <input
            type="checkbox"
            className="h-4 w-4 rounded border-[#c5d4e8] accent-[#0874e8]"
            checked={value.length === 0}
            onChange={() => onChange([])}
          />
          全部分类
        </label>
      )}

      <div className={`overflow-auto py-1 ${maxHeightClass}`}>
        {filteredTree.length === 0 ? (
          <p className="px-3 py-4 text-center text-[13px] text-[#8fa3c0]">未找到匹配分类</p>
        ) : (
          renderNodes(filteredTree)
        )}
      </div>
    </div>
  );
}
