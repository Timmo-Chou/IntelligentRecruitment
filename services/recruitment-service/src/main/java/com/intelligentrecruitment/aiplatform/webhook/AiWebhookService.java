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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Verifies and durably accepts AIAgentPlatform task-status webhooks. */
@Service
public class AiWebhookService {
    private static final Set<String> EVENT_TYPES = Set.of(
            "task.started", "task.progress", "task.waiting_for_input", "task.item_completed",
            "task.item_failed", "task.partially_completed", "task.completed", "task.failed",
            "task.cancelled", "task.cancel_requested", "task.reconciliation_required",
            "task.result_mapping_failed", "usage.reported");

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

    @Transactional
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

        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",event.aiTaskId());
        String payload=objectMapper.valueToTree(event.payload()).toString();
        String digest=eventDigest(event);
        int inserted=jdbc.update("""
                INSERT INTO ai_webhook_events(event_id,ai_task_id,business_task_id,event_type,sequence,occurred_at,payload,received_at,payload_sha256)
                VALUES(?,?,?,?,?,?::timestamptz,?::jsonb,CURRENT_TIMESTAMP,?) ON CONFLICT(event_id) DO NOTHING
                """,event.eventId(),event.aiTaskId(),event.businessTaskId(),event.eventType(),event.sequence(),
                event.occurredAt().toString(),payload,digest);
        if(inserted==0){
            WebhookPrior prior=jdbc.query("SELECT ai_task_id,business_task_id,event_type,sequence,occurred_at,payload::text,payload_sha256 FROM ai_webhook_events WHERE event_id=? FOR UPDATE",
                    (rs,n)->new WebhookPrior(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getTimestamp(5).toInstant(),rs.getString(6),rs.getString(7)),event.eventId()).stream().findFirst().orElse(null);
            if(prior!=null&&!sameEvent(prior,event)){
                jdbc.update("INSERT INTO ai_webhook_event_conflicts(event_id,payload_sha256,event_body,received_at) VALUES(?,?,?::jsonb,CURRENT_TIMESTAMP) ON CONFLICT(event_id,payload_sha256) DO NOTHING",
                        event.eventId(),digest,body);
            }
            return new ReceiveResult(false,true);
        }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",event.aiTaskId());
        if(isStale(event)){
            jdbc.update("UPDATE ai_webhook_events SET processing_status='STALE' WHERE event_id=?",event.eventId());
            return new ReceiveResult(true,true);
        }
        String bindingError=validateExecutionBinding(event);
        if(bindingError!=null){
            String state="EXECUTION_BINDING_NOT_FOUND".equals(bindingError)?"AWAITING_BINDING":"BINDING_FAILED";
            jdbc.update("UPDATE ai_webhook_events SET processing_status=?,processing_error_code=? WHERE event_id=?",state,bindingError,event.eventId());
            return new ReceiveResult(true,false);
        }
        if(projectedStatus(event)==null){
            jdbc.update("UPDATE ai_webhook_events SET processing_status='CONTRACT_INVALID',processing_error_code='UNKNOWN_OR_CONFLICTING_STATUS' WHERE event_id=?",event.eventId());
            return new ReceiveResult(true,false);
        }
        var projection=updateRunHint(event);
        if(projection==com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome.CONFLICT){
            jdbc.update("UPDATE ai_webhook_events SET processing_status='CONTRACT_INVALID',processing_error_code='STATE_VERSION_CONFLICT' WHERE event_id=?",event.eventId());
        }else if(projection==com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome.APPLIED
                ||projection==com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome.IDEMPOTENT){
            jdbc.update("UPDATE ai_webhook_events SET processing_status='APPLIED' WHERE event_id=?",event.eventId());
        }else{
            jdbc.update("UPDATE ai_webhook_events SET processing_status='STALE',processing_error_code=? WHERE event_id=?",
                    projection.name(),event.eventId());
        }
        return new ReceiveResult(true,false);
    }

    @Transactional
    public int applyEventsAwaitingBinding(){
        var pending=jdbc.query("SELECT event_id,ai_task_id,business_task_id,event_type,sequence,occurred_at,payload FROM ai_webhook_events WHERE processing_status='AWAITING_BINDING' ORDER BY received_at LIMIT 100",
                (rs,n)->new Event(rs.getString(1),rs.getString(4),rs.getTimestamp(6).toInstant(),rs.getLong(5),rs.getString(2),rs.getString(3),parsePayload(rs.getString(7))));
        int applied=0;
        for(Event event:pending){
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",event.aiTaskId());
            if(isStale(event)){
                jdbc.update("UPDATE ai_webhook_events SET processing_status='STALE' WHERE event_id=? AND processing_status='AWAITING_BINDING'",event.eventId());
                continue;
            }
            String error=validateExecutionBinding(event);
            if(error==null&&projectedStatus(event)!=null){
                var projection=updateRunHint(event);
                if(projection==com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome.APPLIED
                        ||projection==com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome.IDEMPOTENT){
                    jdbc.update("UPDATE ai_webhook_events SET processing_status='APPLIED',processing_error_code=NULL WHERE event_id=? AND processing_status='AWAITING_BINDING'",event.eventId());applied++;
                }else if(projection==com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome.CONFLICT){
                    jdbc.update("UPDATE ai_webhook_events SET processing_status='CONTRACT_INVALID',processing_error_code='STATE_VERSION_CONFLICT' WHERE event_id=?",event.eventId());
                }else{
                    jdbc.update("UPDATE ai_webhook_events SET processing_status='STALE',processing_error_code=? WHERE event_id=?",projection.name(),event.eventId());
                }
            }
            else if(error==null)jdbc.update("UPDATE ai_webhook_events SET processing_status='CONTRACT_INVALID',processing_error_code='UNKNOWN_OR_CONFLICTING_STATUS' WHERE event_id=?",event.eventId());
            else if(!"EXECUTION_BINDING_NOT_FOUND".equals(error))jdbc.update("UPDATE ai_webhook_events SET processing_status='BINDING_FAILED',processing_error_code=? WHERE event_id=?",error,event.eventId());
        }
        return applied;
    }

    private Map<String,Object> parsePayload(String payload){
        try{return objectMapper.readValue(payload,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
        catch(Exception ex){throw new IllegalStateException("Persisted webhook payload is invalid",ex);}
    }

    private boolean isStale(Event event){
        Long newest=jdbc.queryForObject("SELECT max(sequence) FROM ai_webhook_events WHERE ai_task_id=? AND event_id<>? AND processing_status='APPLIED'",Long.class,event.aiTaskId(),event.eventId());
        return newest!=null&&event.sequence()<newest;
    }

    private String validateExecutionBinding(Event event){
        if(!(event.payload().get("tenant_id") instanceof String tenant)||!(event.payload().get("agent_id") instanceof String agent)
                ||!(event.payload().get("attempt_id") instanceof String attempt)||!(event.payload().get("operation") instanceof String operation))return "EVENT_BINDING_MISSING";
        try{java.util.UUID.fromString(tenant);}catch(IllegalArgumentException ex){return "EVENT_BINDING_INVALID";}
        var rows=jdbc.query("SELECT tenant_id::text,agent_id,attempt_id,operation FROM ai_execution_records WHERE agent_task_id=? AND business_task_id=?",
                (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},event.aiTaskId(),event.businessTaskId());
        if(rows.isEmpty())return "EXECUTION_BINDING_NOT_FOUND";
        String[] saved=rows.getFirst();
        if(!tenant.equals(saved[0])||!agent.equals(saved[1])||!attempt.equals(saved[2])||!operation.equals(saved[3]))return "EXECUTION_BINDING_MISMATCH";
        return null;
    }

    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException("Unable to fingerprint webhook payload",ex);}
    }

    private String eventDigest(Event event){
        try{
            Map<String,Object> canonical=new java.util.LinkedHashMap<>();
            canonical.put("event_id",event.eventId());canonical.put("event_type",event.eventType());canonical.put("occurred_at",event.occurredAt().toString());
            canonical.put("sequence",event.sequence());canonical.put("ai_task_id",event.aiTaskId());canonical.put("business_task_id",event.businessTaskId());
            canonical.put("payload",new java.util.TreeMap<>(event.payload()));
            return sha256(objectMapper.writeValueAsString(canonical));
        }catch(Exception ex){throw new IllegalStateException("Unable to canonicalize webhook event",ex);}
    }

    private boolean sameEvent(WebhookPrior prior,Event event){
        if(!prior.aiTaskId().equals(event.aiTaskId())||!prior.businessTaskId().equals(event.businessTaskId())
                ||!prior.eventType().equals(event.eventType())||prior.sequence()!=event.sequence()||!prior.occurredAt().equals(event.occurredAt()))return false;
        try{
            if(!objectMapper.readTree(prior.payload()).equals(objectMapper.valueToTree(event.payload())))return false;
        }catch(Exception ex){return false;}
        // Compare the persisted envelope and JSON value semantically. This also
        // handles rows created before V17, whose fingerprint column is null or
        // used the earlier payload-only digest format.
        return true;
    }

    private com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome updateRunHint(Event event) {
        String status=projectedStatus(event);
        if(status==null)return com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome.CONFLICT;
        var outcome=com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.project(
                jdbc,uuid(event.payload().get("tenant_id")),event.aiTaskId(),event.sequence(),status);
        if (outcome==com.intelligentrecruitment.aiplatform.infrastructure.PlatformExecutionStateProjection.Outcome.APPLIED
                && ("task.started".equals(event.eventType()) || "task.progress".equals(event.eventType()))) {
            int progress = event.payload().get("progress") instanceof Number number
                    ? Math.max(0, Math.min(99, number.intValue())) : 15;
            jdbc.update("UPDATE ai_runs SET progress=GREATEST(progress,?) WHERE provider_task_id=? AND status='RUNNING'",
                    progress, event.aiTaskId());
        }
        return outcome;
    }

    private static java.util.UUID uuid(Object value){
        try{return java.util.UUID.fromString(String.valueOf(value));}catch(RuntimeException ex){return null;}
    }

    private String projectedStatus(Event event){
        String supplied=String.valueOf(event.payload().get("status"));
        com.intelligentrecruitment.aiplatform.domain.AiTaskStatus projected=
                com.intelligentrecruitment.aiplatform.domain.PlatformTaskStatusProjection.fromWebhook(event.eventType(),supplied);
        return projected==null?null:projected.name();
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
        Object version=event.payload().get("state_version");
        boolean exactVersion=false;
        if(version instanceof Number n){try{exactVersion=new java.math.BigDecimal(n.toString()).longValueExact()==event.sequence();}catch(ArithmeticException ignored){}}
        if(!exactVersion||!(event.payload().get("status") instanceof String))
            throw badRequest("Webhook state_version 或 status 无效");
    }

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ReceiveResult(boolean accepted, boolean duplicateOrStale) { }

    private record WebhookPrior(String aiTaskId,String businessTaskId,String eventType,long sequence,Instant occurredAt,String payload,String payloadSha256) { }

    record Signature(String timestamp, long epochSeconds, String hexSignature) { }

    record Event(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("occurred_at") Instant occurredAt,
            long sequence,
            @JsonProperty("ai_task_id") String aiTaskId,
            @JsonProperty("business_task_id") String businessTaskId,
            Map<String, Object> payload) { }
}
