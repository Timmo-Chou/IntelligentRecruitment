package com.intelligentrecruitment.aiplatform.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.aiplatform.application.*;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;

/**
 * 通过 HTTP 调用 AIAgentPlatform 的客户端（对内面 /api/v1/*）。
 * 所有招聘 AI 能力均由 AIAgentPlatform 执行；BOSS 在 Agent 接收任务前完成
 * 授权和积分预占，并在任务完成后接收用量上报与结算。
 */
@Component
public class HttpAiPlatformClient implements AiPlatformClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiPlatformClient.class);

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final BossControlPlaneClient boss;
    private final String serviceToken;
    private final AiExecutionLedger ledger;
    private final com.intelligentrecruitment.agentflow.application.RecruitmentRouteResolver routeResolver;
    private final long reconciliationHoldSeconds;
    private final com.intelligentrecruitment.agentflow.application.RecruitmentFlowCoordinator flow;
    private final Map<String, BossControlPlaneClient.AiAuthorization> authorizations = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

    public HttpAiPlatformClient(RestClient.Builder builder, ObjectMapper objectMapper,
                                @Value("${app.ai-platform.http.base-url:http://localhost:8083}") String baseUrl,
                                BossControlPlaneClient boss,
                                @Value("${app.ai-platform.http.service-token:}") String serviceToken,
                                AiExecutionLedger ledger,
                                com.intelligentrecruitment.agentflow.application.RecruitmentRouteResolver routeResolver,
                                @Value("${app.ai-platform.reconciliation-hold-seconds:259200}") long reconciliationHoldSeconds,com.intelligentrecruitment.agentflow.application.RecruitmentFlowCoordinator flow) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceFirst("/+$", "");
        this.boss = boss;
        this.serviceToken = serviceToken;
        this.ledger = ledger;this.flow=flow;
        this.routeResolver = routeResolver;
        this.reconciliationHoldSeconds = Math.max(60, reconciliationHoldSeconds);
    }

    /** BOSS-driven lifecycle cleanup; AIAgent retains accounting metadata but erases business payloads. */
    public void logicallyDeleteTenantBusinessData(java.util.UUID tenantId) {
        client.post().uri("/api/v1/internal/tenants/{tenantId}/logical-delete", tenantId)
                // AIAgentPlatform's internal API is protected by the dedicated IR service
                // credential. The BOSS machine token is used only for BOSS callbacks.
                .header("Authorization", "Bearer " + serviceToken).retrieve().toBodilessEntity();
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command) {
        ExecutionContext execution = command.executionContext();
        if (execution == null || execution.policyDecision() == null
                || !execution.policyDecision().allowsExecution()) {
            throw new IllegalStateException("调用 AIAgentPlatform 前必须完成 BOSS PolicyDecision=allow");
        }
        if (execution.tenantId() == null || execution.actorId() == null) {
            throw new IllegalStateException("AIAgentPlatform ExecutionContext 必须包含 tenant_id 与 actor_id");
        }
        if (!execution.tenantId().toString().equals(command.tenantId())
                || !execution.actorId().toString().equals(command.actorId())
                || !execution.businessTaskId().toString().equals(command.businessTaskId())
                || !execution.idempotencyKey().equals(command.idempotencyKey())) {
            throw new IllegalStateException("AI 命令与 ExecutionContext 的 Tenant、用户、业务任务或幂等键不一致");
        }
        Map<String, Object> context = Map.of(
                "request_id", execution.requestId(),
                "trace_id", execution.traceId(),
                "tenant_id", execution.tenantId(),
                "actor_id", execution.actorId(),
                "business_task_id", execution.businessTaskId(),
                "attempt_id",execution.executionId(),
                "idempotency_key", execution.idempotencyKey(),
                "locale", "zh-CN", "timezone", "Asia/Shanghai", "contract_version", "rd-execution-v2");
        BossControlPlaneClient.AiAuthorization prior=ledger.authorizationForExecution(execution.tenantId(),command.idempotencyKey()).orElse(null);
        ExecutionContext.AgentRoute frozenRoute=execution.agentRoute();
        if (prior == null && frozenRoute == null) {
            throw new IllegalStateException("AI Attempt 缺少创建时冻结的 Agent 路由");
        }
        String routeVersion;
        String agentId;
        String operation;
        if (prior != null) {
            if (prior.agentId() == null || prior.operation() == null || prior.routeConfigVersion() == null) {
                throw new IllegalStateException("已授权 Attempt 缺少持久化路由快照，拒绝按当前配置重新路由");
            }
            routeVersion=prior.routeConfigVersion();
            agentId=prior.agentId();
            operation=prior.operation();
            if (frozenRoute != null && (!frozenRoute.agentId().equals(agentId)
                    || !frozenRoute.operation().equals(operation)
                    || !frozenRoute.routeConfigVersion().equals(routeVersion))) {
                throw new IllegalStateException("已授权 Attempt 的路由快照与持久化授权不一致");
            }
        } else {
            routeVersion=frozenRoute.routeConfigVersion();
            agentId=frozenRoute.agentId();
            operation=frozenRoute.operation();
        }
        Map<String,Object> wireInput=new java.util.LinkedHashMap<>("complex_recruitment_agent".equals(agentId)
                ? directInput(command.capability(),command.input()) : command.input());
        wireInput.putIfAbsent("files",List.of());
        String inputHash=canonicalHash(wireInput,execution,command.capability(),operation,agentId);
        if(prior!=null&&(!inputHash.equals(prior.inputHash())||!execution.executionId().equals(prior.attemptId())||!execution.businessTaskId().toString().equals(prior.taskId())||!execution.actorId().equals(prior.actorId())))throw new IllegalStateException("同一Attempt的请求与持久化授权绑定冲突");
        Map<String, Object> body = Map.ofEntries(
                Map.entry("execution_id",execution.executionId().toString()),Map.entry("request_context",context),
                Map.entry("capability",command.capability().name().toLowerCase()),Map.entry("business_operation_ref",execution.businessOperationRef()),
                Map.entry("input_versions",execution.inputVersions()==null?List.of():execution.inputVersions()),Map.entry("policy_decision",execution.policyDecision()),
                Map.entry("data_handling",execution.dataHandling()),Map.entry("requested_at",execution.requestedAt()),Map.entry("input",wireInput),
                Map.entry("agent_id",agentId),Map.entry("operation",operation),Map.entry("attempt_id",execution.executionId()),
                Map.entry("route_config_version",routeVersion),Map.entry("input_hash",inputHash));

        BossControlPlaneClient.AiAuthorization authorization = null;
        try {
            if(prior!=null){AiTask accepted=findAcceptedTask(execution.tenantId(),execution.actorId(),command.capability(),command.idempotencyKey(),prior);if(accepted!=null){
                ledger.agentAccepted(execution.tenantId(),command.idempotencyKey(),accepted.aiTaskId());authorizations.put(accepted.aiTaskId(),prior);
                acknowledgeAcceptance(prior,accepted.aiTaskId());
                return accepted;
            }}
            authorization = boss.authorizeAiExecutionV2(execution.tenantId(), execution.actorId(),
                    execution.businessTaskId().toString(), command.capability().name(),operation,agentId,
                    execution.executionId(),command.idempotencyKey(),routeVersion,inputHash,authorizedUnits(command,agentId));
            ledger.authorized(execution.executionId(), execution.tenantId(), execution.actorId(), execution.businessTaskId().toString(), command.capability().name(), command.idempotencyKey(), authorization);
            String raw = client.post()
                    .uri("/api/v1/capability-executions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.idempotencyKey())
                    .header("X-Request-Id", execution.requestId())
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-AI-Grant", authorization.grant())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(raw);
            AiTask result = toAiTask(node, command.capability());
            authorizations.put(result.aiTaskId(), authorization);
            ledger.agentAccepted(execution.tenantId(), command.idempotencyKey(), result.aiTaskId());
            acknowledgeAcceptance(authorization,result.aiTaskId());
            return result;
        } catch (Exception ex) {
            if (authorization != null) {
                try { ledger.submissionUnconfirmed(authorization, Instant.now().plusSeconds(reconciliationHoldSeconds)); }
                catch (RuntimeException releaseFailure) { ex.addSuppressed(releaseFailure); }
            }
            log.error("调用 AIAgentPlatform 失败 capability={}", command.capability(), ex);
            throw new RuntimeException("AIAgentPlatform 调用失败: " + ex.getMessage(), ex);
        }
    }

    private static int authorizedUnits(StartAiTaskCommand command,String agentId){
        if("complex_recruitment_agent".equals(agentId)&&command.capability()==AiCapability.CANDIDATE_SCREENING){
            Object raw=command.input().get("resumes");
            if(!(raw instanceof List<?> files)||files.isEmpty()||files.size()>5)throw new IllegalArgumentException("RD 批次候选人数量必须为 1 至 5");
            return files.size();
        }
        return 1;
    }

    private void acknowledgeAcceptance(BossControlPlaneClient.AiAuthorization auth,String taskId){
        JsonNode reservation=boss.acceptAiExecution(auth,taskId);
        if(reservation!=null&&"RELEASE".equals(reservation.path("final_decision").asText())){
            if("RECONCILIATION_DEADLINE_EXPIRED".equals(reservation.path("close_reason").asText()))ledger.closeObservedDeadlineRelease(auth,taskId);
            throw new IllegalStateException("授权已关闭，迟到结果只能保留审计");
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.ai-platform.acceptance-reconcile-delay-ms:30000}")
    public void reconcileAcceptedExecutions() {
        for (var pending : ledger.pendingExecutions()) {
            try {
                var auth = ledger.authorizationForExecution(pending.tenantId(), pending.idempotencyKey()).orElse(null);
                if (auth == null || auth.agentId() == null) continue;
                JsonNode financial=boss.executionReservation(auth);
                String closeAt=financial.path("reconciliation_deadline").asText(null);
                if(closeAt!=null&&!Instant.parse(closeAt).isAfter(Instant.now())){
                    ledger.decideDeadline(auth.authorizationId(),auth.reservationId(),pending.agentTaskId(),Instant.parse(closeAt));
                    continue;
                }
                AiTask task;
                if (pending.agentTaskId() == null) {
                    task = findAcceptedTask(auth.tenantId(),auth.actorId(),AiCapability.valueOf(auth.capabilityCode()),auth.idempotencyKey(),auth);
                    if (task == null) continue;
                    ledger.agentAccepted(auth.tenantId(),auth.idempotencyKey(),task.aiTaskId());
                    acknowledgeAcceptance(auth,task.aiTaskId());
                } else task = getTask(pending.agentTaskId(), auth.actorId().toString());
                authorizations.put(task.aiTaskId(),auth);
                if(task.status()==AiTaskStatus.COMPLETED&&ledger.cancellationRequested(auth.tenantId(),auth.idempotencyKey())){
                    if(!ledger.confirmCancelledExecution(auth,task.aiTaskId(),getStructuredResult(task.aiTaskId(),auth.actorId().toString())))holdForReconciliation(task.aiTaskId(),"CANCELLED_RESULT_UNVERIFIED");
                }
                if (task.status()==AiTaskStatus.RECONCILIATION_REQUIRED || task.status()==AiTaskStatus.RESULT_MAPPING_FAILED)
                    holdForReconciliation(task.aiTaskId(),task.status().name());
                else if (task.status()==AiTaskStatus.FAILED || task.status()==AiTaskStatus.CANCELLED)
                    confirmNoBillableResult(task.aiTaskId(), "PLATFORM_"+task.status().name());
            } catch (RuntimeException ex) { log.warn("AI执行受理核对失败 execution={}", pending.idempotencyKey()); }
        }
    }

    private AiTask findAcceptedTask(UUID tenantId,UUID actorId,AiCapability capability,String idempotencyKey,BossControlPlaneClient.AiAuthorization authorization){
        try{Map<String,Object> lookup=Map.ofEntries(
                        Map.entry("tenant_id",tenantId),Map.entry("actor_id",actorId),
                        Map.entry("capability",capability.name().toLowerCase()),Map.entry("idempotency_key",idempotencyKey),
                        Map.entry("authorization_id",authorization.authorizationId()),Map.entry("attempt_id",authorization.attemptId()),
                        Map.entry("route_config_version",authorization.routeConfigVersion()),Map.entry("input_hash",authorization.inputHash()),
                        Map.entry("agent_id",authorization.agentId()),Map.entry("operation",authorization.operation()),
                        Map.entry("business_task_id",authorization.taskId()));
            String raw=client.post().uri("/api/v1/capability-executions/lookup").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(lookup)
                .header("Authorization","Bearer "+serviceToken).header("X-IR-Tenant-Id",tenantId.toString()).header("X-IR-Actor-Id",actorId.toString())
                .retrieve().body(String.class);if(raw==null)return null;JsonNode response=objectMapper.readTree(raw);
            if(!response.path("found").asBoolean(false))return null;
            if(!response.path("task").isObject())throw new IllegalStateException("Lookup 响应 found=true 但缺少 task");
            return toAiTask(response.path("task"),capability);
        }
        catch(Exception ex){throw new RuntimeException("查询内部AI任务回执失败",ex);}
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> directInput(AiCapability capability,Map<String,Object> input){
        if(capability==AiCapability.RESUME_PARSING){Map<String,Object> direct=new java.util.LinkedHashMap<>();direct.put("resumes",input.getOrDefault("resumes",List.of()));direct.put("files",input.getOrDefault("files",List.of()));return direct;}
        if(capability==AiCapability.CANDIDATE_SCREENING){Map<String,Object> direct=new java.util.LinkedHashMap<>();direct.put("job_text",input.get("job_text"));direct.put("resumes",input.getOrDefault("resumes",List.of()));direct.put("files",input.getOrDefault("files",List.of()));direct.put("policy_source","RD_STANDARD");return direct;}
        if(capability!=AiCapability.JD_GENERATION)return input;
        if(input.get("slots") instanceof Map<?,?>)return input;
        Map<String,Object> source=new java.util.LinkedHashMap<>(input);
        Map<String,Object> slots=new java.util.LinkedHashMap<>();
        String title=text(source.get("title"));
        if(title.isBlank())throw new IllegalArgumentException("RD Direct JD requires a confirmed job title");
        slots.put("job_title",Map.of("status","answered","value",title));
        putTextSlot(slots,"location",source.get("location"));
        putTextSlot(slots,"employment_type",source.get("jobType"));
        putTextSlot(slots,"education_requirement",source.get("education"));
        String experience=text(source.get("experienceLevel"));
        if(!experience.isBlank()){Map<String,Object> value=new java.util.LinkedHashMap<>();value.put("minimum_years",null);value.put("text",experience);slots.put("experience_requirement",Map.of("status","answered","value",value));}
        String skills=text(source.get("skills"));
        if(!skills.isBlank()){
            List<Map<String,Object>> values=java.util.Arrays.stream(skills.split("[,，、;；/]+"))
                    .map(String::trim).filter(s->!s.isBlank()).map(s->{Map<String,Object> v=new java.util.LinkedHashMap<>();v.put("name",s);v.put("minimum_years",null);v.put("note",null);return v;}).toList();
            slots.put("required_skills",Map.of("status","answered","value",values));
        }
        String requirement=text(source.get("requirement"));
        if(!requirement.isBlank())slots.put("additional_requirements",Map.of("status","answered","value",List.of(requirement)));
        Map<String,Object> custom=new java.util.LinkedHashMap<>();
        String company=text(source.get("companyName"));if(!company.isBlank())custom.put("company_name",company);
        Map<String,Object> direct=new java.util.LinkedHashMap<>();direct.put("slots",slots);direct.put("custom_fields",custom);return direct;
    }
    private void putTextSlot(Map<String,Object> slots,String key,Object raw){String value=text(raw);if(!value.isBlank())slots.put(key,Map.of("status","answered","value",value));}
    private String text(Object value){return value==null?"":String.valueOf(value).trim();}

    private String canonicalHash(Map<String,Object> input,ExecutionContext context,AiCapability capability,String operation,String agentId){try{
        Map<String,Object> material=new java.util.LinkedHashMap<>();material.put("hash_contract_version","rd-input-hash-v1");material.put("agent_id",agentId);
        material.put("capability",capability.name().toLowerCase());material.put("operation",operation);material.put("business_task_id",context.businessTaskId().toString());
        material.put("route_config_version",context.agentRoute().routeConfigVersion());
        material.put("input_versions",stable(context.inputVersions()==null?List.of():context.inputVersions()));Object files=input.get("files");material.put("files",stable(files instanceof List<?>?files:List.of()));
        material.put("policy_snapshot",stable(policySnapshot(input, capability, agentId, context.policyDecision())));material.put("input",stable(input));
        var mapper=objectMapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(material));return java.util.HexFormat.of().formatHex(digest);
    }catch(Exception ex){throw new IllegalStateException("AI输入无法生成稳定摘要",ex);}}

    private Object stable(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> out=new java.util.TreeMap<>();
            for (var entry:map.entrySet()) {
                String key=String.valueOf(entry.getKey()), normalized=key.toLowerCase(java.util.Locale.ROOT);
                if (java.util.Set.of("download_url","downloadurl","file_url","fileurl","presigned_url","presignedurl","signed_url","signedurl","temporary_url","temporaryurl").contains(normalized)||normalized.contains("access_token")||normalized.equals("accesstoken")
                        ||normalized.equals("api_key")||normalized.endsWith("_secret")||normalized.endsWith("_timestamp")
                        ||normalized.equals("requested_at")) continue;
                if (normalized.endsWith("_base64")&&entry.getValue() instanceof String encoded) {
                    try { out.put(key.substring(0,key.length()-7)+"_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(encoded)))); }
                    catch (Exception ex) { throw new IllegalArgumentException("文件内容无法生成稳定摘要",ex); }
                } else out.put(key,stable(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(this::stable).toList();
        if(value instanceof Number number){java.math.BigDecimal decimal=new java.math.BigDecimal(number.toString()).stripTrailingZeros();return decimal.scale()<=0?decimal.toBigIntegerExact():decimal;}
        try { if (value != null && !value.getClass().isPrimitive() && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) return stable(objectMapper.convertValue(value,Map.class)); }
        catch (IllegalArgumentException ignored) { }
        return value;
    }

    private Object policySnapshot(Map<String,Object> input,AiCapability capability,String agentId,Object policyDecision){
        if(capability!=AiCapability.CANDIDATE_SCREENING)return Map.of();
        Map<String,Object> snapshot=new java.util.LinkedHashMap<>();
        if("complex_recruitment_agent".equals(agentId)) snapshot.put("policy_source","RD_STANDARD");
        else {snapshot.put("policy_source","IR_DIMENSIONS");snapshot.put("rules",input.get("screening_plan"));}
        return snapshot;
    }

    @Override
    public AiTask getTask(String aiTaskId, String actorId) {
        try {
            String raw = client.get()
                    .uri("/api/v1/tasks/{id}", aiTaskId)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-IR-Actor-Id", requiredActor(actorId))
                    .header("X-IR-Tenant-Id", requiredTenantForAgentTask(aiTaskId))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(raw);
            BossControlPlaneClient.AiAuthorization binding=ledger.authorizationForAgentTask(aiTaskId).orElse(null);
            AiCapability capability=binding==null?capability(node):capability(binding.capabilityCode());
            AiTask result = toAiTask(node, capability);
            if(binding!=null)ledger.observeTask(binding.tenantId(),aiTaskId,result.stateVersion(),result.status().name());
            retryCounts.put(aiTaskId, result.retryCount());
            ledger.authorizationForAgentTask(aiTaskId)
                    .map(BossControlPlaneClient.AiAuthorization::tenantId)
                    .ifPresent(tenantId -> ledger.recordRetryCount(tenantId, aiTaskId, result.retryCount()));
            if(java.util.Set.of(AiTaskStatus.QUEUED,AiTaskStatus.RUNNING,AiTaskStatus.CANCEL_REQUESTED,AiTaskStatus.WAITING_FOR_INPUT).contains(result.status())){
                BossControlPlaneClient.AiAuthorization auth=authorizations.get(aiTaskId);
                if(auth==null)auth=ledger.authorizationForAgentTask(aiTaskId).orElse(null);
                if(auth!=null){ledger.renewAcceptedLease(auth.tenantId(),aiTaskId);if(auth.agentId()!=null)boss.heartbeatAiExecution(auth,aiTaskId,result.status().name(),result.stateVersion());}
            }
            return result;
        } catch (Exception ex) {
            log.error("查询 AIAgentPlatform 任务失败 id={}", aiTaskId, ex);
            throw new RuntimeException("AIAgentPlatform 任务查询失败: " + ex.getMessage(), ex);
        }
    }

    private static AiCapability capability(String code){
        try{return AiCapability.valueOf(code.toUpperCase(java.util.Locale.ROOT));}
        catch(Exception ignored){throw new IllegalStateException("AIAgentPlatform 未返回可识别 capability");}
    }
    private static AiCapability capability(JsonNode task){
        String raw=task.path("capability").asText("");
        if(raw.isBlank())raw=task.path("capability_code").asText("");
        return capability(raw);
    }

    @Override
    public AiTask cancelTask(String aiTaskId, String idempotencyKey, String actorId) {
        try {
            String raw = client.post()
                    .uri("/api/v1/tasks/{id}/cancel", aiTaskId)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-Request-Id", downstreamRequestId("agent-cancel", idempotencyKey))
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-IR-Actor-Id", requiredActor(actorId))
                    .header("X-IR-Tenant-Id", requiredTenantForAgentTask(aiTaskId))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(raw);
            BossControlPlaneClient.AiAuthorization authorization = authorizations.get(aiTaskId);
            if (authorization == null) authorization = ledger.authorizationForAgentTask(aiTaskId).orElse(null);
            AiTask result=toAiTask(node,authorization==null?capability(node):capability(authorization.capabilityCode()));
            if (authorization != null) ledger.enqueueCancellation(authorization);
            return result;
        } catch (Exception ex) {
            log.error("取消 AIAgentPlatform 任务失败 id={}", aiTaskId, ex);
            throw new RuntimeException("AIAgentPlatform 任务取消失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public StructuredResult getStructuredResult(String aiTaskId, String actorId) {
        BossControlPlaneClient.AiAuthorization saved=ledger.authorizationForAgentTask(aiTaskId).orElse(null);
        if(saved!=null&&saved.agentId()!=null){
            JsonNode financial=boss.executionReservation(saved);String closeAt=financial.path("reconciliation_deadline").asText(null);
            if("RELEASE".equals(financial.path("final_decision").asText())||(closeAt!=null&&!Instant.parse(closeAt).isAfter(Instant.now())&&!financial.hasNonNull("final_decision")))throw new IllegalStateException("执行已到核对关闭期限，结果仅保留审计");
            ledger.beginResultProcessing(saved.tenantId(),aiTaskId);
        }
        try {
            String raw = client.get()
                    .uri("/api/v1/tasks/{id}/result", aiTaskId)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-IR-Actor-Id", requiredActor(actorId))
                    .header("X-IR-Tenant-Id", requiredTenantForAgentTask(aiTaskId))
                    .retrieve()
                    .body(String.class);
            StructuredResult result = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .forType(StructuredResult.class).readValue(raw);
            if(saved!=null&&saved.agentId()!=null&&(!"recruitment-result-v2".equals(result.resultContractVersion())||!aiTaskId.equals(result.aiTaskId())||!saved.attemptId().equals(result.attemptId())||!saved.attemptId().equals(result.executionId())||!saved.tenantId().equals(result.tenantId())||!saved.agentId().equals(result.agentId())||!saved.operation().equals(result.operation())||!saved.inputHash().equals(result.inputHash())||!saved.routeConfigVersion().equals(result.routeConfigVersion())||!saved.capabilityCode().equals(result.capability().name())))throw new IllegalStateException("结果与原授权执行绑定不一致");
            return result;
        } catch (Exception ex) {
            log.error("获取 AIAgentPlatform 任务结果失败 id={}", aiTaskId, ex);
            throw new RuntimeException("AIAgentPlatform 结果获取失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void confirmResultPersisted(String aiTaskId, StructuredResult result) {
        BossControlPlaneClient.AiAuthorization auth=authorizations.get(aiTaskId);
        if(auth==null)auth=ledger.authorizationForAgentTask(aiTaskId).orElse(null);
        var qualified=auth==null?null:ExecutionBillingQualification.evaluate(result,auth.capabilityCode(),auth.operation());
        if(qualified==null){holdForReconciliation(aiTaskId,"RESULT_BILLING_QUALIFICATION_UNVERIFIED");return;}
        confirmResultPersisted(aiTaskId,result,qualified.units(),qualified.validity(),qualified.reason());
    }

    @Override
    public void confirmResultPersisted(String aiTaskId, StructuredResult result, int billableUnits,
                                       String validity, String reason) {
        BossControlPlaneClient.AiAuthorization authorization = authorizations.get(aiTaskId);
        if (authorization == null) authorization = ledger.authorizationForAgentTask(aiTaskId).orElse(null);
        if (authorization == null) return;
        if(ledger.cancellationRequested(authorization.tenantId(),authorization.idempotencyKey())){
            if(!ledger.confirmCancelledExecution(authorization,aiTaskId,result))holdForReconciliation(aiTaskId,"CANCELLED_RESULT_UNVERIFIED");
            return;
        }
        var qualification=ExecutionBillingQualification.evaluate(result,authorization.capabilityCode(),authorization.operation());
        if(qualification==null||qualification.units()!=billableUnits){
            holdForReconciliation(aiTaskId,"RESULT_BILLING_QUALIFICATION_UNVERIFIED");
            return;
        }
        var usage = result.usage();
        String model = result.provenance() == null || result.provenance().modelId() == null
                ? authorization.modelId() : result.provenance().modelId();
        boolean tokenMetered = "TOKEN".equals(authorization.pricingSnapshot() == null ? null
                : authorization.pricingSnapshot().path("billing_method").asText(null));
        if (tokenMetered && usage == null) {
            holdForReconciliation(aiTaskId, "TOKEN_USAGE_MISSING");
            return;
        }
        int retryCount = ledger.retryCountForAgentTask(authorization.tenantId(), aiTaskId);
        ledger.enqueueUsage(authorization.idempotencyKey(), authorization, "SUCCEEDED", model,
                usage == null ? null : (long) usage.inputTokens(), usage == null ? null : (long) usage.outputTokens(), retryCount, aiTaskId,
                qualification.units(), qualification.validity(), qualification.reason());
        authorizations.remove(aiTaskId, authorization);
        retryCounts.remove(aiTaskId);
    }

    @Override
    public void confirmNoBillableResult(String aiTaskId, String reason) {
        BossControlPlaneClient.AiAuthorization authorization = authorizations.get(aiTaskId);
        if (authorization == null) authorization = ledger.authorizationForAgentTask(aiTaskId).orElse(null);
        if (authorization == null) return;
        ledger.enqueueRelease(authorization, "FAILED", reason, aiTaskId);
        authorizations.remove(aiTaskId, authorization);
        retryCounts.remove(aiTaskId);
    }

    @Override
    public void holdForReconciliation(String aiTaskId, String reason) {
        BossControlPlaneClient.AiAuthorization authorization = authorizations.get(aiTaskId);
        if (authorization == null) authorization = ledger.authorizationForAgentTask(aiTaskId).orElse(null);
        if (authorization == null || authorization.agentId() == null) return;
        java.time.Instant deadline=java.time.Instant.now().plusSeconds(reconciliationHoldSeconds);
        ledger.holdForReconciliation(authorization,reason,deadline);
        boss.holdAiExecutionV2(authorization, aiTaskId, reason,deadline);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public RouteDecision routeMessage(RouteAgentCommand command) {
        UUID tenant=UUID.fromString(command.tenantId()),actor=UUID.fromString(command.actorId()),business=UUID.fromString(command.businessTaskId());
        var prior=ledger.authorizationForExecution(tenant,command.idempotencyKey()).orElse(null);
        var policy=flow.evaluateAuthoritative(FlowCapability.CONVERSATION_ROUTE,new com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope(tenant,null,null,null),actor);
        var versions=List.of(new ExecutionContext.InputVersion("route_message",business.toString(),"frozen",com.intelligentrecruitment.shared.security.SecurityHashes.sha256(command.message())));
        ExecutionContext context=prior==null?flow.createExecutionContext(policy,business,command.idempotencyKey(),"route-message:"+business,versions,false):new ExecutionContext(prior.attemptId(),null,command.requestId(),command.traceId(),tenant,actor,business,command.idempotencyKey(),FlowCapability.CONVERSATION_ROUTE,"route-message:"+business,versions,policy,new ExecutionContext.DataHandling(false,"ephemeral",false),Instant.now(),new ExecutionContext.AgentRoute(prior.agentId(),prior.operation(),prior.routeConfigVersion(),"fixed_capability_rule"));
        var input=Map.<String,Object>of("message",command.message(),"allowed_capabilities",command.allowedCapabilities()==null?List.of():command.allowedCapabilities().stream().map(FlowCapability::value).toList());
        AiTask task=startTask(new StartAiTaskCommand(command.tenantId(),command.actorId(),command.businessTaskId(),command.idempotencyKey(),AiCapability.CONVERSATION_ROUTE,input,context));
        for(int attempt=0;attempt<120;attempt++){
            AiTask current=getTask(task.aiTaskId(),command.actorId());
            if(current.status()==AiTaskStatus.COMPLETED){var result=getStructuredResult(task.aiTaskId(),command.actorId());var decision=parseRouteDecision(result.data(),command);var auth=ledger.authorizationForAgentTask(task.aiTaskId()).orElseThrow();if(!ledger.confirmStandaloneResult(auth,task.aiTaskId(),result))holdForReconciliation(task.aiTaskId(),"STANDALONE_RESULT_UNVERIFIED");return decision;}
            if(current.status()==AiTaskStatus.FAILED||current.status()==AiTaskStatus.CANCELLED||current.status()==AiTaskStatus.RECONCILIATION_REQUIRED)throw new IllegalStateException("路由执行未提供可用结果");
            try{Thread.sleep(250);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("路由查询中断");}
        }
        throw new IllegalStateException("路由执行状态尚待确认");
    }

    private static int number(Object value) {
        return value instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }

    private static String downstreamRequestId(String operation, String idempotencyKey) {
        String current = MDC.get("request_id");
        if (current != null && !current.isBlank()) return current;
        return UUID.nameUUIDFromBytes(("ir-agent:" + operation + ":" + idempotencyKey)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    @Override
    public String continueConversation(ConversationAgentCommand command) {
        StructuredResult result = startAndAwait(command, AiCapability.CONVERSATION_CONTINUE,
                Map.of("conversation", command.messages(), "current_jd", command.jdDraft()));
        String message = result.data() == null ? "" : String.valueOf(result.data().getOrDefault("message", "")).trim();
        if (message.isBlank()) throw new IllegalStateException("对话能力未返回有效回复");
        return message;
    }

    @Override
    public StructuredResult reviseJdInPlace(ConversationAgentCommand command) {
        return startAndAwait(command, AiCapability.JD_IN_PLACE_REVISION,
                Map.of("conversation", command.messages(), "current_jd", command.jdDraft()));
    }

    private StructuredResult startAndAwait(ConversationAgentCommand command, AiCapability capability,
                                           Map<String, Object> input) {
        if (command.executionContext() == null) {
            throw new IllegalArgumentException("对话能力必须携带 BOSS 授权执行上下文");
        }
        AiTask task = startTask(new StartAiTaskCommand(command.tenantId(), command.actorId(),
                command.businessTaskId(), command.executionContext().idempotencyKey(), capability, input,
                command.executionContext()));
        for (int attempt = 0; attempt < 120; attempt++) {
            AiTask current = getTask(task.aiTaskId(), command.actorId());
            if (current.status() == AiTaskStatus.COMPLETED) {
                return getStructuredResult(current.aiTaskId(), command.actorId());
            }
            if (current.status() == AiTaskStatus.FAILED || current.status() == AiTaskStatus.CANCELLED) {
                throw new IllegalStateException(current.errorMessage() == null ? "AI 任务执行失败" : current.errorMessage());
            }
            try { Thread.sleep(250L); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 AI 任务完成时被中断", exception);
            }
        }
        throw new IllegalStateException("AI 任务仍在执行，请稍后重试");
    }

    // ==================== 结果解析 ====================

    @SuppressWarnings("unchecked")
    private RouteDecision parseRouteDecision(Map<String, Object> data, RouteAgentCommand command) {
        String kindStr = String.valueOf(data.getOrDefault("kind", "FALLBACK"));
        RouteDecision.Kind kind;
        try {
            kind = RouteDecision.Kind.fromValue(kindStr);
        } catch (Exception e) {
            kind = RouteDecision.Kind.FALLBACK;
        }

        String capStr = String.valueOf(data.getOrDefault("capability", ""));
        com.intelligentrecruitment.agentflow.domain.FlowCapability capability = null;
        if (!capStr.isBlank() && command.allowedCapabilities() != null) {
            try {
                capability = com.intelligentrecruitment.agentflow.domain.FlowCapability.fromValue(capStr.trim());
                if (!command.allowedCapabilities().contains(capability)) capability = null;
            } catch (Exception ignored) {}
        }

        String secondaryIntent = data.get("secondary_intent") == null ? null : String.valueOf(data.get("secondary_intent"));
        String opStr = data.get("operation") == null ? null : String.valueOf(data.get("operation"));
        RouteDecision.Operation operation = null;
        if (opStr != null && !opStr.isBlank() && !"null".equalsIgnoreCase(opStr)) {
            try { operation = RouteDecision.Operation.fromValue(opStr); } catch (Exception ignored) {}
        }

        double confidence = 0.5;
        Object confObj = data.get("confidence");
        if (confObj instanceof Number n) confidence = Math.max(0, Math.min(1, n.doubleValue()));

        String clarification = data.get("clarification") == null ? null : String.valueOf(data.get("clarification"));

        List<String> missingInputs = new ArrayList<>();
        Object mi = data.get("missing_inputs");
        if (mi instanceof List<?> list) {
            for (Object item : list) if (item != null) missingInputs.add(String.valueOf(item));
        }

        String actionStr = String.valueOf(data.getOrDefault("suggested_next_action", "NONE"));
        RouteDecision.SuggestedNextAction action;
        try {
            action = RouteDecision.SuggestedNextAction.fromValue(actionStr);
        } catch (Exception e) {
            action = RouteDecision.SuggestedNextAction.NONE;
        }

        return new RouteDecision(java.util.UUID.randomUUID(), kind, capability, secondaryIntent, operation,
                confidence, true, missingInputs, clarification, action, Instant.now());
    }

    // ==================== 映射辅助 ====================

    private AiTask toAiTask(JsonNode node, AiCapability capability) {
        String id = node.path("ai_task_id").asText();
        String businessTaskId = node.path("business_task_id").asText();
        AiTaskStatus status = mapStatus(node.path("status").asText());
        int completed = node.path("progress").path("completed").asInt(0);
        int total = node.path("progress").path("total").asInt(1);
        int percent = node.path("progress").path("percent").asInt(0);
        int retryCount = node.path("retry_count").asInt(0);
        Instant acceptedAt = Instant.parse(node.path("accepted_at").asText(Instant.now().toString()));
        String errorCode = node.path("error").path("code").asText(null);
        String errorMessage = node.path("error").path("message").asText(null);
        return new AiTask(id, businessTaskId, capability, status, completed, total, percent, retryCount,
                acceptedAt, errorCode, errorMessage,node.path("state_version").asLong(0));
    }

    private AiTaskStatus mapStatus(String s) {
        return com.intelligentrecruitment.aiplatform.domain.PlatformTaskStatusProjection.fromTaskRead(s);
    }

    private static String requiredActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("访问 AIAgentPlatform 任务必须提供 BOSS actor_id");
        }
        return actorId;
    }

    private String requiredTenantForAgentTask(String aiTaskId) {
        BossControlPlaneClient.AiAuthorization authorization = authorizations.get(aiTaskId);
        if (authorization == null) {
            authorization = ledger.authorizationForAgentTask(aiTaskId).orElse(null);
        }
        if (authorization == null || authorization.tenantId() == null) {
            throw new IllegalStateException("访问 AIAgentPlatform 任务必须找到持久化的 BOSS tenant_id");
        }
        return authorization.tenantId().toString();
    }
}
