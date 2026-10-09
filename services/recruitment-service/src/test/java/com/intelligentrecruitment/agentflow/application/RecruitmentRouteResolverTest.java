package com.intelligentrecruitment.agentflow.application;

import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecruitmentRouteResolverTest {
    @Test
    void complexFlagOnlyChangesTheThreeDirectCapabilities() {
        RecruitmentRouteResolver resolver = new RecruitmentRouteResolver("true", "route-20261008-1");

        assertThat(resolver.resolve(FlowCapability.JD_GENERATION).agentId()).isEqualTo("complex_recruitment_agent");
        assertThat(resolver.resolve(FlowCapability.RESUME_PARSING).agentId()).isEqualTo("complex_recruitment_agent");
        assertThat(resolver.resolve(FlowCapability.CANDIDATE_SCREENING).agentId()).isEqualTo("complex_recruitment_agent");
        assertThat(resolver.resolve(FlowCapability.JD_IN_PLACE_REVISION).agentId()).isEqualTo("simple_recruitment_agent");
        assertThat(resolver.resolve(FlowCapability.INTERVIEW_KIT_GENERATION).agentId()).isEqualTo("simple_recruitment_agent");
        assertThat(resolver.resolve(FlowCapability.CANDIDATE_SCREENING).operation()).isEqualTo("match");
    }

    @Test
    void invalidOrMissingConfigurationFailsImmediately() {
        assertThatThrownBy(() -> new RecruitmentRouteResolver("", "route-v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecruitmentRouteResolver("sometimes", "route-v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecruitmentRouteResolver("false", " "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecruitmentRouteResolver("false", "bad version"))
                .isInstanceOf(IllegalStateException.class);
    }
}
