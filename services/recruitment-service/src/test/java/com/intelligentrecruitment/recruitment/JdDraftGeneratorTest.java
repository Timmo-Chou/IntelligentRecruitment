package com.intelligentrecruitment.recruitment;

import com.intelligentrecruitment.recruitment.application.JdDraftGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdDraftGeneratorTest {

    private final JdDraftGenerator generator = new JdDraftGenerator();

    @Test
    void generatesDeterministicJavaDraftAndMarksMissingFields() {
        var draft = generator.generate(new JdDraftGenerator.GenerationInput(
                "招聘高级 Java 开发工程师，熟悉 Spring Boot 和 MySQL",
                "高级 Java 开发工程师", "示例科技", null, null, null, null, null));

        assertThat(draft.title()).isEqualTo("高级 Java 开发工程师");
        assertThat(draft.skills()).contains("Java", "Spring Boot", "MySQL");
        assertThat(draft.location()).isEqualTo("工作地点待确认");
        assertThat(draft.warnings()).containsExactly("工作地点未明确，请确认后再发布", "薪资范围尚未提供");
    }

    @Test
    void preservesExplicitStructuredFields() {
        var draft = generator.generate(new JdDraftGenerator.GenerationInput(
                "负责企业招聘产品设计", "招聘产品经理", "示例科技", "上海",
                "5年以上", "本科", "全职", "需求分析、数据分析"));

        assertThat(draft.location()).isEqualTo("上海");
        assertThat(draft.experienceLevel()).isEqualTo("5年以上");
        assertThat(draft.skills()).isEqualTo("需求分析、数据分析");
        assertThat(draft.warnings()).containsExactly("薪资范围尚未提供");
    }
}
