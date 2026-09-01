/** Shared talent-library constants for filters / forms */

export const TALENT_INDUSTRIES = [
  "石油化工", "精细化工", "基础化工", "新材料", "高分子", "化肥", "农药",
  "涂料", "胶黏剂", "电子化学品", "新能源材料", "医药化工", "化工装备", "环保", "其他",
] as const;

export const TALENT_TAGS = [
  "高潜人才", "技术专家", "工艺优化专家", "化工研发", "项目经理",
  "EHS专家", "设备专家", "质量专家", "管理人才",
] as const;

export const TALENT_SOURCES = [
  "简历上传", "内部推荐", "猎头推荐", "校园招聘", "主动投递",
] as const;

export const EDUCATION_OPTIONS = ["大专", "本科", "硕士", "博士", "其他"] as const;

export const GENDER_OPTIONS = ["男", "女", "其他"] as const;

export const ACTIVITY_OPTIONS = ["高活跃", "中等活跃", "低活跃", "待激活"] as const;

export const TALENT_STATUS_OPTIONS = ["在库", "待激活", "已淘汰", "已入职"] as const;

export const YEARS_OPTIONS = ["应届", "1-3年", "3-5年", "5-10年", "10年以上"] as const;

export const LEVEL_OPTIONS = ["初级", "中级", "高级", "专家", "经理", "总监"] as const;

/** Simplified China region tree: province -> cities (district optional as city itself for municipalities) */
export type RegionNode = { name: string; children?: RegionNode[] };

export const REGION_TREE: RegionNode[] = [
  {
    name: "上海",
    children: [
      { name: "上海市", children: [{ name: "浦东新区" }, { name: "徐汇区" }, { name: "闵行区" }, { name: "静安区" }, { name: "黄浦区" }, { name: "杨浦区" }] },
    ],
  },
  {
    name: "北京",
    children: [
      { name: "北京市", children: [{ name: "海淀区" }, { name: "朝阳区" }, { name: "丰台区" }, { name: "通州区" }] },
    ],
  },
  {
    name: "浙江省",
    children: [
      { name: "杭州市", children: [{ name: "西湖区" }, { name: "滨江区" }, { name: "余杭区" }, { name: "萧山区" }] },
      { name: "宁波市", children: [{ name: "鄞州区" }, { name: "海曙区" }, { name: "北仑区" }] },
      { name: "嘉兴市", children: [{ name: "南湖区" }, { name: "秀洲区" }] },
    ],
  },
  {
    name: "江苏省",
    children: [
      { name: "南京市", children: [{ name: "鼓楼区" }, { name: "江宁区" }, { name: "建邺区" }] },
      { name: "苏州市", children: [{ name: "工业园区" }, { name: "虎丘区" }, { name: "吴中区" }] },
      { name: "无锡市", children: [{ name: "梁溪区" }, { name: "新吴区" }] },
    ],
  },
  {
    name: "广东省",
    children: [
      { name: "深圳市", children: [{ name: "南山区" }, { name: "福田区" }, { name: "宝安区" }] },
      { name: "广州市", children: [{ name: "天河区" }, { name: "黄埔区" }, { name: "番禺区" }] },
    ],
  },
  {
    name: "四川省",
    children: [
      { name: "成都市", children: [{ name: "高新区" }, { name: "武侯区" }, { name: "锦江区" }] },
    ],
  },
];

export type TalentProfileInput = {
  fullName: string;
  gender: string;
  phone: string;
  email: string;
  province: string;
  city: string;
  district: string;
  currentCompany: string;
  currentTitle: string;
  currentLevel: string;
  yearsExperience: string;
  industry: string;
  highestEducation: string;
  school: string;
  major: string;
  graduateAt: string;
  professionalSkills: string;
  softwareSkills: string;
  managementSkills: string;
  industrySkills: string;
  tags: string[];
  source?: string;
  certificates?: string;
  jobCategory?: string;
  age?: string;
};

export function skillsFromProfile(profile: TalentProfileInput): string[] {
  return [
    ...splitSkills(profile.professionalSkills),
    ...splitSkills(profile.softwareSkills),
    ...splitSkills(profile.managementSkills),
    ...splitSkills(profile.industrySkills),
  ];
}

function splitSkills(value: string): string[] {
  return value
    .split(/[,，、;\s]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}
