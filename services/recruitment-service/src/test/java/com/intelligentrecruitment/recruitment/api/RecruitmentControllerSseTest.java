package com.intelligentrecruitment.recruitment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intelligentrecruitment.recruitment.application.RecruitmentService;
import com.intelligentrecruitment.shared.security.AuthenticatedUser;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RecruitmentControllerSseTest {

    private final RecruitmentService recruitment = mock(RecruitmentService.class);
    private final RecruitmentController controller = new RecruitmentController(recruitment);
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final TenantScope scope = new TenantScope(tenantId, "ENTERPRISE", "测试企业", "OWNER");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(userId, "request-only-token"), null, List.of());

    @Test
    void authorizesOnRequestThreadBeforeReturningEventStream() throws Exception {
        when(recruitment.authorizeRunEventStream(userId, tenantId, taskId)).thenReturn(scope);
        when(recruitment.runEvents(scope, taskId, 0)).thenReturn(List.of(
                new RecruitmentService.RunEvent(42, UUID.randomUUID(), "completed",
                        "{\"status\":\"COMPLETED\",\"progress\":100}", Instant.parse("2026-10-13T10:00:00Z"))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        StreamingResponseBody body = controller.events(tenantId, taskId, null, authentication, response);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        body.writeTo(output);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache, no-transform");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(output.toString()).contains("id: 42\nevent: completed\ndata: {\"status\":\"COMPLETED\",\"progress\":100}\n\n");
        var order = inOrder(recruitment);
        order.verify(recruitment).authorizeRunEventStream(userId, tenantId, taskId);
        order.verify(recruitment).runEvents(scope, taskId, 0);
    }

    @Test
    void rejectsUnauthorizedStreamBeforeCreatingAsyncBody() {
        ApiException denied = new ApiException("BOSS_SESSION_REQUIRED", "登录上下文缺失", org.springframework.http.HttpStatus.UNAUTHORIZED);
        when(recruitment.authorizeRunEventStream(userId, tenantId, taskId)).thenThrow(denied);

        assertThatThrownBy(() -> controller.events(tenantId, taskId, null, authentication,
                new MockHttpServletResponse())).isSameAs(denied);

        verify(recruitment, never()).runEvents(scope, taskId, 0);
    }

    @Test
    void closesStreamAfterPublishingReconciliationRequiredEvent() throws Exception {
        when(recruitment.authorizeRunEventStream(userId, tenantId, taskId)).thenReturn(scope);
        when(recruitment.runEvents(scope, taskId, 0)).thenReturn(List.of(
                new RecruitmentService.RunEvent(43, UUID.randomUUID(), "reconciliation_required",
                        "{\"status\":\"RECONCILIATION_REQUIRED\",\"errorCode\":\"PROVIDER_UNAVAILABLE\"}",
                        Instant.parse("2026-10-13T10:00:01Z"))));
        StreamingResponseBody body = controller.events(tenantId, taskId, null, authentication,
                new MockHttpServletResponse());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        body.writeTo(output);

        assertThat(output.toString()).contains("event: reconciliation_required");
        verify(recruitment).runEvents(scope, taskId, 0);
    }
}
