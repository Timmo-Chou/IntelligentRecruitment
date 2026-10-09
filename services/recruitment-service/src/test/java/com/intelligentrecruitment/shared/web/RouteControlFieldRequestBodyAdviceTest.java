package com.intelligentrecruitment.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteControlFieldRequestBodyAdviceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void identifiesClientSuppliedRoutingFieldsAtThePublicRequestBoundary() throws Exception {
        assertThat(RouteControlFieldRequestBodyAdvice.containsReservedField(
                json.readTree("{\"plan_id\":\"p\",\"agent_id\":\"simple_recruitment_agent\"}"))).isTrue();
        assertThat(RouteControlFieldRequestBodyAdvice.containsReservedField(
                json.readTree("{\"plan_id\":\"p\",\"candidate_ids\":[\"c\"]}"))).isFalse();
    }
}
