package com.intelligentrecruitment.screening.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.agentflow.application.RecruitmentFlowCoordinator;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.candidates.application.PiiCipher;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.TenantAccessService;
import com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class ScreeningService {

    private static final List<String> SENSITIVE_RULE_TERMS = List.of(
            "性别", "年龄", "婚姻", "婚育", "生育", "民族", "种族", "宗教", "残疾", "户籍"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TenantAccessService tenantAccess;
    private final RecruitmentFlowCoordinator flowCoordinator;
    private final AiPlatformClient aiPlatform;
    private final ScreeningMatcher matcher;
    private final PiiCipher pii;
    private final long outboxLeaseSeconds;
    private final int maxInFlightPerRun;

    public ScreeningService(JdbcTemplate jdbc, ObjectMapper objectMapper, TenantAccessService tenantAccess,
                            RecruitmentFlowCoordinator flowCoordinator,
                            AiPlatformClient aiPlatform, ScreeningMatcher matcher, PiiCipher pii,
                            @Value("${app.phase5.outbox-lease-seconds:300}") long outboxLeaseSeconds,
                            @Value("${app.phase5.screening-max-in-flight-per-run:3}") int maxInFlightPerRun) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tenantAccess = tenantAccess;
        this.flowCoordinator = flowCoordinator;
        this.aiPlatform = aiPlatform;
        this.matcher = matcher;
        this.pii = pii;
        this.outboxLeaseSeconds = outboxLeaseSeconds;
        this.maxInFlightPerRun = Math.max(1, Math.min(maxInFlightPerRun, 10));
    }

    @Transactional
    public ScreeningPlanView createPlan(UUID userId, UUID tenantId, PlanInput input) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        if (input == null || input.jobId() == null) throw validation("请选择职位");
        JobRow job = job(tenantId, input.jobId());
        UUID recruitmentTaskId = recruitmentTask(tenantId, input.recruitmentTaskId());
        if (recruitmentTaskId != null) {
            Integer existing = jdbc.queryForObject("""
                    SELECT count(*) FROM screening_plans
                    WHERE tenant_id=? AND recruitment_task_id=? AND status='ACTIVE'
                    """, Integer.class, tenantId, recruitmentTaskId);
            if (existing != null && existing > 0) {
                throw new ApiException("SCREENING_PLAN_EXISTS", "每个招聘任务只能保留一个筛选方案，请直接保存修改", HttpStatus.CONFLICT);
            }
        }
        List<DimensionInput> dimensions = normalizeDimensions(input.dimensions());
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        String name = input.name() == null || input.name().isBlank() ? job.title() + "筛选方案" : input.name().trim();
        if (name.length() > 200) throw validation("筛选方案名称不能超过200字");
        jdbc.update("""
                INSERT INTO screening_plans
                (id,tenant_id,recruitment_task_id,job_id,name,status,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'ACTIVE',?,?,?)
                """, planId, scope.tenantId(), recruitmentTaskId, job.id(), name, userId, timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO screening_plan_versions
                (id,tenant_id,plan_id,version_number,rules_snapshot,created_by,created_at)
                VALUES (?,?,?,?,1,?::jsonb,?,?)
                """, versionId, scope.tenantId(), planId, json(dimensions), userId, timestamp(now));
        jdbc.update("UPDATE screening_plans SET current_version_id=? WHERE id=?", versionId, planId);
        audit(userId, scope, "SCREENING_PLAN_CREATED", "SCREENING_PLAN", planId);
        return planScoped(tenantId, planId);
    }

    @Transactional
    public ScreeningPlanView updatePlan(UUID userId, UUID tenantId, UUID planId, PlanUpdateInput input) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        ScreeningPlanView existing = planScoped(tenantId, planId);
        List<DimensionInput> dimensions = normalizeDimensions(input == null ? null : input.dimensions());
        JobRow job = job(tenantId, input == null || input.jobId() == null ? existing.jobId() : input.jobId());
        int version = existing.versionNumber() + 1;
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO screening_plan_versions
                (id,tenant_id,plan_id,version_number,rules_snapshot,created_by,created_at)
                VALUES (?,?,?,?,?,?::jsonb,?,?)
                """, versionId, scope.tenantId(), planId, version, json(dimensions), userId, timestamp(now));
        jdbc.update("UPDATE screening_plans SET current_version_id=?,job_id=?,updated_at=? WHERE id=? AND tenant_id=?",
                versionId, job.id(), timestamp(now), planId, tenantId);
        audit(userId, scope, "SCREENING_PLAN_UPDATED", "SCREENING_PLAN", planId);
        return planScoped(tenantId, planId);
    }

    public List<ScreeningPlanView> listPlans(UUID userId, UUID tenantId, UUID recruitmentTaskId) {
        tenantAccess.requireBusinessAccess(userId, tenantId);
        recruitmentTask(tenantId, recruitmentTaskId);
        String taskFilter = recruitmentTaskId == null ? "" : " AND p.recruitment_task_id=?";
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (recruitmentTaskId != null) params.add(recruitmentTaskId);
        return jdbc.query(planSelect() + " WHERE p.tenant_id=? AND p.status='ACTIVE'" + taskFilter
                        + " ORDER BY p.updated_at DESC",
                (rs, n) -> plan(rs), params.toArray());
    }

    @Transactional
    public ScreeningRunDetail run(UUID userId, UUID tenantId, String idempotencyKey, RunInput input) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        String key = requiredKey(idempotencyKey);
        if (input == null || input.planId() == null) throw validation("请选择筛选方案");
        List<UUID> candidateIds = safeCandidateIds(input.candidateIds());
        String scenario = "NORMAL";
        ScreeningPlanView plan = planScoped(tenantId, input.planId());
        JobRow job = job(tenantId, plan.jobId());
        List<CandidateRow> candidates = candidates(tenantId, candidateIds);
        if (candidates.size() != candidateIds.size()) throw validation("候选人不存在、未解析或不属于当前租户");
        String requestHash = SecurityHashes.sha256(plan.currentVersionId() + "|" + candidateIds.stream().sorted().toList()
                + "|" + scenario);
        ScreeningRunDetail existing = existingRun(tenantId, key, requestHash);
        if (existing != null) return existing;
        List<QueuedCandidate> queued = candidates.stream()
                .map(value -> new QueuedCandidate(value.id(), value.parseVersionId(), null, 1)).toList();
        return createQueuedRun(scope, userId, key, requestHash, job.id(), job.versionId(),
                plan.currentVersionId(), plan.recruitmentTaskId(), scenario, queued, null, null);
    }

    @Transactional
    public ScreeningRunDetail retryFailed(UUID userId, UUID tenantId, UUID originalRunId,
                                          String idempotencyKey) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        String key = requiredKey(idempotencyKey);
        RetryContext context = retryContext(tenantId, originalRunId);
        List<QueuedCandidate> failed = failedCandidates(tenantId, originalRunId);
        if (failed.isEmpty()) throw new ApiException("NO_FAILED_ITEMS", "没有可重试的失败候选人", HttpStatus.CONFLICT);
        String requestHash = SecurityHashes.sha256(originalRunId + "|RETRY_FAILED");
        ScreeningRunDetail existing = existingRun(tenantId, key, requestHash);
        if (existing != null) return existing;
        UUID rootRunId = context.rootRunId() == null ? originalRunId : context.rootRunId();
        return createQueuedRun(scope, userId, key, requestHash, context.jobId(), context.jobVersionId(),
                context.planVersionId(), context.recruitmentTaskId(), "NORMAL", failed, originalRunId, rootRunId);
    }

    private ScreeningRunDetail createQueuedRun(TenantScope scope, UUID userId, String key, String requestHash,
                                                UUID jobId, UUID jobVersionId,
                                                UUID planVersionId, UUID recruitmentTaskId, String scenario,
                                                List<QueuedCandidate> candidates, UUID parentRunId, UUID rootRunId) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        PolicyDecision policyDecision = flowCoordinator.evaluateAuthoritative(FlowCapability.CANDIDATE_SCREENING, scope, userId);
        List<ExecutionContext.InputVersion> inputVersions = new ArrayList<>();
        inputVersions.add(new ExecutionContext.InputVersion("job_version", jobVersionId.toString(), "frozen", null));
        inputVersions.add(new ExecutionContext.InputVersion("screening_plan_version", planVersionId.toString(), "frozen", null));
        candidates.forEach(candidate -> inputVersions.add(new ExecutionContext.InputVersion("resume_parse_version",
                candidate.parseVersionId().toString(), "frozen", null)));
        ExecutionContext executionContext = flowCoordinator.createExecutionContext(policyDecision, runId, key,
                "screening-run:" + runId, inputVersions, false);
        jdbc.update("""
                INSERT INTO screening_runs
                (id,tenant_id,recruitment_task_id,job_id,job_version_id,plan_version_id,parent_run_id,
                 root_run_id,status,progress,scenario,idempotency_key,request_hash,created_by,created_at,
                 policy_decision,execution_context)
                VALUES (?,?,?,?,?,?,?,?,?,'RUNNING',5,?,?,?,?,?::jsonb,?::jsonb)
                """, runId, scope.tenantId(), recruitmentTaskId, jobId, jobVersionId, planVersionId,
                parentRunId, rootRunId, scenario, key, requestHash, userId, timestamp(now), json(policyDecision),
                json(executionContext));
        for (QueuedCandidate candidate : candidates) {
            jdbc.update("""
                    INSERT INTO screening_run_items
                    (id,tenant_id,run_id,candidate_id,parse_version_id,source_run_item_id,
                     status,attempt_number,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?)
                    """, UUID.randomUUID(), scope.tenantId(), runId, candidate.candidateId(),
                    candidate.parseVersionId(), candidate.sourceRunItemId(), candidate.attemptNumber(),
                    timestamp(now), timestamp(now));
        }
        jdbc.update("""
                INSERT INTO outbox_events
                (id,aggregate_type,aggregate_id,event_type,payload,status,attempts,next_attempt_at,created_at)
                VALUES (?,'SCREENING_RUN',?,'SCREENING_RUN_REQUESTED',?::jsonb,'PENDING',0,?,?)
                """, UUID.randomUUID(), runId.toString(), json(Map.of("run_id", runId.toString())),
                timestamp(now), timestamp(now));
        audit(userId, scope, "SCREENING_RUN_REQUESTED", "SCREENING_RUN", runId);
        return runScoped(scope.tenantId(), runId);
    }

    @Transactional
    public OutboxClaim claimNextRun() {
        Instant now = Instant.now();
        List<OutboxClaim> rows = jdbc.query("""
                UPDATE outbox_events SET status='PROCESSING',attempts=attempts+1,next_attempt_at=?
                WHERE id=(
                    SELECT id FROM outbox_events
                    WHERE event_type='SCREENING_RUN_REQUESTED'
                      AND ((status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                        OR (status='PROCESSING' AND next_attempt_at<=?))
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                RETURNING id,aggregate_id,attempts
                """, (rs, n) -> new OutboxClaim(rs.getObject("id", UUID.class),
                UUID.fromString(rs.getString("aggregate_id")), rs.getInt("attempts")),
                timestamp(now.plus(outboxLeaseSeconds, ChronoUnit.SECONDS)), timestamp(now), timestamp(now));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public boolean prepareRun(UUID runId) {
        List<ExecutionRow> rows = executionRows(runId, true);
        if (rows.isEmpty() || !"RUNNING".equals(rows.getFirst().status())) return false;
        // Each candidate has an independent AI task. The run merely coordinates
        // the batch so a slow model response cannot block other positions.
        jdbc.update("UPDATE screening_runs SET progress=GREATEST(progress,10) WHERE id=? AND status='RUNNING'", runId);
        return true;
    }

    @Transactional
    public boolean processNextItem(UUID runId) {
        List<ExecutionRow> rows = executionRows(runId, true);
        if (rows.isEmpty() || !"RUNNING".equals(rows.getFirst().status())) return false;
        ExecutionRow run = rows.getFirst();
        Integer inFlight = jdbc.queryForObject("""
                SELECT count(*) FROM screening_run_items
                WHERE run_id=? AND status='PROCESSING'
                """, Integer.class, runId);
        boolean canStartAnother = (inFlight == null ? 0 : inFlight) < maxInFlightPerRun;
        List<ItemExecutionRow> items = jdbc.query("""
                SELECT i.id,i.candidate_id,i.parse_version_id,i.status,i.provider_task_id,pv.headline,pv.years_experience,
                       pv.highest_education,pv.skills::text,pv.summary,pv.work_experience::text,pv.raw_text
                FROM screening_run_items i
                JOIN resume_parse_versions pv ON pv.id=i.parse_version_id
                WHERE i.run_id=? AND i.tenant_id=? AND i.status=""" + (canStartAnother ? "'PENDING'" : "'PROCESSING'") + """
                ORDER BY i.created_at,i.id FOR UPDATE OF i SKIP LOCKED LIMIT 1
                """, (rs, n) -> new ItemExecutionRow(rs.getObject("id", UUID.class),
                rs.getObject("candidate_id", UUID.class), rs.getObject("parse_version_id", UUID.class), rs.getString("status"), rs.getString("provider_task_id"),
                rs.getString("headline"), rs.getInt("years_experience"), rs.getString("highest_education"),
                strings(rs.getString("skills")), rs.getString("summary"), rs.getString("work_experience"),
                pii.decryptIfEncrypted(rs.getString("raw_text"))), runId, run.tenantId());
        if (items.isEmpty() && canStartAnother) {
            // All candidates have been submitted. Poll one outstanding AI task.
            items = jdbc.query("""
                    SELECT i.id,i.candidate_id,i.parse_version_id,i.status,i.provider_task_id,pv.headline,pv.years_experience,
                           pv.highest_education,pv.skills::text,pv.summary,pv.work_experience::text,pv.raw_text
                    FROM screening_run_items i
                    JOIN resume_parse_versions pv ON pv.id=i.parse_version_id
                    WHERE i.run_id=? AND i.tenant_id=? AND i.status='PROCESSING'
                    ORDER BY i.created_at,i.id FOR UPDATE OF i SKIP LOCKED LIMIT 1
                    """, (rs, n) -> new ItemExecutionRow(rs.getObject("id", UUID.class),
                    rs.getObject("candidate_id", UUID.class), rs.getObject("parse_version_id", UUID.class), rs.getString("status"), rs.getString("provider_task_id"),
                    rs.getString("headline"), rs.getInt("years_experience"), rs.getString("highest_education"),
                    strings(rs.getString("skills")), rs.getString("summary"), rs.getString("work_experience"),
                    pii.decryptIfEncrypted(rs.getString("raw_text"))), runId, run.tenantId());
        }
        if (items.isEmpty()) return false;
        ItemExecutionRow item = items.getFirst();
        Integer processed = jdbc.queryForObject("""
                SELECT count(*) FROM screening_run_items WHERE run_id=? AND status IN ('SUCCEEDED','FAILED','CANCELLED')
                """, Integer.class, runId);
        int index = processed == null ? 0 : processed;
        Instant now = Instant.now();
        if ("PENDING".equals(item.status())) {
            try {
                ExecutionContext itemContext = itemExecutionContext(executionContext(run.executionContext()), item.id(), item.parseVersionId());
                var aiTask = aiPlatform.startTask(new StartAiTaskCommand(run.tenantId().toString(),
                        run.createdBy().toString(), item.id().toString(), "screening-item:" + item.id(),
                        AiCapability.CANDIDATE_SCREENING,
                        screeningInput(run, item), itemContext));
                jdbc.update("UPDATE screening_run_items SET status='PROCESSING',provider_task_id=?,updated_at=? WHERE id=?",
                        aiTask.aiTaskId(), timestamp(now), item.id());
                if (aiTask.status() == com.intelligentrecruitment.aiplatform.domain.AiTaskStatus.COMPLETED) {
                    persistAiResult(run, item, aiPlatform.getStructuredResult(aiTask.aiTaskId(), run.createdBy().toString()), now);
                }
            } catch (RuntimeException exception) { failItem(item, exception, now); }
        } else {
            try {
                var aiTask = item.providerTaskId() == null ? null : aiPlatform.getTask(item.providerTaskId(), run.createdBy().toString());
                if (aiTask == null || aiTask.status() == com.intelligentrecruitment.aiplatform.domain.AiTaskStatus.FAILED
                        || aiTask.status() == com.intelligentrecruitment.aiplatform.domain.AiTaskStatus.CANCELLED) {
                    failItem(item, new ApiException("AI_PROVIDER_UNAVAILABLE", "AI Platform 未返回可用结果", HttpStatus.BAD_GATEWAY), now);
                } else if (aiTask.status() == com.intelligentrecruitment.aiplatform.domain.AiTaskStatus.COMPLETED) {
                    persistAiResult(run, item, aiPlatform.getStructuredResult(aiTask.aiTaskId(), run.createdBy().toString()), now);
                } else {
                    return false;
                }
            } catch (RuntimeException exception) { failItem(item, exception, now); }
        }
        Integer completed = jdbc.queryForObject("""
                SELECT count(*) FROM screening_run_items WHERE run_id=? AND status IN ('SUCCEEDED','FAILED','CANCELLED')
                """, Integer.class, runId);
        int progress = 15 + (int) Math.floor((completed == null ? 0 : completed) * 75.0 / run.totalItems());
        jdbc.update("UPDATE screening_runs SET progress=? WHERE id=? AND status='RUNNING'", progress, runId);
        return true;
    }

    @Transactional
    public void finalizeRun(UUID runId) {
        List<ExecutionRow> rows = executionRows(runId, true);
        if (rows.isEmpty() || !"RUNNING".equals(rows.getFirst().status())) return;
        ExecutionRow run = rows.getFirst();
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM screening_run_items WHERE run_id=? AND status IN ('PENDING','PROCESSING')",
                Integer.class, runId);
        if (pending != null && pending > 0) return;
        Integer succeeded = jdbc.queryForObject("SELECT count(*) FROM screening_run_items WHERE run_id=? AND status='SUCCEEDED'",
                Integer.class, runId);
        int successCount = succeeded == null ? 0 : succeeded;
        String status = successCount == run.totalItems() ? "COMPLETED" : successCount == 0 ? "FAILED" : "PARTIAL_FAILED";
        jdbc.update("""
                UPDATE screening_runs SET status=?,progress=100,completed_at=?
                WHERE id=? AND status='RUNNING'
                """, status, timestamp(Instant.now()), runId);
        auditExecution(run, "SCREENING_RUN_" + status);
    }

    public List<UUID> runningRunIds() {
        return jdbc.query("""
                SELECT id FROM screening_runs
                WHERE status='RUNNING'
                ORDER BY created_at
                LIMIT 50
                """, (rs, n) -> rs.getObject(1, UUID.class));
    }

    @Transactional
    public void completeOutbox(UUID eventId) {
        jdbc.update("UPDATE outbox_events SET status='SENT',sent_at=? WHERE id=?",
                timestamp(Instant.now()), eventId);
    }

    @Transactional
    public void failOutbox(OutboxClaim claim, String message) {
        if (claim.attempts() < 3) {
            jdbc.update("""
                    UPDATE outbox_events SET status='PENDING',next_attempt_at=? WHERE id=?
                    """, timestamp(Instant.now().plus(claim.attempts(), ChronoUnit.SECONDS)), claim.eventId());
            return;
        }
        List<ExecutionRow> rows = executionRows(claim.runId(), true);
        if (!rows.isEmpty() && "RUNNING".equals(rows.getFirst().status())) {
            ExecutionRow run = rows.getFirst();
            jdbc.update("""
                    UPDATE screening_run_items SET status='FAILED',error_code='SCREENING_WORKER_FAILED',updated_at=?
                    WHERE run_id=? AND status='PENDING'
                    """, timestamp(Instant.now()), run.id());
            jdbc.update("""
                    UPDATE screening_runs SET status='FAILED',progress=100,completed_at=? WHERE id=?
                    """, timestamp(Instant.now()), run.id());
            auditExecution(run, "SCREENING_RUN_WORKER_FAILED");
        }
        jdbc.update("UPDATE outbox_events SET status='FAILED',sent_at=?,payload=jsonb_set(payload,'{error}',to_jsonb(CAST(? AS text))) WHERE id=?",
                timestamp(Instant.now()), safeError(message), claim.eventId());
    }

    @Transactional
    public void failRun(UUID runId, String message) {
        List<ExecutionRow> rows = executionRows(runId, true);
        if (rows.isEmpty() || !"RUNNING".equals(rows.getFirst().status())) return;
        ExecutionRow run = rows.getFirst();
        jdbc.update("""
                UPDATE screening_run_items SET status='FAILED',error_code='SCREENING_WORKER_FAILED',updated_at=?
                WHERE run_id=? AND status='PENDING'
                """, timestamp(Instant.now()), run.id());
        jdbc.update("""
                UPDATE screening_runs SET status='FAILED',progress=100,completed_at=? WHERE id=?
                """, timestamp(Instant.now()), run.id());
        auditExecution(run, "SCREENING_RUN_WORKER_FAILED");
    }

    @Transactional
    public ScreeningRunDetail cancel(UUID userId, UUID tenantId, UUID runId, String idempotencyKey) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        List<CancelRow> rows = jdbc.query("""
                SELECT status,provider_task_id FROM screening_runs
                WHERE id=? AND tenant_id=? FOR UPDATE
                """, (rs, n) -> new CancelRow(rs.getString(1), rs.getString(2)), runId, tenantId);
        if (rows.isEmpty()) throw new ApiException("SCREENING_RUN_NOT_FOUND", "筛选任务不存在", HttpStatus.NOT_FOUND);
        CancelRow row = rows.getFirst();
        if ("CANCELLED".equals(row.status())) return runScoped(tenantId, runId);
        if (!"RUNNING".equals(row.status())) {
            throw new ApiException("SCREENING_RUN_TERMINAL", "筛选任务已结束，不能取消", HttpStatus.CONFLICT);
        }
        if (row.providerTaskId() != null) aiPlatform.cancelTask(row.providerTaskId(), requiredKey(idempotencyKey), userId.toString());
        jdbc.query("""
                SELECT provider_task_id FROM screening_run_items
                WHERE run_id=? AND tenant_id=? AND status='PROCESSING' AND provider_task_id IS NOT NULL
                """, (rs, n) -> rs.getString(1), runId, tenantId)
                .forEach(taskId -> aiPlatform.cancelTask(taskId, requiredKey(idempotencyKey), userId.toString()));
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE screening_run_items SET status='CANCELLED',updated_at=?
                WHERE run_id=? AND tenant_id=? AND status IN ('PENDING','PROCESSING')
                """, timestamp(now), runId, tenantId);
        jdbc.update("""
                UPDATE screening_runs SET status='CANCELLED',progress=100,completed_at=?
                WHERE id=? AND tenant_id=?
                """, timestamp(now), runId, tenantId);
        jdbc.update("""
                UPDATE outbox_events SET status='SENT',sent_at=?
                WHERE event_type='SCREENING_RUN_REQUESTED' AND aggregate_id=? AND status IN ('PENDING','PROCESSING')
                """, timestamp(now), runId.toString());
        audit(userId, scope, "SCREENING_RUN_CANCELLED", "SCREENING_RUN", runId);
        return runScoped(tenantId, runId);
    }

    public List<ScreeningRunSummary> listRuns(UUID userId, UUID tenantId, UUID recruitmentTaskId) {
        tenantAccess.requireBusinessAccess(userId, tenantId);
        recruitmentTask(tenantId, recruitmentTaskId);
        String taskFilter = recruitmentTaskId == null ? "" : " AND r.recruitment_task_id=?";
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (recruitmentTaskId != null) params.add(recruitmentTaskId);
        return jdbc.query("""
                SELECT r.id,r.job_id,j.title AS job_title,r.status,r.progress,r.created_at,r.completed_at,r.recruitment_task_id,
                       count(i.id) AS total_items,count(i.id) FILTER (WHERE i.status='SUCCEEDED') AS succeeded_items
                FROM screening_runs r JOIN jobs j ON j.id=r.job_id
                JOIN screening_run_items i ON i.run_id=r.id WHERE r.tenant_id=?""" + taskFilter + """
                 GROUP BY r.id,j.title ORDER BY r.created_at DESC LIMIT 100
                """, (rs, n) -> new ScreeningRunSummary(rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getString("job_title"), rs.getString("status"),
                rs.getInt("progress"), rs.getInt("total_items"), rs.getInt("succeeded_items"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at") == null ? null :
                rs.getTimestamp("completed_at").toInstant(), rs.getObject("recruitment_task_id", UUID.class)), params.toArray());
    }

    public ScreeningRunDetail getRun(UUID userId, UUID tenantId, UUID runId) {
        tenantAccess.requireBusinessAccess(userId, tenantId);
        return runScoped(tenantId, runId);
    }

    private ScreeningRunDetail runScoped(UUID tenantId, UUID runId) {
        List<RunRow> runs = jdbc.query("""
                SELECT r.id,r.job_id,j.title AS job_title,p.id AS plan_id,p.name AS plan_name,r.status,r.progress,
                       r.scenario,r.created_at,r.completed_at,r.recruitment_task_id
                FROM screening_runs r JOIN jobs j ON j.id=r.job_id
                JOIN screening_plan_versions pv ON pv.id=r.plan_version_id JOIN screening_plans p ON p.id=pv.plan_id
                WHERE r.id=? AND r.tenant_id=?
                """, (rs, n) -> new RunRow(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getString("job_title"), rs.getObject("plan_id", UUID.class), rs.getString("plan_name"),
                rs.getString("status"), rs.getInt("progress"), rs.getString("scenario"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at") == null ? null :
                rs.getTimestamp("completed_at").toInstant(), rs.getObject("recruitment_task_id", UUID.class)), runId, tenantId);
        if (runs.isEmpty()) throw new ApiException("SCREENING_RUN_NOT_FOUND", "筛选任务不存在", HttpStatus.NOT_FOUND);
        List<ScreeningItemView> items = jdbc.query("""
                SELECT i.id,i.candidate_id,c.full_name_ciphertext,i.status,i.error_code,i.attempt_number,
                       r.score,r.level,r.matched_points::text,r.unmatched_points::text,r.negotiable_points::text,
                       r.missing_information::text,r.risks::text,r.evidence::text
                FROM screening_run_items i JOIN candidates c ON c.id=i.candidate_id
                LEFT JOIN screening_results r ON r.run_item_id=i.id
                WHERE i.run_id=? AND i.tenant_id=? ORDER BY r.score DESC NULLS LAST,c.display_name_masked
                """, (rs, n) -> new ScreeningItemView(rs.getObject("id", UUID.class),
                rs.getObject("candidate_id", UUID.class), pii.decrypt(rs.getString("full_name_ciphertext")),
                rs.getString("status"), rs.getString("error_code"), rs.getInt("attempt_number"),
                rs.getObject("score") == null ? null : rs.getInt("score"), rs.getString("level"),
                strings(rs.getString("matched_points")), strings(rs.getString("unmatched_points")),
                strings(rs.getString("negotiable_points")), strings(rs.getString("missing_information")),
                strings(rs.getString("risks")), strings(rs.getString("evidence"))), runId, tenantId);
        RunRow run = runs.getFirst();
        String settlementStatus = settlementStatusForRun(tenantId, runId);
        return new ScreeningRunDetail(run.id(), run.jobId(), run.jobTitle(), run.planId(), run.planName(),
                run.status(), run.progress(), run.scenario(), items, run.createdAt(), run.completedAt(),
                run.recruitmentTaskId(), settlementStatus);
    }

    private String settlementStatusForRun(UUID tenantId, UUID runId) {
        List<String> statuses = jdbc.query("""
                SELECT DISTINCT er.status
                FROM screening_run_items i
                LEFT JOIN ai_execution_records er
                  ON er.tenant_id=i.tenant_id AND er.business_task_id=i.id::text
                WHERE i.run_id=? AND i.tenant_id=? AND er.status IS NOT NULL
                """, (rs, n) -> rs.getString(1), runId, tenantId);
        if (statuses.isEmpty()) return null;
        if (statuses.contains("SETTLEMENT_FAILED")) return "SETTLEMENT_FAILED";
        if (statuses.contains("USAGE_PENDING") || statuses.contains("CANCELLATION_PENDING")) return "PENDING";
        if (statuses.stream().allMatch("SETTLED"::equals)) return "SETTLED";
        if (statuses.stream().allMatch("CANCELLED"::equals)) return "CANCELLED";
        return statuses.stream().anyMatch(status -> "AUTHORIZED".equals(status) || "AGENT_ACCEPTED".equals(status))
                ? "EXECUTING" : statuses.getFirst();
    }

    private ScreeningPlanView planScoped(UUID tenantId, UUID planId) {
        List<ScreeningPlanView> rows = jdbc.query(planSelect() + " WHERE p.id=? AND p.tenant_id=? AND p.status='ACTIVE'",
                (rs, n) -> plan(rs), planId, tenantId);
        if (rows.isEmpty()) throw new ApiException("SCREENING_PLAN_NOT_FOUND", "筛选方案不存在", HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private static String planSelect() {
        return """
                SELECT p.id,p.tenant_id,p.recruitment_task_id,p.job_id,j.title AS job_title,p.current_version_id,
                       pv.version_number,pv.rules_snapshot::text,p.name,p.status,p.created_at,p.updated_at
                FROM screening_plans p JOIN jobs j ON j.id=p.job_id
                JOIN screening_plan_versions pv ON pv.id=p.current_version_id
                """;
    }

    private ScreeningPlanView plan(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScreeningPlanView(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("recruitment_task_id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getString("job_title"), rs.getObject("current_version_id", UUID.class),
                rs.getInt("version_number"), dimensions(rs.getString("rules_snapshot")), rs.getString("name"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private JobRow job(UUID tenantId, UUID jobId) {
        List<JobRow> rows = jdbc.query("""
                SELECT id,current_version_id,title,skills,experience_level,education,requirements
                FROM jobs WHERE id=? AND tenant_id=? AND status IN ('ACTIVE','DRAFT') AND current_version_id IS NOT NULL
                """, (rs, n) -> new JobRow(rs.getObject("id", UUID.class),
                rs.getObject("current_version_id", UUID.class), rs.getString("title"), rs.getString("skills"),
                rs.getString("experience_level"), rs.getString("education"), rs.getString("requirements")),
                jobId, tenantId);
        if (rows.isEmpty()) throw new ApiException("JOB_NOT_FOUND", "职位不存在或没有可用版本", HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private UUID recruitmentTask(UUID tenantId, UUID recruitmentTaskId) {
        if (recruitmentTaskId == null) return null;
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM recruitment_tasks WHERE id=? AND tenant_id=?
                """, Integer.class, recruitmentTaskId, tenantId);
        if (count == null || count == 0) {
            throw new ApiException("RECRUITMENT_TASK_NOT_FOUND", "招聘任务不存在或不属于当前租户", HttpStatus.NOT_FOUND);
        }
        return recruitmentTaskId;
    }

    private List<CandidateRow> candidates(UUID tenantId, List<UUID> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> params = new ArrayList<>(); params.add(tenantId); params.addAll(ids);
        return jdbc.query("""
                SELECT c.id,c.current_parse_version_id,pv.headline,pv.years_experience,pv.highest_education,pv.skills::text
                FROM candidates c JOIN resume_parse_versions pv ON pv.id=c.current_parse_version_id
                WHERE c.tenant_id=? AND c.status='ACTIVE' AND c.id IN (""" + placeholders + ")",
                (rs, n) -> new CandidateRow(rs.getObject("id", UUID.class),
                rs.getObject("current_parse_version_id", UUID.class), rs.getString("headline"),
                rs.getInt("years_experience"), rs.getString("highest_education"), strings(rs.getString("skills"))),
                params.toArray());
    }

    private List<DimensionInput> normalizeDimensions(List<DimensionInput> dimensions) {
        List<DimensionInput> value = dimensions == null || dimensions.isEmpty() ? defaultDimensions() : dimensions;
        if (value.size() > 12) throw validation("筛选维度不能超过12项");
        int total = 0;
        Set<String> names = new LinkedHashSet<>();
        List<DimensionInput> clean = new ArrayList<>();
        for (DimensionInput item : value) {
            if (item == null || item.name() == null || item.name().isBlank()) throw validation("筛选维度名称不能为空");
            String name = item.name().trim();
            if (!names.add(name)) throw validation("筛选维度不能重复");
            if (item.weight() < 0 || item.weight() > 100) throw validation("筛选权重必须在0到100之间");
            total += item.weight();
            String description = item.description() == null ? "" : item.description().trim();
            String exclusion = item.exclusionRule() == null ? "" : item.exclusionRule().trim();
            String missingPolicy = item.missingPolicy() == null || item.missingPolicy().isBlank()
                    ? "REVIEW" : item.missingPolicy().trim().toUpperCase(Locale.ROOT);
            if (!List.of("REVIEW", "NEGOTIABLE", "IGNORE").contains(missingPolicy)) {
                throw validation("缺失信息策略只能是人工复核、可协商或忽略");
            }
            rejectSensitiveRule(name + " " + description + " " + exclusion);
            clean.add(new DimensionInput(name, item.weight(), description, item.required(), exclusion, missingPolicy));
        }
        if (total != 100) throw validation("筛选维度权重合计必须为100");
        return clean;
    }

    private static List<DimensionInput> defaultDimensions() {
        return List.of(new DimensionInput("基本信息", 10, "地点、到岗时间等基础条件", false, "", "REVIEW"),
                new DimensionInput("教育背景", 10, "学历与专业背景", false, "", "REVIEW"),
                new DimensionInput("职业履历", 25, "岗位相关经历与稳定性", false, "", "REVIEW"),
                new DimensionInput("专业技能", 30, "核心技能和技术深度", true, "", "REVIEW"),
                new DimensionInput("项目成果", 15, "可验证的项目结果", false, "", "REVIEW"),
                new DimensionInput("求职动机", 10, "岗位意愿和发展匹配", false, "", "NEGOTIABLE"));
    }

    private static void rejectSensitiveRule(String value) {
        for (String term : SENSITIVE_RULE_TERMS) {
            if (value.contains(term)) throw validation("筛选规则不得使用性别、年龄、婚育等敏感属性");
        }
    }

    private ScreeningRunDetail existingRun(UUID tenantId, String key, String requestHash) {
        List<RunRef> existing = jdbc.query("""
                SELECT id,request_hash FROM screening_runs WHERE tenant_id=? AND idempotency_key=?
                """, (rs, n) -> new RunRef(rs.getObject(1, UUID.class), rs.getString(2)), tenantId, key);
        if (existing.isEmpty()) return null;
        if (!existing.getFirst().requestHash().equals(requestHash)) throw idempotencyConflict();
        return runScoped(tenantId, existing.getFirst().id());
    }

    private RetryContext retryContext(UUID tenantId, UUID originalRunId) {
        List<RetryContext> rows = jdbc.query("""
                SELECT r.job_id,r.job_version_id,r.plan_version_id,p.id AS plan_id,r.recruitment_task_id,r.root_run_id,r.status
                FROM screening_runs r
                JOIN screening_plan_versions pv ON pv.id=r.plan_version_id
                JOIN screening_plans p ON p.id=pv.plan_id
                WHERE r.id=? AND r.tenant_id=?
                """, (rs, n) -> new RetryContext(rs.getObject("job_id", UUID.class),
                rs.getObject("job_version_id", UUID.class), rs.getObject("plan_version_id", UUID.class),
                rs.getObject("plan_id", UUID.class), rs.getObject("recruitment_task_id", UUID.class), rs.getObject("root_run_id", UUID.class),
                rs.getString("status")), originalRunId, tenantId);
        if (rows.isEmpty()) throw new ApiException("SCREENING_RUN_NOT_FOUND", "筛选任务不存在", HttpStatus.NOT_FOUND);
        if ("RUNNING".equals(rows.getFirst().status())) {
            throw new ApiException("SCREENING_RUN_NOT_TERMINAL", "筛选任务尚未结束，不能重试", HttpStatus.CONFLICT);
        }
        return rows.getFirst();
    }

    private List<QueuedCandidate> failedCandidates(UUID tenantId, UUID originalRunId) {
        return jdbc.query("""
                SELECT id,candidate_id,parse_version_id,attempt_number
                FROM screening_run_items
                WHERE run_id=? AND tenant_id=? AND status='FAILED'
                ORDER BY created_at,id
                """, (rs, n) -> new QueuedCandidate(rs.getObject("candidate_id", UUID.class),
                rs.getObject("parse_version_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getInt("attempt_number") + 1), originalRunId, tenantId);
    }

    private List<ExecutionRow> executionRows(UUID runId, boolean lock) {
        return jdbc.query("""
                SELECT r.id,r.tenant_id,r.job_version_id,r.plan_version_id,r.provider_task_id,
                       r.status,r.scenario,r.created_by,jv.snapshot::text,
                       pv.rules_snapshot::text,r.execution_context::text,
                       (SELECT count(*) FROM screening_run_items i WHERE i.run_id=r.id) AS total_items
                FROM screening_runs r
                JOIN job_versions jv ON jv.id=r.job_version_id
                JOIN screening_plan_versions pv ON pv.id=r.plan_version_id
                WHERE r.id=?
                """ + (lock ? " FOR UPDATE OF r" : ""), (rs, n) -> new ExecutionRow(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("job_version_id", UUID.class), rs.getObject("plan_version_id", UUID.class),
                rs.getString("provider_task_id"), rs.getString("status"), rs.getString("scenario"),
                rs.getObject("created_by", UUID.class), rs.getString("snapshot"), rs.getString("rules_snapshot"),
                rs.getString("execution_context"), rs.getInt("total_items")), runId);
    }

    private ScreeningMatcher.FrozenJob jobFromSnapshot(String snapshot) {
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            JsonNode job = root.has("job") ? root.get("job") : root;
            return new ScreeningMatcher.FrozenJob(text(job, "title"), text(job, "skills"),
                    text(job, "experienceLevel"), text(job, "education"), text(job, "requirements"));
        } catch (JsonProcessingException exception) {
            throw new ApiException("JOB_SNAPSHOT_INVALID", "职位版本快照无法读取", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Map<String, Object> screeningInput(ExecutionRow run, ItemExecutionRow item) {
        ScreeningMatcher.FrozenJob job = jobFromSnapshot(run.jobSnapshot());
        List<Map<String, Object>> plan = dimensions(run.rulesSnapshot()).stream().map(dimension -> Map.<String, Object>of(
                "name", dimension.name(), "weight", dimension.weight(), "description", nullToEmpty(dimension.description()),
                "required", dimension.required(), "exclusion_rule", nullToEmpty(dimension.exclusionRule()),
                "missing_policy", nullToEmpty(dimension.missingPolicy()))).toList();
        return Map.of(
                "job", Map.of("title", nullToEmpty(job.title()), "skills", nullToEmpty(job.skills()),
                        "experience_level", nullToEmpty(job.experienceLevel()), "education", nullToEmpty(job.education()),
                        "requirements", nullToEmpty(job.requirements())),
                "screening_plan", plan,
                "candidate", Map.of("headline", nullToEmpty(item.headline()), "years_experience", item.yearsExperience(),
                        "education", nullToEmpty(item.education()), "skills", String.join("、", item.skills()),
                        "summary", nullToEmpty(item.summary()), "work_experience", nullToEmpty(item.workExperience()),
                        "resume_text", nullToEmpty(item.rawText()))
        );
    }

    private void persistAiResult(ExecutionRow run, ItemExecutionRow item, StructuredResult output, Instant now) {
        if (output.capability() != FlowCapability.CANDIDATE_SCREENING || output.data() == null) {
            throw new ApiException("AI_CONTRACT_INVALID", "AI Platform 未返回简历筛选结果", HttpStatus.BAD_GATEWAY);
        }
        int score = screeningScore(output.data().get("score"));
        String level = screeningLevel(output.data().get("level"), score);
        List<String> risks = new ArrayList<>(stringList(output.data().get("risks")));
        if (risks.stream().noneMatch(value -> value.contains("不得自动淘汰"))) {
            risks.add("AI 评分仅供招聘人员辅助判断，不得自动淘汰候选人");
        }
        ScreeningMatcher.MatchResult result = new ScreeningMatcher.MatchResult(score, level,
                stringList(output.data().get("matched_points")), stringList(output.data().get("unmatched_points")),
                stringList(output.data().get("negotiable_points")), stringList(output.data().get("missing_information")), risks,
                stringList(output.data().get("evidence")));
        persistResult(run, item, result, Map.of("source", "AI_PLATFORM", "result", output.data()), now);
    }

    private void failItem(ItemExecutionRow item, RuntimeException exception, Instant now) {
        String code = exception instanceof ApiException api ? api.code() : "AI_PROVIDER_UNAVAILABLE";
        jdbc.update("UPDATE screening_run_items SET status='FAILED',error_code=?,updated_at=? WHERE id=?",
                code, timestamp(now), item.id());
    }

    private void persistResult(ExecutionRow run, ItemExecutionRow item, ScreeningMatcher.MatchResult result,
                               Map<String, Object> snapshot, Instant now) {
        jdbc.update("""
                INSERT INTO screening_results
                (id,tenant_id,run_item_id,score,level,matched_points,unmatched_points,
                 negotiable_points,missing_information,risks,evidence,result_snapshot,created_at)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)
                """, UUID.randomUUID(), run.tenantId(), item.id(), result.score(), result.level(),
                protectedJson(result.matched()), protectedJson(result.unmatched()), protectedJson(result.negotiable()), protectedJson(result.missing()),
                protectedJson(result.risks()), protectedJson(result.evidence()), protectedJson(snapshot), timestamp(now));
        jdbc.update("UPDATE screening_run_items SET status='SUCCEEDED',error_code=NULL,updated_at=? WHERE id=?",
                timestamp(now), item.id());
    }

    private static int screeningScore(Object value) {
        try {
            int score = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            if (score < 0 || score > 100) throw new NumberFormatException();
            return score;
        } catch (RuntimeException exception) {
            throw new ApiException("AI_CONTRACT_INVALID", "AI Platform 返回了无效评分", HttpStatus.BAD_GATEWAY);
        }
    }

    private static String screeningLevel(Object value, int score) {
        String level = value == null ? "" : String.valueOf(value).trim();
        if (List.of("STRONG_MATCH", "MATCH", "GENERAL_MATCH", "WEAK_MATCH").contains(level)) return level;
        return score >= 85 ? "STRONG_MATCH" : score >= 70 ? "MATCH" : score >= 60 ? "GENERAL_MATCH" : "WEAK_MATCH";
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(item -> !item.isBlank()).limit(20).toList();
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private void auditExecution(ExecutionRow run, String action) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,tenant_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,'SCREENING_RUN',?,?)
                """, UUID.randomUUID(), run.createdBy(), run.tenantId(), action,
                run.id().toString(), timestamp(Instant.now()));
    }

    private static String safeError(String message) {
        if (message == null || message.isBlank()) return "screening worker failed";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new ApiException("SERIALIZATION_FAILED", "筛选数据保存失败", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    private String protectedJson(Object value) {
        return json(Map.of("_encrypted", pii.encrypt(json(value))));
    }

    private ExecutionContext executionContext(String value) {
        try { return objectMapper.readValue(value, ExecutionContext.class); }
        catch (JsonProcessingException exception) {
            throw new ApiException("EXECUTION_CONTEXT_INVALID", "筛选执行上下文无法读取", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Each candidate is independently authorized and settled, so it needs its own durable idempotency scope. */
    static ExecutionContext itemExecutionContext(ExecutionContext runContext, UUID itemId, UUID parseVersionId) {
        String idempotencyKey = "screening-item:" + itemId;
        List<ExecutionContext.InputVersion> versions = new ArrayList<>(runContext.inputVersions());
        versions.add(new ExecutionContext.InputVersion("resume_parse_version", parseVersionId.toString(), "frozen", null));
        return new ExecutionContext(UUID.randomUUID(), runContext.routeDecisionId(), runContext.requestId(),
                runContext.traceId(), runContext.tenantId(), runContext.actorId(), itemId, idempotencyKey,
                runContext.capability(), "screening-item:" + itemId, List.copyOf(versions),
                runContext.policyDecision(), new ExecutionContext.DataHandling(true, "ephemeral", false), Instant.now());
    }

    private List<DimensionInput> dimensions(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { return List.of(); }
    }

    private List<String> strings(String json) {
        if (json == null) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            String value = node.has("_encrypted") ? pii.decryptIfEncrypted(node.path("_encrypted").asText()) : json;
            return objectMapper.readValue(value, new TypeReference<>() {});
        }
        catch (JsonProcessingException exception) { return List.of(); }
    }

    private void audit(UUID actor, TenantScope scope, String action, String resourceType, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,tenant_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), actor, scope.tenantId(), action, resourceType,
                resourceId.toString(), timestamp(Instant.now()));
    }

    static Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) return result;
        for (String token : value.split("[、,，/;；\\n\\r]+")) {
            if (!token.isBlank()) result.add(token.trim());
        }
        return result;
    }


    private static String requiredKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200) throw validation("Idempotency-Key不能为空且不能超过200字符");
        return key.trim();
    }

    private static List<UUID> safeCandidateIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 200) throw validation("请选择1至200名候选人");
        List<UUID> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.size() != ids.size()) throw validation("候选人列表包含重复或无效数据");
        return distinct;
    }

    private static ApiException validation(String message) { return new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST); }
    private static ApiException idempotencyConflict() { return new ApiException("IDEMPOTENCY_CONFLICT", "相同幂等键对应了不同请求", HttpStatus.CONFLICT); }

    private record JobRow(UUID id, UUID versionId, String title, String skills, String experienceLevel,
                          String education, String requirements) { }
    private record CandidateRow(UUID id, UUID parseVersionId, String headline, int yearsExperience,
                                String education, List<String> skills) { }
    private record RunRef(UUID id, String requestHash) { }
    private record CancelRow(String status, String providerTaskId) { }
    private record RunRow(UUID id, UUID jobId, String jobTitle, UUID planId, String planName, String status,
                          int progress, String scenario, Instant createdAt,
                          Instant completedAt, UUID recruitmentTaskId) { }
    private record QueuedCandidate(UUID candidateId, UUID parseVersionId, UUID sourceRunItemId,
                                   int attemptNumber) { }
    private record RetryContext(UUID jobId, UUID jobVersionId, UUID planVersionId, UUID planId,
                                UUID recruitmentTaskId,
                                UUID rootRunId, String status) { }
    private record ExecutionRow(UUID id, UUID tenantId, UUID jobVersionId,
                                UUID planVersionId, String providerTaskId, String status, String scenario,
                                UUID createdBy, String jobSnapshot, String rulesSnapshot,
                                String executionContext, int totalItems) { }
    private record ItemExecutionRow(UUID id, UUID candidateId, UUID parseVersionId, String status, String providerTaskId, String headline,
                                    int yearsExperience, String education, List<String> skills, String summary,
                                    String workExperience, String rawText) { }

    public record DimensionInput(String name, int weight, String description, boolean required,
                                 String exclusionRule, String missingPolicy) { }
    public record PlanInput(UUID jobId, String name, List<DimensionInput> dimensions, UUID recruitmentTaskId) { }
    public record PlanUpdateInput(UUID jobId, List<DimensionInput> dimensions) { }
    public record RunInput(UUID planId, List<UUID> candidateIds) { }
    public record OutboxClaim(UUID eventId, UUID runId, int attempts) { }
    public record ScreeningPlanView(UUID id, UUID tenantId, UUID recruitmentTaskId, UUID jobId, String jobTitle,
                                    UUID currentVersionId, int versionNumber, List<DimensionInput> dimensions,
                                    String name, String status, Instant createdAt, Instant updatedAt) { }
    public record ScreeningRunSummary(UUID id, UUID jobId, String jobTitle, String status, int progress,
                                      int totalItems, int succeededItems, Instant createdAt, Instant completedAt,
                                      UUID recruitmentTaskId) { }
    public record ScreeningItemView(UUID id, UUID candidateId, String candidateName, String status,
                                    String errorCode, int attemptNumber, Integer score, String level,
                                    List<String> matchedPoints, List<String> unmatchedPoints,
                                    List<String> negotiablePoints, List<String> missingInformation,
                                    List<String> risks, List<String> evidence) { }
    public record ScreeningRunDetail(UUID id, UUID jobId, String jobTitle, UUID planId, String planName,
                                     String status, int progress, String scenario, List<ScreeningItemView> items,
                                     Instant createdAt, Instant completedAt,
                                     UUID recruitmentTaskId, String settlementStatus) { }
}
