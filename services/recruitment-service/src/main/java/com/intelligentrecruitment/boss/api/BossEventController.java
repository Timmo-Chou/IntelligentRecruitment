package com.intelligentrecruitment.boss.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import com.intelligentrecruitment.shared.error.ApiException;

/** Receives BOSS outbox events and maintains rebuildable local projections. */
@RestController
@RequestMapping("/internal/v1/boss/events")
public class BossEventController {
    private final JdbcTemplate jdbc;
    private final String bearerToken;

    public BossEventController(JdbcTemplate jdbc,
                               @Value("${app.boss.event-bearer-token:}") String bearerToken) {
        this.jdbc = jdbc;
        this.bearerToken = bearerToken;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public void receive(@RequestHeader(value = "Authorization", required = false) String authorization,
                        @RequestBody JsonNode event) {
        if (bearerToken == null || bearerToken.isBlank()
                || authorization == null || !authorization.equals("Bearer " + bearerToken)) {
            throw new ApiException("BOSS_EVENT_UNAUTHORIZED", "BOSS 事件凭证无效", HttpStatus.UNAUTHORIZED);
        }
        String eventId = requiredText(event, "event_id");
        String eventType = requiredText(event, "event_type");
        if (!event.path("payload").isObject()) {
            throw new ApiException("BOSS_EVENT_INVALID", "BOSS 事件 payload 格式无效", HttpStatus.BAD_REQUEST);
        }
        String payload = event.path("payload").toString();
        int inserted = jdbc.update("""
                INSERT INTO boss_event_inbox(event_id,event_type,aggregate_id,payload,processed_at)
                VALUES(?,?,?,?::jsonb,CURRENT_TIMESTAMP) ON CONFLICT(event_id) DO NOTHING
                """, eventId, eventType, nullableUuid(event, "aggregate_id"), payload);
        if (inserted == 0) return;
        JsonNode data = event.path("payload");
        if ("company.status.changed".equals(eventType) || "company.activated".equals(eventType)) {
            UUID companyId = nullableUuid(data, "company_id");
            if (companyId != null) jdbc.update("UPDATE boss_company_projections SET company_status=?,synchronized_at=CURRENT_TIMESTAMP WHERE company_id=?",
                    data.path("status").asText(), companyId);
        } else if ("tenant.status.changed".equals(eventType)) {
            UUID tenantId = nullableUuid(data, "tenant_id");
            if (tenantId != null) jdbc.update("UPDATE boss_company_projections SET tenant_status=?,synchronized_at=CURRENT_TIMESTAMP WHERE tenant_id=?",
                    data.path("status").asText(), tenantId);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new ApiException("BOSS_EVENT_INVALID", "BOSS 事件缺少 " + field, HttpStatus.BAD_REQUEST);
        return value;
    }

    private static UUID nullableUuid(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException("BOSS_EVENT_INVALID", "BOSS 事件 UUID 格式无效", HttpStatus.BAD_REQUEST);
        }
    }
}
