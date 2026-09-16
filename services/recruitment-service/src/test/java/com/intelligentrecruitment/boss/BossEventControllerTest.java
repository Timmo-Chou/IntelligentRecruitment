package com.intelligentrecruitment.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.boss.api.BossEventController;
import com.intelligentrecruitment.shared.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BossEventControllerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void consumesDuplicateEventExactlyOnce() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        BossEventController controller = new BossEventController(jdbc, "event-secret");
        JsonNode event = json.readTree("""
                {"event_id":"evt-1","event_type":"company.status.changed","aggregate_id":"00000000-0000-0000-0000-000000000001",
                 "payload":{"tenant_id":"00000000-0000-0000-0000-000000000001","tenant_id":"00000000-0000-0000-0000-000000000002","status":"SUSPENDED"}}
                """);

        controller.receive("Bearer event-secret", event);
        controller.receive("Bearer event-secret", event);

        verify(jdbc, times(1)).update(startsWith("UPDATE boss_company_projections"), any(Object[].class));
    }

    @Test
    void rejectsInvalidEventCredential() throws Exception {
        BossEventController controller = new BossEventController(mock(JdbcTemplate.class), "event-secret");
        JsonNode event = json.readTree("{\"event_id\":\"evt-1\",\"event_type\":\"company.activated\",\"payload\":{}}");

        assertThatThrownBy(() -> controller.receive("Bearer wrong", event))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("BOSS_EVENT_UNAUTHORIZED"));
    }
}
