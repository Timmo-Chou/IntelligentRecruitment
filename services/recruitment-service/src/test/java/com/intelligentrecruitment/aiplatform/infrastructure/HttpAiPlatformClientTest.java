package com.intelligentrecruitment.aiplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpAiPlatformClientTest {

    @Test
    void directJdInputUsesTheLockedRdSlotAndCustomFieldShapes() {
        var input = HttpAiPlatformClient.directInput(com.intelligentrecruitment.aiplatform.domain.AiCapability.JD_GENERATION,
                Map.of("title", "合成验证工程师", "companyName", "合成测试企业",
                        "responsibilities", java.util.List.of("参与合成测试系统的需求分析与交付"),
                        "requirements", java.util.List.of("具备软件测试经验", "能清晰编写测试记录"),
                        "skills", java.util.List.of("测试设计", "问题分析")));

        assertThat(input).containsOnlyKeys("slots", "custom_fields");
        assertThat(input).extractingByKey("custom_fields").isEqualTo(Map.of("company_name",
                Map.of("status", "answered", "value", "合成测试企业")));
        @SuppressWarnings("unchecked") var slots = (Map<String, Object>) input.get("slots");
        assertThat(slots.get("responsibilities")).isEqualTo(Map.of("status", "answered",
                "value", java.util.List.of("参与合成测试系统的需求分析与交付")));
        assertThat(slots.get("other_hard_requirements")).isEqualTo(Map.of("status", "answered",
                "value", java.util.List.of("具备软件测试经验", "能清晰编写测试记录")));
        assertThat(slots).doesNotContainKey("additional_requirements");
        @SuppressWarnings("unchecked") var skillSlot = (Map<String, Object>) slots.get("required_skills");
        assertThat(skillSlot.get("status")).isEqualTo("answered");
        @SuppressWarnings("unchecked") var skills = (java.util.List<Map<String, Object>>) skillSlot.get("value");
        assertThat(skills).hasSize(2);
        assertThat(skills).extracting(skill -> skill.get("name")).containsExactly("测试设计", "问题分析");
        assertThat(skills).allSatisfy(skill -> {
            assertThat(skill).containsKeys("minimum_years", "note");
            assertThat(skill.get("minimum_years")).isNull();
            assertThat(skill.get("note")).isNull();
        });
    }

    @Test
    void freeFormRecruitmentBriefRemainsSupplementaryRatherThanAHardRequirement() {
        String brief = "职责：设计测试；福利：培训；请仅依据已提供信息生成职位描述";
        var input = HttpAiPlatformClient.directInput(com.intelligentrecruitment.aiplatform.domain.AiCapability.JD_GENERATION,
                Map.of("title", "合成验证工程师", "requirement", brief));

        @SuppressWarnings("unchecked") var slots = (Map<String, Object>) input.get("slots");
        assertThat(slots).doesNotContainKeys("other_hard_requirements", "required_skills", "experience_requirement");
        assertThat(slots.get("additional_requirements")).isEqualTo(Map.of("status", "answered",
                "value", java.util.List.of("职责：设计测试", "福利：培训", "请仅依据已提供信息生成职位描述")));
    }

    @Test
    void explicitQualificationsAndSupplementaryBriefKeepTheirSeparateMeanings() {
        var input = HttpAiPlatformClient.directInput(com.intelligentrecruitment.aiplatform.domain.AiCapability.JD_GENERATION,
                Map.of("title", "合成验证工程师", "requirements", java.util.List.of(" 具备接口测试经验 ", " "),
                        "requirement", "本岗位仅用于本地验证"));

        @SuppressWarnings("unchecked") var slots = (Map<String, Object>) input.get("slots");
        assertThat(slots.get("other_hard_requirements")).isEqualTo(Map.of("status", "answered",
                "value", java.util.List.of("具备接口测试经验")));
        assertThat(slots.get("additional_requirements")).isEqualTo(Map.of("status", "answered",
                "value", java.util.List.of("本岗位仅用于本地验证")));
    }

    @Test
    void unknownQualificationsAreOmittedAndConfirmedSlotsAreNeverReinterpreted() {
        var input = HttpAiPlatformClient.directInput(com.intelligentrecruitment.aiplatform.domain.AiCapability.JD_GENERATION,
                Map.of("title", "合成验证工程师", "requirements", java.util.List.of(" ")));
        @SuppressWarnings("unchecked") var slots = (Map<String, Object>) input.get("slots");
        assertThat(slots).containsOnlyKeys("job_title");

        Map<String, Object> notRequired = new java.util.LinkedHashMap<>();
        notRequired.put("status", "not_required");
        notRequired.put("value", null);
        Map<String, Object> confirmed = Map.of("slots", Map.of("job_title", Map.of("status", "answered", "value", "合成岗位"),
                "other_hard_requirements", notRequired));
        assertThat(HttpAiPlatformClient.directInput(com.intelligentrecruitment.aiplatform.domain.AiCapability.JD_GENERATION,
                confirmed)).isSameAs(confirmed);
    }

    @Test
    void onlyExpiredUnacceptedGrantCanBeClosedAsDefinitivelyNotAccepted() {
        Instant now = Instant.parse("2026-10-12T09:10:00Z");

        assertThat(HttpAiPlatformClient.isExpiredUnacceptedGrant(now.minusSeconds(1), now)).isTrue();
        assertThat(HttpAiPlatformClient.isExpiredUnacceptedGrant(now, now)).isTrue();
        assertThat(HttpAiPlatformClient.isExpiredUnacceptedGrant(now.plusSeconds(1), now)).isFalse();
        assertThat(HttpAiPlatformClient.isExpiredUnacceptedGrant(null, now)).isFalse();
    }
}
