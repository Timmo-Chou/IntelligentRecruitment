export type CategoryNode = {
  id: string;
  name: string;
  code?: string;
  children?: CategoryNode[];
};

function n(id: string, name: string, children?: CategoryNode[]): CategoryNode {
  return children?.length ? { id, name, children } : { id, name };
}

function l1(code: string, id: string, name: string, children: CategoryNode[]): CategoryNode {
  return { id, name, code, children };
}

export const JOB_CATEGORY_TREE: CategoryNode[] = [
  l1("01", "cat-rd", "研发类", [
    n("cat-rd-chem", "化学研发", [
      n("cat-rd-chem-organic", "有机合成"),
      n("cat-rd-chem-inorganic", "无机材料"),
      n("cat-rd-chem-fine", "精细化学品研发"),
      n("cat-rd-chem-catalyst", "催化剂研发"),
      n("cat-rd-chem-analysis", "化学分析研发"),
    ]),
    n("cat-rd-polymer", "高分子研发", [
      n("cat-rd-polymer-poly", "聚合物研发"),
      n("cat-rd-polymer-resin", "树脂研发"),
      n("cat-rd-polymer-rubber", "橡胶研发"),
      n("cat-rd-polymer-plastic", "塑料研发"),
      n("cat-rd-polymer-elastomer", "弹性体研发"),
      n("cat-rd-polymer-modify", "高分子改性研发"),
    ]),
    n("cat-rd-material", "新材料研发", [
      n("cat-rd-material-composite", "复合材料"),
      n("cat-rd-material-func", "功能材料"),
      n("cat-rd-material-nano", "纳米材料"),
      n("cat-rd-material-electronic", "电子化学材料"),
      n("cat-rd-material-energy", "新能源材料"),
      n("cat-rd-material-bio", "生物基材料"),
    ]),
    n("cat-rd-app", "应用研发", [
      n("cat-rd-app-formula", "配方研发"),
      n("cat-rd-app-dev", "应用开发"),
      n("cat-rd-app-product", "产品开发"),
      n("cat-rd-app-customer", "客户应用"),
      n("cat-rd-app-solution", "技术解决方案"),
    ]),
    n("cat-rd-mgmt", "研发管理", [
      n("cat-rd-mgmt-pm", "研发项目管理"),
      n("cat-rd-mgmt-tech", "技术管理"),
      n("cat-rd-mgmt-manager", "研发经理"),
      n("cat-rd-mgmt-lead", "研发负责人"),
    ]),
  ]),
  l1("02", "cat-process", "工艺技术类", [
    n("cat-process-design", "工艺设计", [
      n("cat-process-design-chem", "化工工艺设计"),
      n("cat-process-design-package", "工艺包设计"),
      n("cat-process-design-pfd", "PFD/P&ID设计"),
      n("cat-process-design-reaction", "反应工程"),
      n("cat-process-design-sep", "分离工程"),
    ]),
    n("cat-process-opt", "工艺优化", [
      n("cat-process-opt-process", "工艺优化"),
      n("cat-process-opt-capacity", "产能提升"),
      n("cat-process-opt-cost", "降本增效"),
      n("cat-process-opt-energy", "能耗优化"),
      n("cat-process-opt-yield", "良率提升"),
    ]),
    n("cat-process-dev", "工艺开发", [
      n("cat-process-dev-lab", "实验室工艺开发"),
      n("cat-process-dev-pilot", "中试工艺开发"),
      n("cat-process-dev-scale", "放大验证"),
      n("cat-process-dev-industrial", "工业化开发"),
      n("cat-process-dev-transfer", "工艺转移"),
    ]),
    n("cat-process-sim", "过程模拟", [
      n("cat-process-sim-aspen", "Aspen Plus"),
      n("cat-process-sim-hysys", "Aspen HYSYS"),
      n("cat-process-sim-cfd", "CFD"),
      n("cat-process-sim-digital", "数字化工艺模拟"),
    ]),
    n("cat-process-mgmt", "工艺技术管理", [
      n("cat-process-mgmt-manager", "工艺技术经理"),
      n("cat-process-mgmt-expert", "工艺技术专家"),
      n("cat-process-mgmt-lead", "工艺负责人"),
    ]),
  ]),
  l1("03", "cat-prod", "生产运营类", [
    n("cat-prod-mgmt", "生产管理", [
      n("cat-prod-mgmt-supervisor", "生产主管"),
      n("cat-prod-mgmt-manager", "生产经理"),
      n("cat-prod-mgmt-director", "生产总监"),
      n("cat-prod-mgmt-lead", "生产负责人"),
    ]),
    n("cat-prod-plan", "生产计划", [
      n("cat-prod-plan-plan", "生产计划"),
      n("cat-prod-plan-schedule", "排产管理"),
      n("cat-prod-plan-capacity", "产能管理"),
    ]),
    n("cat-prod-site", "现场管理", [
      n("cat-prod-site-workshop", "车间管理"),
      n("cat-prod-site-team", "班组管理"),
      n("cat-prod-site-unit", "装置管理"),
      n("cat-prod-site-ops", "现场运营"),
    ]),
    n("cat-prod-ops", "操作技术", [
      n("cat-prod-ops-chem", "化工操作"),
      n("cat-prod-ops-dcs", "DCS操作"),
      n("cat-prod-ops-control", "中控操作"),
      n("cat-prod-ops-field", "现场操作"),
      n("cat-prod-ops-process", "工艺操作"),
    ]),
    n("cat-prod-improve", "生产改善", [
      n("cat-prod-improve-lean", "精益生产"),
      n("cat-prod-improve-oee", "OEE改善"),
      n("cat-prod-improve-tpm", "TPM"),
      n("cat-prod-improve-ci", "持续改善"),
    ]),
  ]),
  l1("04", "cat-equip", "设备工程类", [
    n("cat-equip-mech", "机械设备", [
      n("cat-equip-mech-chem", "化工设备"),
      n("cat-equip-mech-vessel", "压力容器"),
      n("cat-equip-mech-hx", "换热器"),
      n("cat-equip-mech-tower", "塔器"),
      n("cat-equip-mech-reactor", "反应器"),
      n("cat-equip-mech-pump", "泵/压缩机"),
    ]),
    n("cat-equip-rotating", "动设备", [
      n("cat-equip-rotating-pump", "泵"),
      n("cat-equip-rotating-compressor", "压缩机"),
      n("cat-equip-rotating-fan", "风机"),
      n("cat-equip-rotating-maint", "机械设备维护"),
    ]),
    n("cat-equip-static", "静设备", [
      n("cat-equip-static-vessel", "压力容器"),
      n("cat-equip-static-tank", "储罐"),
      n("cat-equip-static-hx", "换热设备"),
      n("cat-equip-static-tower", "塔器设备"),
    ]),
    n("cat-equip-auto", "仪表自动化", [
      n("cat-equip-auto-dcs", "DCS"),
      n("cat-equip-auto-plc", "PLC"),
      n("cat-equip-auto-sis", "SIS"),
      n("cat-equip-auto-instrument", "仪表"),
      n("cat-equip-auto-control", "自动化控制"),
    ]),
    n("cat-equip-mgmt", "设备管理", [
      n("cat-equip-mgmt-maint", "设备维护"),
      n("cat-equip-mgmt-reliability", "设备可靠性"),
      n("cat-equip-mgmt-overhaul", "设备检修"),
      n("cat-equip-mgmt-mgmt", "设备管理"),
    ]),
  ]),
  l1("05", "cat-quality", "质量管理类", [
    n("cat-quality-mgmt", "质量管理", [
      n("cat-quality-mgmt-qa", "QA"),
      n("cat-quality-mgmt-qc", "QC"),
      n("cat-quality-mgmt-system", "质量体系"),
      n("cat-quality-mgmt-eng", "质量工程"),
    ]),
    n("cat-quality-analysis", "化学分析", [
      n("cat-quality-analysis-gc", "GC"),
      n("cat-quality-analysis-hplc", "HPLC"),
      n("cat-quality-analysis-icp", "ICP"),
      n("cat-quality-analysis-ftir", "FTIR"),
      n("cat-quality-analysis-physchem", "理化分析"),
    ]),
    n("cat-quality-lab", "实验室", [
      n("cat-quality-lab-mgmt", "实验室管理"),
      n("cat-quality-lab-test", "检测"),
      n("cat-quality-lab-instrument", "仪器分析"),
      n("cat-quality-lab-lims", "LIMS"),
    ]),
    n("cat-quality-improve", "质量改进", [
      n("cat-quality-improve-spc", "SPC"),
      n("cat-quality-improve-capa", "CAPA"),
      n("cat-quality-improve-8d", "8D"),
      n("cat-quality-improve-complaint", "客诉质量"),
    ]),
  ]),
  l1("06", "cat-ehs", "EHS类", [
    n("cat-ehs-safety", "安全", [
      n("cat-ehs-safety-eng", "安全工程"),
      n("cat-ehs-safety-process", "工艺安全"),
      n("cat-ehs-safety-hse", "HSE管理"),
      n("cat-ehs-safety-risk", "风险管理"),
      n("cat-ehs-safety-hazop", "HAZOP"),
    ]),
    n("cat-ehs-env", "环保", [
      n("cat-ehs-env-eng", "环保工程"),
      n("cat-ehs-env-waste", "三废处理"),
      n("cat-ehs-env-water", "废水处理"),
      n("cat-ehs-env-gas", "废气处理"),
      n("cat-ehs-env-solid", "固废管理"),
    ]),
    n("cat-ehs-health", "职业健康", [
      n("cat-ehs-health-hygiene", "职业卫生"),
      n("cat-ehs-health-health", "职业健康"),
      n("cat-ehs-health-protect", "劳动保护"),
    ]),
    n("cat-ehs-fire", "消防与应急", [
      n("cat-ehs-fire-fire", "消防管理"),
      n("cat-ehs-fire-emergency", "应急管理"),
      n("cat-ehs-fire-hazmat", "危化品管理"),
    ]),
  ]),
  l1("07", "cat-supply", "供应链类", [
    n("cat-supply-purchase", "采购", [
      n("cat-supply-purchase-chem", "化工原料采购"),
      n("cat-supply-purchase-equip", "设备采购"),
      n("cat-supply-purchase-pack", "包材采购"),
      n("cat-supply-purchase-mro", "MRO采购"),
    ]),
    n("cat-supply-vendor", "供应商管理", [
      n("cat-supply-vendor-dev", "供应商开发"),
      n("cat-supply-vendor-quality", "供应商质量"),
      n("cat-supply-vendor-mgmt", "供应商管理"),
    ]),
    n("cat-supply-plan", "计划", [
      n("cat-supply-plan-material", "物料计划"),
      n("cat-supply-plan-mrp", "MRP"),
      n("cat-supply-plan-scm", "供应链计划"),
    ]),
    n("cat-supply-logistics", "仓储物流", [
      n("cat-supply-logistics-warehouse", "仓储管理"),
      n("cat-supply-logistics-hazmat", "危化品物流"),
      n("cat-supply-logistics-transport", "运输管理"),
      n("cat-supply-logistics-inventory", "库存管理"),
    ]),
  ]),
  l1("08", "cat-sales", "市场销售类", [
    n("cat-sales-chem", "化工销售", [
      n("cat-sales-chem-raw", "化工原料销售"),
      n("cat-sales-chem-fine", "精细化工销售"),
      n("cat-sales-chem-polymer", "高分子材料销售"),
      n("cat-sales-chem-material", "新材料销售"),
    ]),
    n("cat-sales-tech", "技术销售", [
      n("cat-sales-tech-sales", "技术型销售"),
      n("cat-sales-tech-ae", "应用工程师"),
      n("cat-sales-tech-presales", "售前技术"),
      n("cat-sales-tech-support", "技术支持"),
    ]),
    n("cat-sales-market", "市场", [
      n("cat-sales-market-analysis", "市场分析"),
      n("cat-sales-market-pm", "产品经理"),
      n("cat-sales-market-dev", "市场开发"),
      n("cat-sales-market-research", "行业研究"),
    ]),
    n("cat-sales-customer", "客户管理", [
      n("cat-sales-customer-key", "大客户管理"),
      n("cat-sales-customer-success", "客户成功"),
      n("cat-sales-customer-tech", "客户技术服务"),
    ]),
  ]),
  l1("09", "cat-project", "工程项目类", [
    n("cat-project-design", "工程设计", [
      n("cat-project-design-chem", "化工工程设计"),
      n("cat-project-design-pipe", "管道设计"),
      n("cat-project-design-arch", "建筑设计"),
      n("cat-project-design-elec", "电气设计"),
      n("cat-project-design-instrument", "仪表设计"),
    ]),
    n("cat-project-build", "工程建设", [
      n("cat-project-build-eng", "项目工程"),
      n("cat-project-build-construction", "施工管理"),
      n("cat-project-build-install", "安装工程"),
      n("cat-project-build-commission", "调试"),
    ]),
    n("cat-project-pm", "项目管理", [
      n("cat-project-pm-manager", "项目经理"),
      n("cat-project-pm-pmo", "PMO"),
      n("cat-project-pm-plan", "项目计划"),
      n("cat-project-pm-control", "项目控制"),
    ]),
    n("cat-project-epc", "EPC", [
      n("cat-project-epc-project", "EPC项目"),
      n("cat-project-epc-purchase", "工程采购"),
      n("cat-project-epc-build", "工程施工"),
    ]),
  ]),
  l1("10", "cat-digital", "数字化与智能制造类", [
    n("cat-digital-industrial", "工业数字化", [
      n("cat-digital-industrial-mes", "MES"),
      n("cat-digital-industrial-erp", "ERP"),
      n("cat-digital-industrial-lims", "LIMS"),
      n("cat-digital-industrial-iot", "工业互联网"),
    ]),
    n("cat-digital-smart", "智能制造", [
      n("cat-digital-smart-factory", "智能工厂"),
      n("cat-digital-smart-auto", "自动化"),
      n("cat-digital-smart-twin", "数字孪生"),
      n("cat-digital-smart-control", "智能控制"),
    ]),
    n("cat-digital-data", "数据分析", [
      n("cat-digital-data-industrial", "工业数据分析"),
      n("cat-digital-data-prod", "生产数据分析"),
      n("cat-digital-data-bi", "BI"),
    ]),
    n("cat-digital-ai", "AI应用", [
      n("cat-digital-ai-rd", "AI研发"),
      n("cat-digital-ai-process", "AI工艺优化"),
      n("cat-digital-ai-quality", "AI质量分析"),
      n("cat-digital-ai-predict", "AI预测维护"),
    ]),
  ]),
  l1("11", "cat-tech-mgmt", "技术管理类", [
    n("cat-tech-mgmt-mgmt", "技术管理"),
    n("cat-tech-mgmt-plan", "技术规划"),
    n("cat-tech-mgmt-standard", "技术标准"),
    n("cat-tech-mgmt-ip", "知识产权"),
    n("cat-tech-mgmt-patent", "专利管理"),
    n("cat-tech-mgmt-strategy", "技术战略"),
  ]),
  l1("12", "cat-support", "职能支持类", [
    n("cat-support-hr", "人力资源"),
    n("cat-support-finance", "财务"),
    n("cat-support-legal", "法务"),
    n("cat-support-admin", "行政"),
    n("cat-support-it", "IT"),
    n("cat-support-enterprise", "企业管理"),
    n("cat-support-general", "综合管理"),
  ]),
];

