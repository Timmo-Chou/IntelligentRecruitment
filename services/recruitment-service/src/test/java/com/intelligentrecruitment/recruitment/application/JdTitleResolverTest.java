package com.intelligentrecruitment.recruitment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JdTitleResolverTest {

    @Test
    void explicitTitleTakesPrecedenceOverRequirementText() {
        assertThat(JdTitleResolver.resolve("  Java 工程师 ", "岗位名称：算法工程师"))
                .isEqualTo("Java 工程师");
    }

    @Test
    void extractsNumberedChineseLabeledTitleFromUserRequirement() {
        String requirement = "1. 岗位名称：大模型算法工程师\n2. 薪资：35K–60K\n3. 工作地点：北京";

        assertThat(JdTitleResolver.resolve("", requirement)).isEqualTo("大模型算法工程师");
    }

    @Test
    void acceptsOtherExplicitTitleLabels() {
        assertThat(JdTitleResolver.resolve(null, "职位名称: 测试开发工程师\n工作地点：苏州"))
                .isEqualTo("测试开发工程师");
        assertThat(JdTitleResolver.resolve(null, "招聘岗位：数据分析师"))
                .isEqualTo("数据分析师");
    }

    @Test
    void doesNotInferTitleFromUnlabeledTextOrPlaceholder() {
        assertThat(JdTitleResolver.resolve(null, "招聘一名熟悉大模型的工程师" )).isEmpty();
        assertThat(JdTitleResolver.resolve(null, "岗位名称：待确认\n要求：熟悉模型" )).isEmpty();
    }
}
