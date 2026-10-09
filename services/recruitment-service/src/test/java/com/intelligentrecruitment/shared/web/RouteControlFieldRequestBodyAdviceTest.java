package com.intelligentrecruitment.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteControlFieldRequestBodyAdviceTest {
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void identifiesClientSuppliedRoutingFieldsAtThePublicRequestBoundary() throws Exception {
        assertThat(RouteControlFieldRequestBodyAdvice.containsReservedField(
                json.readTree("{\"plan_id\":\"p\",\"agent_id\":\"simple_recruitment_agent\"}"))).isTrue();
        assertThat(RouteControlFieldRequestBodyAdvice.containsReservedField(
                json.readTree("{\"plan_id\":\"p\",\"candidate_ids\":[\"c\"]}"))).isFalse();
    }

    @Test
    void rejectsReservedFieldsWhenSpringWrapsTheServletRequestBody() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/v1/tenants/tenant/screening-runs");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        HttpInputMessage wrappedBody = new HttpInputMessage() {
            private final HttpHeaders headers = new HttpHeaders();
            @Override public HttpHeaders getHeaders() { return headers; }
            @Override public InputStream getBody() {
                return new ByteArrayInputStream("{\"agent_id\":\"attacker\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        MethodParameter parameter = new MethodParameter(
                RouteControlFieldRequestBodyAdviceTest.class.getDeclaredMethod("requestBodyTarget", String.class), 0);
        RouteControlFieldRequestBodyAdvice advice = new RouteControlFieldRequestBodyAdvice(json);

        assertThat(advice.supports(parameter, String.class, MappingJackson2HttpMessageConverter.class)).isTrue();
        assertThatThrownBy(() -> advice.beforeBodyRead(wrappedBody, parameter, String.class,
                MappingJackson2HttpMessageConverter.class))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("CLIENT_ROUTE_CONTROL_FORBIDDEN");
    }

    @SuppressWarnings("unused")
    private void requestBodyTarget(String ignored) { }
}
