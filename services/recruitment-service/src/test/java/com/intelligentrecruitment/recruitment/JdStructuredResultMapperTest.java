package com.intelligentrecruitment.recruitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.recruitment.application.JdStructuredResultMapper;
import com.intelligentrecruitment.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdStructuredResultMapperTest {

    private final JdStructuredResultMapper mapper = new JdStructuredResultMapper();

    @Test
    void mapsOnlyCompleteStructuredJdDrafts() {
        var draft = mapper.toDraft(result(Map.ofEntries(
                Map.entry("title", "Java 工程师"), Map.entry("company_name", "示例科技"),
                Map.entry("location", "上海"), Map.entry("experience_level", "3 年以上"),
                Map.entry("education", "本科"), Map.entry("job_type", "全职"),
                Map.entry("salary_range", "25K-35K·14薪"),
                Map.entry("responsibilities", "负责服务端开发"), Map.entry("requirements", "熟悉 Java"),
                Map.entry("skills", "Java、Spring Boot"), Map.entry("nice_to_haves", "有云原生经验"),
                Map.entry("benefits", "五险一金"), Map.entry("talent_profile", "有服务端经验"),
                Map.entry("warnings", List.of("请确认薪资")))));

        assertThat(draft.title()).isEqualTo("Java 工程师");
        assertThat(draft.salaryRange()).isEqualTo("25K-35K·14薪");
        assertThat(draft.niceToHaves()).isEqualTo("有云原生经验");
        assertThat(draft.benefits()).isEqualTo("五险一金");
        assertThat(draft.warnings()).containsExactly("请确认薪资");
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> mapper.toDraft(result(Map.of("title", "Java 工程师"))))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("AI_CONTRACT_INVALID");
    }

    @Test
    void rendersStructuredArrayFieldsAsSeparateLines() {
        var draft = mapper.toDraft(result(Map.ofEntries(
                Map.entry("title", "Java 工程师"), Map.entry("company_name", "示例科技"),
                Map.entry("responsibilities", List.of("负责服务端开发", "优化系统性能")),
                Map.entry("requirements", List.of("熟悉 Java")),
                Map.entry("skills", List.of("Java", "Spring Boot")),
                Map.entry("talent_profile", "有服务端经验"))));

        assertThat(draft.responsibilities()).isEqualTo("- 负责服务端开发\n- 优化系统性能");
        assertThat(draft.skills()).isEqualTo("- Java\n- Spring Boot");
    }

    private StructuredResult result(Map<String, Object> data) {
        return new StructuredResult(null, "ait-1", FlowCapability.JD_GENERATION, StructuredResult.Status.DRAFT_READY,
                "jd-v1", data, List.of(), List.of(),
                new StructuredResult.Provenance("test", "v1", "prompt-v1", "mock"), null, Instant.now());
    }
}
