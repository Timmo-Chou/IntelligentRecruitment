package com.intelligentrecruitment.aiplatform.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Verifies and durably accepts AIAgentPlatform task-status webhooks. */
@Service
public class AiWebhookService {
    private static final Set<String> EVENT_TYPES = Set.of(
            "task.started", "task.progress", "task.waiting_for_input", "task.item_completed",
            "task.item_failed", "task.partially_completed", "task.completed", "task.failed",
            "task.cancelled", "usage.reported");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String signingSecret;
    private final long timeWindowSeconds;
    private final Clock clock;

    @Autowired
    public AiWebhookService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${app.ai-platform.webhook.signing-secret:}") String signingSecret,
            @Value("${app.ai-platform.webhook.time-window-seconds:300}") long timeWindowSeconds) {
        this(jdbc, objectMapper, signingSecret, timeWindowSeconds, Clock.systemUTC());
    }

    AiWebhookService(JdbcTemplate jdbc, ObjectMapper objectMapper, String signingSecret,
                     long timeWindowSeconds, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.signingSecret = signingSecret;
        this.timeWindowSeconds = Math.max(1L, timeWindowSeconds);
        this.clock = clock;
    }

    public ReceiveResult receive(String signatureHeader, String timestampHeader, String body) {
        if (body == null || body.isBlank()) throw badRequest("Webhook body 不能为空");
        Signature signature = parseSignature(signatureHeader);
        if (timestampHeader != null && !timestampHeader.isBlank()
                && !timestampHeader.equals(signature.timestamp())) {
            throw unauthorized("Webhook 时间戳头不一致");
        }
        long now = Instant.now(clock).getEpochSecond();
        long skew = Math.abs(now - signature.epochSeconds());
        if (skew > timeWindowSeconds) throw unauthorized("Webhook 已超出时间窗口");
        verifySignature(signature, body);

        Event event;
        try {
            event = objectMapper.readValue(body, Event.class);
        } catch (Exception exception) {
            throw badRequest("Webhook 事件格式无效");
        }
        validate(event);

        boolean inserted;
        try {
            inserted = !jdbc.query("""
                    INSERT INTO ai_webhook_events
                        (event_id,ai_task_id,business_task_id,event_type,sequence,occurred_at,payload,received_at)
                    SELECT ?,?,?,?,?,?::timestamptz,?::jsonb,CURRENT_TIMESTAMP
                    WHERE NOT EXISTS (SELECT 1 FROM ai_webhook_events WHERE event_id=?)
                      AND NOT EXISTS (SELECT 1 FROM ai_webhook_events WHERE ai_task_id=? AND sequence>=?)
                    RETURNING event_id
                    """, (rs, row) -> rs.getString(1),
                    event.eventId(), event.aiTaskId(), event.businessTaskId(), event.eventType(), event.sequence(),
                    event.occurredAt().toString(), objectMapper.valueToTree(event.payload()).toString(),
                    event.eventId(), event.aiTaskId(), event.sequence()).isEmpty();
        } catch (DataIntegrityViolationException exception) {
            inserted = false;
        }

        if (inserted) {
            updateRunHint(event);
        }
        return new ReceiveResult(inserted, !inserted);
    }

    private void updateRunHint(Event event) {
        if ("task.started".equals(event.eventType()) || "task.progress".equals(event.eventType())) {
            int progress = event.payload().get("progress") instanceof Number number
                    ? Math.max(0, Math.min(99, number.intValue())) : 15;
            jdbc.update("UPDATE ai_runs SET progress=GREATEST(progress,?) WHERE provider_task_id=? AND status='RUNNING'",
                    progress, event.aiTaskId());
        }
    }

    private void verifySignature(Signature signature, String body) {
        if (signingSecret == null || signingSecret.isBlank()) throw unauthorized("Webhook 签名密钥未配置");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((signature.timestamp() + "." + body).getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(signature.hexSignature());
            if (!MessageDigest.isEqual(expected, actual)) throw unauthorized("Webhook 签名无效");
        } catch (IllegalArgumentException exception) {
            throw unauthorized("Webhook 签名无效");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unauthorized("Webhook 签名校验失败");
        }
    }

    private Signature parseSignature(String header) {
        if (header == null || header.isBlank()) throw unauthorized("缺少 Webhook 签名");
        String timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) continue;
            if ("t".equals(pair[0])) timestamp = pair[1];
            if ("s".equals(pair[0])) signature = pair[1];
        }
        if (timestamp == null || signature == null || signature.isBlank()) throw unauthorized("Webhook 签名格式无效");
        try {
            long epochSeconds = Long.parseLong(timestamp);
            if (epochSeconds < 0) throw new NumberFormatException();
            return new Signature(timestamp, epochSeconds, signature);
        } catch (NumberFormatException exception) {
            throw unauthorized("Webhook 时间戳无效");
        }
    }

    private void validate(Event event) {
        if (event.eventId() == null || event.eventId().isBlank()
                || event.aiTaskId() == null || event.aiTaskId().isBlank()
                || event.businessTaskId() == null || event.businessTaskId().isBlank()
                || event.occurredAt() == null || event.payload() == null
                || event.sequence() < 0 || !EVENT_TYPES.contains(event.eventType())) {
            throw badRequest("Webhook 事件字段无效");
        }
    }

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ReceiveResult(boolean accepted, boolean duplicateOrStale) { }

    record Signature(String timestamp, long epochSeconds, String hexSignature) { }

    record Event(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("occurred_at") Instant occurredAt,
            int sequence,
            @JsonProperty("ai_task_id") String aiTaskId,
            @JsonProperty("business_task_id") String businessTaskId,
            Map<String, Object> payload) { }
}
