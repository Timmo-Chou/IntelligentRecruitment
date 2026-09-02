package com.intelligentrecruitment.recruitment.application;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JdDraftGenerator {

    public JdDraftContent generate(GenerationInput input) {
        String title = fallback(input.title(), inferTitle(input.requirement()));
        String location = fallback(input.location(), "工作地点待确认");
        String experience = fallback(input.experienceLevel(), "3年以上相关经验");
        String education = fallback(input.education(), "本科及以上");
        String skills = fallback(input.skills(), inferSkills(input.requirement()));
        String responsibilities = """
                1. 负责%s相关工作的规划、推进与持续优化；
                2. 与产品、研发及业务团队协作，将招聘需求转化为可交付成果；
                3. 建立质量标准并跟踪关键指标，及时识别和解决风险；
                4. 沉淀流程、规范和文档，持续提升团队协作效率。
                """.formatted(title);
        String requirements = """
                1. %s，%s；
                2. 具备与岗位相关的专业能力，能够独立分析并解决复杂问题；
                3. 具备良好的沟通、协作和结构化表达能力；
                4. 对结果负责，能够在变化环境中持续推进工作。
                """.formatted(education, experience);
        String profile = "优先寻找具备“%s”能力组合、业务理解力强且有明确结果案例的人选。".formatted(skills);
        List<String> warnings = input.location() == null || input.location().isBlank()
                ? List.of("工作地点未明确，请确认后再发布", "薪资范围尚未提供")
                : List.of("薪资范围尚未提供");
        return new JdDraftContent(title, fallback(input.companyName(), "企业名称待确认"), location,
                experience, education, fallback(input.jobType(), "全职"), "薪资待确认", responsibilities, requirements,
                skills, "加分项待确认", "福利待遇待确认", profile, warnings);
    }

    private static String inferTitle(String requirement) {
        if (requirement == null || requirement.isBlank()) return "待确认职位";
        String firstLine = requirement.strip().split("[\n，。；;]", 2)[0];
        firstLine = firstLine.replaceFirst("^(我们|公司)?(需要|想要|计划|招聘|招募|寻找)+", "").trim();
        return firstLine.isBlank() ? "待确认职位" : firstLine.substring(0, Math.min(firstLine.length(), 40));
    }

    private static String inferSkills(String requirement) {
        if (requirement == null) return "岗位专业能力、沟通协作、问题解决";
        String lower = requirement.toLowerCase();
        if (lower.contains("java")) return "Java、Spring Boot、MySQL、系统设计、沟通协作";
        if (lower.contains("产品")) return "需求分析、产品设计、数据分析、项目推进、沟通协作";
        if (lower.contains("测试")) return "测试设计、自动化测试、质量保障、问题定位、沟通协作";
        return "岗位专业能力、业务理解、问题解决、沟通协作";
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record GenerationInput(String requirement, String title, String companyName, String location,
                                  String experienceLevel, String education, String jobType, String skills) {
    }

    public record JdDraftContent(String title, String companyName, String location, String experienceLevel,
                                 String education, String jobType, String salaryRange, String responsibilities, String requirements,
                                 String skills, String niceToHaves, String benefits,
                                 String talentProfile, List<String> warnings) {
    }
}
