package com.intelligentrecruitment.screening.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningMatcherTest {

    private final ScreeningMatcher matcher = new ScreeningMatcher();
    private final ScreeningMatcher.FrozenJob job = new ScreeningMatcher.FrozenJob(
            "Java工程师", "Java,Spring Boot", "3年以上", "本科", "负责服务端开发");

    @Test
    void appliesWeightsAndFourLevelThresholds() {
        var candidate = candidate(5, "本科", List.of("Java", "Spring Boot"));
        var result = matcher.match(job, candidate, List.of(
                dimension("专业技能", 70, false, "", "REVIEW"),
                dimension("职业履历", 30, false, "", "REVIEW")));

        assertThat(result.score()).isGreaterThanOrEqualTo(85);
        assertThat(result.level()).isEqualTo("STRONG_MATCH");
        assertThat(result.evidence()).anyMatch(value -> value.contains("权重 70%"));
    }

    @Test
    void capsScoreWhenRequiredDimensionIsMissing() {
        var candidate = candidate(5, "本科", List.of());
        var result = matcher.match(job, candidate, List.of(
                dimension("基本信息", 90, false, "", "REVIEW"),
                dimension("专业技能", 10, true, "", "REVIEW")));

        assertThat(result.score()).isLessThanOrEqualTo(59);
        assertThat(result.level()).isEqualTo("WEAK_MATCH");
        assertThat(result.risks()).anyMatch(value -> value.contains("必须项未满足"));
    }

    @Test
    void honorsMissingPolicyAndOnlyFlagsExclusionForReview() {
        var candidate = new ScreeningMatcher.FrozenCandidate("Java工程师", 5, "本科", List.of("Java"),
                "", "", "具有外包项目经验");
        var ignored = matcher.match(job, candidate,
                List.of(dimension("求职动机", 100, false, "外包", "IGNORE")));
        var reviewed = matcher.match(job, candidate,
                List.of(dimension("求职动机", 100, false, "外包", "REVIEW")));

        assertThat(ignored.score()).isGreaterThan(reviewed.score());
        assertThat(ignored.risks()).anyMatch(value -> value.contains("仅标记人工复核"));
        assertThat(ignored.risks()).noneMatch(value -> value.contains("自动淘汰候选人") && value.contains("命中"));
    }

    private static ScreeningMatcher.FrozenCandidate candidate(int years, String education, List<String> skills) {
        return new ScreeningMatcher.FrozenCandidate("Java工程师", years, education, skills,
                "参与多个核心系统建设并交付业务成果", "负责系统设计与研发", "");
    }

    private static ScreeningService.DimensionInput dimension(String name, int weight, boolean required,
                                                               String exclusion, String missingPolicy) {
        return new ScreeningService.DimensionInput(name, weight, "", required, exclusion, missingPolicy);
    }
}
