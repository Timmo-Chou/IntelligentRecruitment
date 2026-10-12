import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch } from "@/lib/api-client";
import { createTalent, fetchCandidates, updateCandidateTags } from "@/lib/candidate-api";

vi.mock("@/lib/api-client", () => ({ apiFetch: vi.fn() }));

describe("candidate requests preserve submitted values", () => {
  beforeEach(() => vi.clearAllMocks());

  it("keeps original status, segment, and source values in filter query parameters", async () => {
    vi.mocked(apiFetch).mockResolvedValue({ items: [], total: 0 } as never);

    await fetchCandidates("tenant-1", {
      status: "ACTIVE",
      segment: "HIGH_MATCH",
      source: "INTERNAL_REFERRAL",
      talentStatus: "IN_POOL",
      page: 2,
    });

    const path = vi.mocked(apiFetch).mock.calls[0]?.[0] as string;
    const query = new URL(path, "http://localhost").searchParams;
    expect(query.get("status")).toBe("ACTIVE");
    expect(query.get("segment")).toBe("HIGH_MATCH");
    expect(query.get("source")).toBe("INTERNAL_REFERRAL");
    expect(query.get("talentStatus")).toBe("IN_POOL");
    expect(query.get("page")).toBe("2");
  });

  it("sends original profile and tag values in create/update payloads", async () => {
    vi.mocked(apiFetch).mockResolvedValue({ id: "candidate-1" } as never);
    const input = {
      fullName: "虚构人才",
      gender: "女",
      phone: "",
      email: "",
      province: "浙江省",
      city: "杭州市",
      district: "滨江区",
      currentCompany: "测试公司",
      currentTitle: "研发工程师",
      currentLevel: "高级",
      yearsExperience: "5-10年",
      industry: "精细化工",
      highestEducation: "硕士",
      school: "测试大学",
      major: "化学工程",
      graduateAt: "2020-06",
      professionalSkills: "工艺设计",
      softwareSkills: "Aspen Plus",
      managementSkills: "项目管理",
      industrySkills: "精细化工",
      tags: ["技术专家"],
      source: "内部推荐",
      jobCategory: "工艺技术类",
    };

    await createTalent("tenant-1", input);
    await updateCandidateTags("tenant-1", "candidate-1", ["技术专家", "自定义标签"]);

    const createCall = vi.mocked(apiFetch).mock.calls[0];
    expect(createCall?.[0]).toBe("/tenants/tenant-1/candidates");
    expect(JSON.parse(String(createCall?.[1]?.body))).toMatchObject({
      fullName: "虚构人才",
      industry: "精细化工",
      highestEducation: "硕士",
      jobCategory: "工艺技术类",
      source: "内部推荐",
      tags: ["技术专家"],
    });

    expect(apiFetch).toHaveBeenNthCalledWith(2, "/tenants/tenant-1/candidates/candidate-1/tags", {
      method: "PATCH",
      body: JSON.stringify({ tags: ["技术专家", "自定义标签"] }),
    });
  });
});