export function collectLeafIds(node: CategoryNode): string[] {
  if (!node.children?.length) return [node.id];
  return node.children.flatMap(collectLeafIds);
}

export function findCategoryPath(leafId: string, tree: CategoryNode[] = JOB_CATEGORY_TREE): string[] | null {
  for (const node of tree) {
    if (node.id === leafId) return [node.name];
    if (node.children?.length) {
      const childPath = findCategoryPath(leafId, node.children);
      if (childPath) return [node.name, ...childPath];
    }
  }
  return null;
}

export function formatCategorySelectionSummary(selectedIds: string[]): string {
  if (selectedIds.length === 0) return "全部分类";
  if (selectedIds.length === 1) {
    const path = findCategoryPath(selectedIds[0]);
    return path ? path.join(" / ") : "全部分类";
  }
  return selectedIds
    .map((id) => findCategoryPath(id))
    .filter((path): path is string[] => Boolean(path))
    .map((path) => path.join(" / "))
    .join("、");
}

export function getCategoryButtonLabel(selectedIds: string[]): string {
  if (selectedIds.length === 0) return "职位分类";
  if (selectedIds.length === 1) {
    const path = findCategoryPath(selectedIds[0]);
    return path ? path.join(" / ") : "职位分类";
  }
  return `已选 ${selectedIds.length} 项`;
}

export function filterCategoryTree(tree: CategoryNode[], query: string): CategoryNode[] {
  const q = query.trim().toLowerCase();
  if (!q) return tree;

  return tree.flatMap((node) => {
    const selfMatch =
      node.name.toLowerCase().includes(q) ||
      Boolean(node.code?.includes(q));

    if (!node.children?.length) {
      return selfMatch ? [node] : [];
    }

    const filteredChildren = filterCategoryTree(node.children, query);
    if (selfMatch) return [node];
    if (filteredChildren.length) return [{ ...node, children: filteredChildren }];
    return [];
  });
}

/** Expand ancestors of matches; if a branch name itself matches, expand only one level. */
export function collectExpandedIdsForSearch(tree: CategoryNode[], query: string): Set<string> {
  const q = query.trim().toLowerCase();
  const ids = new Set<string>();

  const matches = (node: CategoryNode) =>
    node.name.toLowerCase().includes(q) || Boolean(node.code?.includes(q));

  const walk = (nodes: CategoryNode[]): boolean => {
    let any = false;
    for (const node of nodes) {
      const self = matches(node);
      const childHit = node.children?.length ? walk(node.children) : false;
      if (childHit) {
        ids.add(node.id);
        any = true;
      } else if (self && node.children?.length) {
        ids.add(node.id);
        any = true;
      } else if (self) {
        any = true;
      }
    }
    return any;
  };

  walk(tree);
  return ids;
}
