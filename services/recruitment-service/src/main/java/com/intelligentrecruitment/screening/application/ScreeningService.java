package com.intelligentrecruitment.screening.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.agentflow.application.RecruitmentFlowCoordinator;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.WorkspaceScope;
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
    private final WorkspaceAccessService workspaceAccess;
    private final BillingService billing;
    private final RecruitmentFlowCoordinator flowCoordinator;
    private final AiPlatformClient aiPlatform;
    private final ScreeningMatcher matcher;
    private final long unitPriceMinor;
    private final String pricingVersion;
    private final long quoteTtlSeconds;
    private final long outboxLeaseSeconds;

    public ScreeningService(JdbcTemplate jdbc, ObjectMapper objectMapper, WorkspaceAccessService workspaceAccess,
                            BillingService billing, RecruitmentFlowCoordinator flowCoordinator,
                            AiPlatformClient aiPlatform, ScreeningMatcher matcher,
                            @Value("${app.phase5.screening-unit-price-minor:80}") long unitPriceMinor,
                            @Value("${app.phase5.pricing-version:SCREENING_MOCK_V1}") String pricingVersion,
                            @Value("${app.phase5.quote-ttl-seconds:300}") long quoteTtlSeconds,
                            @Value("${app.phase5.outbox-lease-seconds:300}") long outboxLeaseSeconds) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workspaceAccess = workspaceAccess;
        this.billing = billing;
        this.flowCoordinator = flowCoordinator;
        this.aiPlatform = aiPlatform;
        this.matcher = matcher;
        this.unitPriceMinor = unitPriceMinor;
        this.pricingVersion = pricingVersion;
        this.quoteTtlSeconds = quoteTtlSeconds;
        this.outboxLeaseSeconds = outboxLeaseSeconds;
    }

    @Transactional
    public ScreeningPlanView createPlan(UUID userId, UUID workspaceId, PlanInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        if (input == null || input.jobId() == null) throw validation("请选择职位");
        JobRow job = job(workspaceId, input.jobId());
        UUID recruitmentTaskId = recruitmentTask(workspaceId, input.recruitmentTaskId());
        List<DimensionInput> dimensions = normalizeDimensions(input.dimensions());
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        String name = input.name() == null || input.name().isBlank() ? job.title() + "筛选方案" : input.name().trim();
        if (name.length() > 200) throw validation("筛选方案名称不能超过200字");
        jdbc.update("""
                INSERT INTO screening_plans
                (id,company_id,workspace_id,recruitment_task_id,job_id,name,status,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'ACTIVE',?,?,?)
                """, planId, scope.companyId(), workspaceId, recruitmentTaskId, job.id(), name, userId, timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO screening_plan_versions
                (id,company_id,workspace_id,plan_id,version_number,rules_snapshot,created_by,created_at)
                VALUES (?,?,?,?,1,?::jsonb,?,?)
                """, versionId, scope.companyId(), workspaceId, planId, json(dimensions), userId, timestamp(now));
        jdbc.update("UPDATE screening_plans SET current_version_id=? WHERE id=?", versionId, planId);
        audit(userId, scope, "SCREENING_PLAN_CREATED", "SCREENING_PLAN", planId);
        return planScoped(workspaceId, planId);
    }

    @Transactional
    public ScreeningPlanView updatePlan(UUID userId, UUID workspaceId, UUID planId, PlanUpdateInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        ScreeningPlanView existing = planScoped(workspaceId, planId);
        List<DimensionInput> dimensions = normalizeDimensions(input == null ? null : input.dimensions());
        int version = existing.versionNumber() + 1;
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO screening_plan_versions
                (id,company_id,workspace_id,plan_id,version_number,rules_snapshot,created_by,created_at)
                VALUES (?,?,?,?,?,?::jsonb,?,?)
                """, versionId, scope.companyId(), workspaceId, planId, version, json(dimensions), userId, timestamp(now));
        jdbc.update("UPDATE screening_plans SET current_version_id=?,updated_at=? WHERE id=? AND workspace_id=?",
                versionId, timestamp(now), planId, workspaceId);
        audit(userId, scope, "SCREENING_PLAN_UPDATED", "SCREENING_PLAN", planId);
        return planScoped(workspaceId, planId);
    }

    public List<ScreeningPlanView> listPlans(UUID userId, UUID workspaceId, UUID recruitmentTaskId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        recruitmentTask(workspaceId, recruitmentTaskId);
        String taskFilter = recruitmentTaskId == null ? "" : " AND p.recruitment_task_id=?";
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        if (recruitmentTaskId != null) params.add(recruitmentTaskId);
        return jdbc.query(planSelect() + " WHERE p.workspace_id=? AND p.status='ACTIVE'" + taskFilter
                        + " ORDER BY p.updated_at DESC",
                (rs, n) -> plan(rs), params.toArray());
    }

    @Transactional
    public ScreeningQuoteView quote(UUID userId, UUID workspaceId, QuoteInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        if (input == null || input.planId() == null) throw validation("请选择筛选方案");
        List<UUID> candidateIds = safeCandidateIds(input.candidateIds());
        ScreeningPlanView plan = planScoped(workspaceId, input.planId());
        JobRow job = job(workspaceId, plan.jobId());
        List<CandidateRow> candidates = candidates(workspaceId, candidateIds);
        if (candidates.size() != candidateIds.size()) {
            throw validation("候选人不存在、未解析或不属于当前工作空间");
        }
        long estimate = Math.multiplyExact(unitPriceMinor, candidateIds.size());
        var billingView = billing.view(userId, workspaceId);
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(quoteTtlSeconds, ChronoUnit.SECONDS);
        jdbc.update("""
                INSERT INTO screening_quotes
                (id,company_id,workspace_id,plan_version_id,job_version_id,candidate_ids_hash,
                 candidate_versions_hash,candidate_count,pricing_version,unit_price_minor,
                 estimated_amount_minor,expires_at,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, quoteId, scope.companyId(), workspaceId, plan.currentVersionId(), job.versionId(),
                candidateHash(candidateIds), candidateVersionHash(candidates), candidateIds.size(), pricingVersion,
                unitPriceMinor, estimate, timestamp(expiresAt), userId, timestamp(now));
        return new ScreeningQuoteView(quoteId, workspaceId, plan.id(), plan.currentVersionId(), candidateIds.size(),
                pricingVersion, unitPriceMinor, estimate, billingView.availableAmountMinor(), expiresAt);
    }

    public ScreeningPricingView pricing(UUID userId, UUID workspaceId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return new ScreeningPricingView(pricingVersion, unitPriceMinor, quoteTtlSeconds);
    }

    @Transactional
    public ScreeningRunDetail run(UUID userId, UUID workspaceId, String idempotencyKey, RunInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        String key = requiredKey(idempotencyKey);
        if (input == null || input.planId() == null) throw validation("请选择筛选方案");
        List<UUID> candidateIds = safeCandidateIds(input.candidateIds());
        String scenario = scenario(input.scenario());
        ScreeningPlanView plan = planScoped(workspaceId, input.planId());
        JobRow job = job(workspaceId, plan.jobId());
        List<CandidateRow> candidates = candidates(workspaceId, candidateIds);
        if (candidates.size() != candidateIds.size()) throw validation("候选人不存在、未解析或不属于当前工作空间");
        QuoteRow quote = quoteForUpdate(workspaceId, input.quoteId());
        String requestHash = SecurityHashes.sha256(plan.currentVersionId() + "|" + candidateIds.stream().sorted().toList()
                + "|" + scenario + "|" + quote.id());
        ScreeningRunDetail existing = existingRun(workspaceId, key, requestHash);
        if (existing != null) return existing;
        if (quote.consumedAt() != null) throw new ApiException("SCREENING_QUOTE_CONSUMED", "费用报价已使用，请重新获取报价", HttpStatus.CONFLICT);
        if (quote.expiresAt().isBefore(Instant.now())) throw new ApiException("SCREENING_QUOTE_EXPIRED", "费用报价已过期，请重新确认", HttpStatus.CONFLICT);
        if (!quote.createdBy().equals(userId)) throw new ApiException("SCREENING_QUOTE_OWNER_MISMATCH", "只能使用本人确认的费用报价", HttpStatus.FORBIDDEN);
        if (!java.util.Objects.equals(quote.planVersionId(), plan.currentVersionId())
                || !java.util.Objects.equals(quote.jobVersionId(), job.versionId())
                || !java.util.Objects.equals(quote.candidateIdsHash(), candidateHash(candidateIds))
                || !java.util.Objects.equals(quote.candidateVersionsHash(), candidateVersionHash(candidates))
                || quote.candidateCount() != candidateIds.size()
                || !quote.pricingVersion().equals(pricingVersion)
                || quote.unitPriceMinor() != unitPriceMinor) {
            throw new ApiException("SCREENING_QUOTE_CHANGED", "筛选范围、方案或价格已变化，请重新确认", HttpStatus.CONFLICT);
        }
        List<QueuedCandidate> queued = candidates.stream()
                .map(value -> new QueuedCandidate(value.id(), value.parseVersionId(), null, 1)).toList();
        return createQueuedRun(scope, userId, key, requestHash, quote, job.id(), job.versionId(),
                plan.currentVersionId(), plan.recruitmentTaskId(), scenario, queued, null, null);
    }

    @Transactional
    public ScreeningQuoteView retryQuote(UUID userId, UUID workspaceId, UUID originalRunId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        RetryContext context = retryContext(workspaceId, originalRunId);
        List<QueuedCandidate> failed = failedCandidates(workspaceId, originalRunId);
        if (failed.isEmpty()) throw new ApiException("NO_FAILED_ITEMS", "没有可重试的失败候选人", HttpStatus.CONFLICT);
        long estimate = Math.multiplyExact(unitPriceMinor, failed.size());
        var billingView = billing.view(userId, workspaceId);
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(quoteTtlSeconds, ChronoUnit.SECONDS);
        jdbc.update("""
                INSERT INTO screening_quotes
                (id,company_id,workspace_id,plan_version_id,job_version_id,candidate_ids_hash,
                 candidate_versions_hash,candidate_count,pricing_version,unit_price_minor,
                 estimated_amount_minor,expires_at,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, quoteId, scope.companyId(), workspaceId, context.planVersionId(), context.jobVersionId(),
                candidateHash(failed.stream().map(QueuedCandidate::candidateId).toList()),
                queuedCandidateVersionHash(failed), failed.size(), pricingVersion, unitPriceMinor, estimate,
                timestamp(expiresAt), userId, timestamp(now));
        return new ScreeningQuoteView(quoteId, workspaceId, context.planId(), context.planVersionId(), failed.size(),
                pricingVersion, unitPriceMinor, estimate, billingView.availableAmountMinor(), expiresAt);
    }

    @Transactional
    public ScreeningRunDetail retryFailed(UUID userId, UUID workspaceId, UUID originalRunId,
                                          String idempotencyKey, RetryInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        String key = requiredKey(idempotencyKey);
        RetryContext context = retryContext(workspaceId, originalRunId);
        List<QueuedCandidate> failed = failedCandidates(workspaceId, originalRunId);
        if (failed.isEmpty()) throw new ApiException("NO_FAILED_ITEMS", "没有可重试的失败候选人", HttpStatus.CONFLICT);
        QuoteRow quote = quoteForUpdate(workspaceId, input == null ? null : input.quoteId());
        String requestHash = SecurityHashes.sha256(originalRunId + "|" + quote.id() + "|RETRY_FAILED");
        ScreeningRunDetail existing = existingRun(workspaceId, key, requestHash);
        if (existing != null) return existing;
        if (quote.consumedAt() != null) throw new ApiException("SCREENING_QUOTE_CONSUMED", "费用报价已使用，请重新获取报价", HttpStatus.CONFLICT);
        if (quote.expiresAt().isBefore(Instant.now())) throw new ApiException("SCREENING_QUOTE_EXPIRED", "费用报价已过期，请重新确认", HttpStatus.CONFLICT);
        if (!quote.createdBy().equals(userId)) throw new ApiException("SCREENING_QUOTE_OWNER_MISMATCH", "只能使用本人确认的费用报价", HttpStatus.FORBIDDEN);
        if (!java.util.Objects.equals(quote.planVersionId(), context.planVersionId())
                || !java.util.Objects.equals(quote.jobVersionId(), context.jobVersionId())
                || !java.util.Objects.equals(quote.candidateIdsHash(), candidateHash(failed.stream().map(QueuedCandidate::candidateId).toList()))
                || !java.util.Objects.equals(quote.candidateVersionsHash(), queuedCandidateVersionHash(failed))
                || quote.candidateCount() != failed.size()
                || !quote.pricingVersion().equals(pricingVersion)
                || quote.unitPriceMinor() != unitPriceMinor) {
            throw new ApiException("SCREENING_QUOTE_CHANGED", "重试范围、冻结版本或价格已变化，请重新确认", HttpStatus.CONFLICT);
        }
        UUID rootRunId = context.rootRunId() == null ? originalRunId : context.rootRunId();
        return createQueuedRun(scope, userId, key, requestHash, quote, context.jobId(), context.jobVersionId(),
                context.planVersionId(), context.recruitmentTaskId(), "NORMAL", failed, originalRunId, rootRunId);
    }

    private ScreeningRunDetail createQueuedRun(WorkspaceScope scope, UUID userId, String key, String requestHash,
                                                QuoteRow quote, UUID jobId, UUID jobVersionId,
                                                UUID planVersionId, UUID recruitmentTaskId, String scenario,
                                                List<QueuedCandidate> candidates, UUID parentRunId, UUID rootRunId) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        long availableAmountMinor = billing.view(userId, scope.workspaceId()).availableAmountMinor();
        PolicyDecision policyDecision = flowCoordinator.evaluate(FlowCapability.CANDIDATE_SCREENING, scope, userId,
                availableAmountMinor, quote.estimatedAmountMinor(), quote.id(), true);
        List<ExecutionContext.InputVersion> inputVersions = new ArrayList<>();
        inputVersions.add(new ExecutionContext.InputVersion("job_version", jobVersionId.toString(), "frozen", null));
        inputVersions.add(new ExecutionContext.InputVersion("screening_plan_version", planVersionId.toString(), "frozen", null));
        candidates.forEach(candidate -> inputVersions.add(new ExecutionContext.InputVersion("resume_parse_version",
                candidate.parseVersionId().toString(), "frozen", null)));
        ExecutionContext executionContext = flowCoordinator.createExecutionContext(policyDecision, runId, key,
                "screening-run:" + runId, inputVersions, false);
        jdbc.update("""
                INSERT INTO screening_runs
                (id,company_id,workspace_id,recruitment_task_id,job_id,job_version_id,plan_version_id,quote_id,parent_run_id,
                 root_run_id,status,progress,scenario,pricing_version,unit_price_minor,estimated_amount_minor,
                 idempotency_key,request_hash,created_by,created_at,policy_decision,execution_context)
                VALUES (?,?,?,?,?,?,?,?,?,?,'RUNNING',5,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                """, runId, scope.companyId(), scope.workspaceId(), recruitmentTaskId, jobId, jobVersionId, planVersionId, quote.id(),
                parentRunId, rootRunId, scenario, quote.pricingVersion(), quote.unitPriceMinor(),
                quote.estimatedAmountMinor(), key, requestHash, userId, timestamp(now), json(policyDecision),
                json(executionContext));
        for (QueuedCandidate candidate : candidates) {
            jdbc.update("""
                    INSERT INTO screening_run_items
                    (id,company_id,workspace_id,run_id,candidate_id,parse_version_id,source_run_item_id,
                     status,attempt_number,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?)
                    """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), runId, candidate.candidateId(),
                    candidate.parseVersionId(), candidate.sourceRunItemId(), candidate.attemptNumber(),
                    timestamp(now), timestamp(now));
        }
        billing.reserve(userId, scope.workspaceId(), "screening-run:" + runId, quote.estimatedAmountMinor());
        int consumed = jdbc.update("""
                UPDATE screening_quotes SET consumed_at=?,consumed_by_run_id=?
                WHERE id=? AND workspace_id=? AND consumed_at IS NULL
                """, timestamp(now), runId, quote.id(), scope.workspaceId());
        if (consumed != 1) throw new ApiException("SCREENING_QUOTE_CONSUMED", "费用报价已使用，请重新获取报价", HttpStatus.CONFLICT);
        jdbc.update("""
                INSERT INTO outbox_events
                (id,aggregate_type,aggregate_id,event_type,payload,status,attempts,next_attempt_at,created_at)
                VALUES (?,'SCREENING_RUN',?,'SCREENING_RUN_REQUESTED',?::jsonb,'PENDING',0,?,?)
                """, UUID.randomUUID(), runId.toString(), json(Map.of("run_id", runId.toString())),
                timestamp(now), timestamp(now));
        audit(userId, scope, "SCREENING_RUN_REQUESTED", "SCREENING_RUN", runId);
        return runScoped(scope.workspaceId(), runId);
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
        ExecutionRow run = rows.getFirst();
        if (run.providerTaskId() == null) {
            Map<String, Object> aiInput = Map.of("job_version_id", run.jobVersionId().toString(),
                    "candidate_count", run.totalItems(), "scenario", run.scenario());
            var aiTask = aiPlatform.startTask(new StartAiTaskCommand(run.workspaceId().toString(),
                    run.companyId() == null ? null : run.companyId().toString(), run.createdBy().toString(),
                    run.id().toString(), "screening-provider:" + run.id(), AiCapability.CANDIDATE_SCREENING, aiInput,
                    executionContext(run.executionContext())));
            jdbc.update("UPDATE screening_runs SET provider_task_id=?,progress=15 WHERE id=? AND status='RUNNING'",
                    aiTask.aiTaskId(), runId);
        }
        return true;
    }

    @Transactional
    public boolean processNextItem(UUID runId) {
        List<ExecutionRow> rows = executionRows(runId, true);
        if (rows.isEmpty() || !"RUNNING".equals(rows.getFirst().status())) return false;
        ExecutionRow run = rows.getFirst();
        List<ItemExecutionRow> items = jdbc.query("""
                SELECT i.id,i.candidate_id,i.parse_version_id,pv.headline,pv.years_experience,
                       pv.highest_education,pv.skills::text,pv.summary,pv.work_experience::text,pv.raw_text
                FROM screening_run_items i
                JOIN resume_parse_versions pv ON pv.id=i.parse_version_id
                WHERE i.run_id=? AND i.workspace_id=? AND i.status='PENDING'
                ORDER BY i.created_at,i.id FOR UPDATE OF i SKIP LOCKED LIMIT 1
                """, (rs, n) -> new ItemExecutionRow(rs.getObject("id", UUID.class),
                rs.getObject("candidate_id", UUID.class), rs.getObject("parse_version_id", UUID.class),
                rs.getString("headline"), rs.getInt("years_experience"), rs.getString("highest_education"),
                strings(rs.getString("skills")), rs.getString("summary"), rs.getString("work_experience"),
                rs.getString("raw_text")), runId, run.workspaceId());
        if (items.isEmpty()) return false;
        ItemExecutionRow item = items.getFirst();
        Integer processed = jdbc.queryForObject("""
                SELECT count(*) FROM screening_run_items WHERE run_id=? AND status<>'PENDING'
                """, Integer.class, runId);
        int index = processed == null ? 0 : processed;
        boolean fail = "INVALID_SCHEMA".equals(run.scenario())
                || ("PARTIAL_FAILURE".equals(run.scenario()) && index % 3 == 2);
        Instant now = Instant.now();
        if (fail) {
            jdbc.update("UPDATE screening_run_items SET status='FAILED',error_code=?,updated_at=? WHERE id=?",
                    "INVALID_SCHEMA".equals(run.scenario()) ? "AI_SCHEMA_INVALID" : "AI_ITEM_FAILED",
                    timestamp(now), item.id());
        } else {
            ScreeningMatcher.MatchResult result = matcher.match(jobFromSnapshot(run.jobSnapshot()),
                    new ScreeningMatcher.FrozenCandidate(item.headline(), item.yearsExperience(), item.education(),
                            item.skills(), item.summary(), item.workExperience(), item.rawText()),
                    dimensions(run.rulesSnapshot()));
            jdbc.update("""
                    INSERT INTO screening_results
                    (id,company_id,workspace_id,run_item_id,score,level,matched_points,unmatched_points,
                     negotiable_points,missing_information,risks,evidence,result_snapshot,created_at)
                    VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)
                    """, UUID.randomUUID(), run.companyId(), run.workspaceId(), item.id(), result.score(),
                    result.level(), json(result.matched()), json(result.unmatched()), json(result.negotiable()),
                    json(result.missing()), json(result.risks()), json(result.evidence()), json(result), timestamp(now));
            jdbc.update("UPDATE screening_run_items SET status='SUCCEEDED',error_code=NULL,updated_at=? WHERE id=?",
                    timestamp(now), item.id());
        }
        Integer completed = jdbc.queryForObject("""
                SELECT count(*) FROM screening_run_items WHERE run_id=? AND status<>'PENDING'
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
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM screening_run_items WHERE run_id=? AND status='PENDING'",
                Integer.class, runId);
        if (pending != null && pending > 0) return;
        Integer succeeded = jdbc.queryForObject("SELECT count(*) FROM screening_run_items WHERE run_id=? AND status='SUCCEEDED'",
                Integer.class, runId);
        int successCount = succeeded == null ? 0 : succeeded;
        long actual = Math.multiplyExact(run.unitPriceMinor(), successCount);
        billing.settleSystem(run.workspaceId(), "screening-run:" + runId, actual);
        String status = successCount == run.totalItems() ? "COMPLETED" : successCount == 0 ? "FAILED" : "PARTIAL_FAILED";
        jdbc.update("""
                UPDATE screening_runs SET status=?,progress=100,settled_amount_minor=?,completed_at=?
                WHERE id=? AND status='RUNNING'
                """, status, actual, timestamp(Instant.now()), runId);
        auditExecution(run, "SCREENING_RUN_" + status);
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
            Integer succeeded = jdbc.queryForObject("SELECT count(*) FROM screening_run_items WHERE run_id=? AND status='SUCCEEDED'",
                    Integer.class, run.id());
            long actual = Math.multiplyExact(run.unitPriceMinor(), succeeded == null ? 0 : succeeded);
            billing.settleSystem(run.workspaceId(), "screening-run:" + run.id(), actual);
            jdbc.update("""
                    UPDATE screening_runs SET status=?,progress=100,settled_amount_minor=?,completed_at=? WHERE id=?
                    """, actual > 0 ? "PARTIAL_FAILED" : "FAILED", actual, timestamp(Instant.now()), run.id());
            auditExecution(run, "SCREENING_RUN_WORKER_FAILED");
        }
        jdbc.update("UPDATE outbox_events SET status='FAILED',sent_at=?,payload=jsonb_set(payload,'{error}',to_jsonb(CAST(? AS text))) WHERE id=?",
                timestamp(Instant.now()), safeError(message), claim.eventId());
    }

    @Transactional
    public ScreeningRunDetail cancel(UUID userId, UUID workspaceId, UUID runId, String idempotencyKey) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        List<CancelRow> rows = jdbc.query("""
                SELECT status,provider_task_id,unit_price_minor FROM screening_runs
                WHERE id=? AND workspace_id=? FOR UPDATE
                """, (rs, n) -> new CancelRow(rs.getString(1), rs.getString(2), rs.getLong(3)), runId, workspaceId);
        if (rows.isEmpty()) throw new ApiException("SCREENING_RUN_NOT_FOUND", "筛选任务不存在", HttpStatus.NOT_FOUND);
        CancelRow row = rows.getFirst();
        if ("CANCELLED".equals(row.status())) return runScoped(workspaceId, runId);
        if (!"RUNNING".equals(row.status())) {
            throw new ApiException("SCREENING_RUN_TERMINAL", "筛选任务已结束，不能取消", HttpStatus.CONFLICT);
        }
        if (row.providerTaskId() != null) aiPlatform.cancelTask(row.providerTaskId(), requiredKey(idempotencyKey));
        Integer succeeded = jdbc.queryForObject("""
                SELECT count(*) FROM screening_run_items WHERE run_id=? AND workspace_id=? AND status='SUCCEEDED'
                """, Integer.class, runId, workspaceId);
        long actual = Math.multiplyExact(row.unitPriceMinor(), succeeded == null ? 0 : succeeded);
        billing.settle(userId, workspaceId, "screening-run:" + runId, actual);
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE screening_run_items SET status='CANCELLED',updated_at=?
                WHERE run_id=? AND workspace_id=? AND status='PENDING'
                """, timestamp(now), runId, workspaceId);
        jdbc.update("""
                UPDATE screening_runs SET status='CANCELLED',progress=100,settled_amount_minor=?,completed_at=?
                WHERE id=? AND workspace_id=?
                """, actual, timestamp(now), runId, workspaceId);
        jdbc.update("""
                UPDATE outbox_events SET status='SENT',sent_at=?
                WHERE event_type='SCREENING_RUN_REQUESTED' AND aggregate_id=? AND status IN ('PENDING','PROCESSING')
                """, timestamp(now), runId.toString());
        audit(userId, scope, "SCREENING_RUN_CANCELLED", "SCREENING_RUN", runId);
        return runScoped(workspaceId, runId);
    }

    public List<ScreeningRunSummary> listRuns(UUID userId, UUID workspaceId, UUID recruitmentTaskId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        recruitmentTask(workspaceId, recruitmentTaskId);
        String taskFilter = recruitmentTaskId == null ? "" : " AND r.recruitment_task_id=?";
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        if (recruitmentTaskId != null) params.add(recruitmentTaskId);
        return jdbc.query("""
                SELECT r.id,r.job_id,j.title AS job_title,r.status,r.progress,r.estimated_amount_minor,
                       r.settled_amount_minor,r.created_at,r.completed_at,r.recruitment_task_id,
                       count(i.id) AS total_items,count(i.id) FILTER (WHERE i.status='SUCCEEDED') AS succeeded_items
                FROM screening_runs r JOIN jobs j ON j.id=r.job_id
                JOIN screening_run_items i ON i.run_id=r.id WHERE r.workspace_id=?""" + taskFilter + """
                GROUP BY r.id,j.title ORDER BY r.created_at DESC LIMIT 100
                """, (rs, n) -> new ScreeningRunSummary(rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getString("job_title"), rs.getString("status"),
                rs.getInt("progress"), rs.getInt("total_items"), rs.getInt("succeeded_items"),
                rs.getLong("estimated_amount_minor"), rs.getLong("settled_amount_minor"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at") == null ? null :
                rs.getTimestamp("completed_at").toInstant(), rs.getObject("recruitment_task_id", UUID.class)), params.toArray());
    }

    public ScreeningRunDetail getRun(UUID userId, UUID workspaceId, UUID runId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return runScoped(workspaceId, runId);
    }

    private ScreeningRunDetail runScoped(UUID workspaceId, UUID runId) {
        List<RunRow> runs = jdbc.query("""
                SELECT r.id,r.job_id,j.title AS job_title,p.id AS plan_id,p.name AS plan_name,r.status,r.progress,
                       r.scenario,r.pricing_version,r.unit_price_minor,r.estimated_amount_minor,
                       r.settled_amount_minor,r.created_at,r.completed_at,r.recruitment_task_id
                FROM screening_runs r JOIN jobs j ON j.id=r.job_id
                JOIN screening_plan_versions pv ON pv.id=r.plan_version_id JOIN screening_plans p ON p.id=pv.plan_id
                WHERE r.id=? AND r.workspace_id=?
                """, (rs, n) -> new RunRow(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getString("job_title"), rs.getObject("plan_id", UUID.class), rs.getString("plan_name"),
                rs.getString("status"), rs.getInt("progress"), rs.getString("scenario"),
                rs.getString("pricing_version"), rs.getLong("unit_price_minor"),
                rs.getLong("estimated_amount_minor"), rs.getLong("settled_amount_minor"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at") == null ? null :
                rs.getTimestamp("completed_at").toInstant(), rs.getObject("recruitment_task_id", UUID.class)), runId, workspaceId);
        if (runs.isEmpty()) throw new ApiException("SCREENING_RUN_NOT_FOUND", "筛选任务不存在", HttpStatus.NOT_FOUND);
        List<ScreeningItemView> items = jdbc.query("""
                SELECT i.id,i.candidate_id,c.display_name_masked,i.status,i.error_code,i.attempt_number,
                       r.score,r.level,r.matched_points::text,r.unmatched_points::text,r.negotiable_points::text,
                       r.missing_information::text,r.risks::text,r.evidence::text
                FROM screening_run_items i JOIN candidates c ON c.id=i.candidate_id
                LEFT JOIN screening_results r ON r.run_item_id=i.id
                WHERE i.run_id=? AND i.workspace_id=? ORDER BY r.score DESC NULLS LAST,c.display_name_masked
                """, (rs, n) -> new ScreeningItemView(rs.getObject("id", UUID.class),
                rs.getObject("candidate_id", UUID.class), rs.getString("display_name_masked"),
                rs.getString("status"), rs.getString("error_code"), rs.getInt("attempt_number"),
                rs.getObject("score") == null ? null : rs.getInt("score"), rs.getString("level"),
                strings(rs.getString("matched_points")), strings(rs.getString("unmatched_points")),
                strings(rs.getString("negotiable_points")), strings(rs.getString("missing_information")),
                strings(rs.getString("risks")), strings(rs.getString("evidence"))), runId, workspaceId);
        RunRow run = runs.getFirst();
        return new ScreeningRunDetail(run.id(), run.jobId(), run.jobTitle(), run.planId(), run.planName(),
                run.status(), run.progress(), run.scenario(), run.pricingVersion(), run.unitPriceMinor(),
                run.estimatedAmountMinor(), run.settledAmountMinor(), items, run.createdAt(), run.completedAt(),
                run.recruitmentTaskId());
    }

    private ScreeningPlanView planScoped(UUID workspaceId, UUID planId) {
        List<ScreeningPlanView> rows = jdbc.query(planSelect() + " WHERE p.id=? AND p.workspace_id=? AND p.status='ACTIVE'",
                (rs, n) -> plan(rs), planId, workspaceId);
        if (rows.isEmpty()) throw new ApiException("SCREENING_PLAN_NOT_FOUND", "筛选方案不存在", HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private static String planSelect() {
        return """
                SELECT p.id,p.company_id,p.workspace_id,p.recruitment_task_id,p.job_id,j.title AS job_title,p.current_version_id,
                       pv.version_number,pv.rules_snapshot::text,p.name,p.status,p.created_at,p.updated_at
                FROM screening_plans p JOIN jobs j ON j.id=p.job_id
                JOIN screening_plan_versions pv ON pv.id=p.current_version_id
                """;
    }

    private ScreeningPlanView plan(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScreeningPlanView(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getObject("recruitment_task_id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getString("job_title"), rs.getObject("current_version_id", UUID.class),
                rs.getInt("version_number"), dimensions(rs.getString("rules_snapshot")), rs.getString("name"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private JobRow job(UUID workspaceId, UUID jobId) {
        List<JobRow> rows = jdbc.query("""
                SELECT id,current_version_id,title,skills,experience_level,education,requirements
                FROM jobs WHERE id=? AND workspace_id=? AND status IN ('ACTIVE','DRAFT') AND current_version_id IS NOT NULL
                """, (rs, n) -> new JobRow(rs.getObject("id", UUID.class),
                rs.getObject("current_version_id", UUID.class), rs.getString("title"), rs.getString("skills"),
                rs.getString("experience_level"), rs.getString("education"), rs.getString("requirements")),
                jobId, workspaceId);
        if (rows.isEmpty()) throw new ApiException("JOB_NOT_FOUND", "职位不存在或没有可用版本", HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private UUID recruitmentTask(UUID workspaceId, UUID recruitmentTaskId) {
        if (recruitmentTaskId == null) return null;
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM recruitment_tasks WHERE id=? AND workspace_id=?
                """, Integer.class, recruitmentTaskId, workspaceId);
        if (count == null || count == 0) {
            throw new ApiException("RECRUITMENT_TASK_NOT_FOUND", "招聘任务不存在或不属于当前工作空间", HttpStatus.NOT_FOUND);
        }
        return recruitmentTaskId;
    }

    private List<CandidateRow> candidates(UUID workspaceId, List<UUID> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> params = new ArrayList<>(); params.add(workspaceId); params.addAll(ids);
        return jdbc.query("""
                SELECT c.id,c.current_parse_version_id,pv.headline,pv.years_experience,pv.highest_education,pv.skills::text
                FROM candidates c JOIN resume_parse_versions pv ON pv.id=c.current_parse_version_id
                WHERE c.workspace_id=? AND c.status='ACTIVE' AND c.id IN (""" + placeholders + ")",
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

    private QuoteRow quoteForUpdate(UUID workspaceId, UUID quoteId) {
        if (quoteId == null) throw validation("请先获取并确认费用报价");
        List<QuoteRow> rows = jdbc.query("""
                SELECT id,plan_version_id,job_version_id,candidate_ids_hash,candidate_versions_hash,
                       candidate_count,pricing_version,unit_price_minor,estimated_amount_minor,expires_at,
                       created_by,consumed_at
                FROM screening_quotes WHERE id=? AND workspace_id=? FOR UPDATE
                """, (rs, n) -> new QuoteRow(rs.getObject("id", UUID.class),
                rs.getObject("plan_version_id", UUID.class), rs.getObject("job_version_id", UUID.class),
                rs.getString("candidate_ids_hash"), rs.getString("candidate_versions_hash"),
                rs.getInt("candidate_count"), rs.getString("pricing_version"), rs.getLong("unit_price_minor"),
                rs.getLong("estimated_amount_minor"), rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("consumed_at") == null ? null
                : rs.getTimestamp("consumed_at").toInstant()), quoteId, workspaceId);
        if (rows.isEmpty()) throw new ApiException("SCREENING_QUOTE_NOT_FOUND", "费用报价不存在", HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private static String candidateHash(List<UUID> ids) {
        return SecurityHashes.sha256(ids.stream().sorted().map(UUID::toString).toList().toString());
    }

    private static String candidateVersionHash(List<CandidateRow> candidates) {
        return SecurityHashes.sha256(candidates.stream()
                .map(value -> value.id() + ":" + value.parseVersionId()).sorted().toList().toString());
    }

    private static String queuedCandidateVersionHash(List<QueuedCandidate> candidates) {
        return SecurityHashes.sha256(candidates.stream()
                .map(value -> value.candidateId() + ":" + value.parseVersionId()).sorted().toList().toString());
    }

    private ScreeningRunDetail existingRun(UUID workspaceId, String key, String requestHash) {
        List<RunRef> existing = jdbc.query("""
                SELECT id,request_hash FROM screening_runs WHERE workspace_id=? AND idempotency_key=?
                """, (rs, n) -> new RunRef(rs.getObject(1, UUID.class), rs.getString(2)), workspaceId, key);
        if (existing.isEmpty()) return null;
        if (!existing.getFirst().requestHash().equals(requestHash)) throw idempotencyConflict();
        return runScoped(workspaceId, existing.getFirst().id());
    }

    private RetryContext retryContext(UUID workspaceId, UUID originalRunId) {
        List<RetryContext> rows = jdbc.query("""
                SELECT r.job_id,r.job_version_id,r.plan_version_id,p.id AS plan_id,r.recruitment_task_id,r.root_run_id,r.status
                FROM screening_runs r
                JOIN screening_plan_versions pv ON pv.id=r.plan_version_id
                JOIN screening_plans p ON p.id=pv.plan_id
                WHERE r.id=? AND r.workspace_id=?
                """, (rs, n) -> new RetryContext(rs.getObject("job_id", UUID.class),
                rs.getObject("job_version_id", UUID.class), rs.getObject("plan_version_id", UUID.class),
                rs.getObject("plan_id", UUID.class), rs.getObject("recruitment_task_id", UUID.class), rs.getObject("root_run_id", UUID.class),
                rs.getString("status")), originalRunId, workspaceId);
        if (rows.isEmpty()) throw new ApiException("SCREENING_RUN_NOT_FOUND", "筛选任务不存在", HttpStatus.NOT_FOUND);
        if ("RUNNING".equals(rows.getFirst().status())) {
            throw new ApiException("SCREENING_RUN_NOT_TERMINAL", "筛选任务尚未结束，不能重试", HttpStatus.CONFLICT);
        }
        return rows.getFirst();
    }

    private List<QueuedCandidate> failedCandidates(UUID workspaceId, UUID originalRunId) {
        return jdbc.query("""
                SELECT id,candidate_id,parse_version_id,attempt_number
                FROM screening_run_items
                WHERE run_id=? AND workspace_id=? AND status='FAILED'
                ORDER BY created_at,id
                """, (rs, n) -> new QueuedCandidate(rs.getObject("candidate_id", UUID.class),
                rs.getObject("parse_version_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getInt("attempt_number") + 1), originalRunId, workspaceId);
    }

    private List<ExecutionRow> executionRows(UUID runId, boolean lock) {
        return jdbc.query("""
                SELECT r.id,r.company_id,r.workspace_id,r.job_version_id,r.plan_version_id,r.provider_task_id,
                       r.status,r.scenario,r.unit_price_minor,r.created_by,jv.snapshot::text,
                       pv.rules_snapshot::text,r.execution_context::text,
                       (SELECT count(*) FROM screening_run_items i WHERE i.run_id=r.id) AS total_items
                FROM screening_runs r
                JOIN job_versions jv ON jv.id=r.job_version_id
                JOIN screening_plan_versions pv ON pv.id=r.plan_version_id
                WHERE r.id=?
                """ + (lock ? " FOR UPDATE OF r" : ""), (rs, n) -> new ExecutionRow(
                rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getObject("job_version_id", UUID.class),
                rs.getObject("plan_version_id", UUID.class), rs.getString("provider_task_id"),
                rs.getString("status"), rs.getString("scenario"), rs.getLong("unit_price_minor"),
                rs.getObject("created_by", UUID.class), rs.getString("snapshot"),
                rs.getString("rules_snapshot"), rs.getString("execution_context"),
                rs.getInt("total_items")), runId);
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

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private void auditExecution(ExecutionRow run, String action) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,company_id,workspace_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,'SCREENING_RUN',?,?)
                """, UUID.randomUUID(), run.createdBy(), run.companyId(), run.workspaceId(), action,
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

    private ExecutionContext executionContext(String value) {
        try { return objectMapper.readValue(value, ExecutionContext.class); }
        catch (JsonProcessingException exception) {
            throw new ApiException("EXECUTION_CONTEXT_INVALID", "筛选执行上下文无法读取", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<DimensionInput> dimensions(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { return List.of(); }
    }

    private List<String> strings(String json) {
        if (json == null) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { return List.of(); }
    }

    private void audit(UUID actor, WorkspaceScope scope, String action, String resourceType, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,company_id,workspace_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), actor, scope.companyId(), scope.workspaceId(), action, resourceType,
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

    private static String scenario(String value) {
        String scenario = value == null || value.isBlank() ? "NORMAL" : value.toUpperCase(Locale.ROOT);
        if (!List.of("NORMAL", "PARTIAL_FAILURE", "INVALID_SCHEMA").contains(scenario)) throw validation("无效的筛选场景");
        return scenario;
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
    private record QuoteRow(UUID id, UUID planVersionId, UUID jobVersionId, String candidateIdsHash,
                            String candidateVersionsHash, int candidateCount, String pricingVersion,
                            long unitPriceMinor, long estimatedAmountMinor, Instant expiresAt,
                            UUID createdBy, Instant consumedAt) { }
    private record CancelRow(String status, String providerTaskId, long unitPriceMinor) { }
    private record RunRow(UUID id, UUID jobId, String jobTitle, UUID planId, String planName, String status,
                          int progress, String scenario, String pricingVersion, long unitPriceMinor,
                          long estimatedAmountMinor, long settledAmountMinor, Instant createdAt,
                          Instant completedAt, UUID recruitmentTaskId) { }
    private record QueuedCandidate(UUID candidateId, UUID parseVersionId, UUID sourceRunItemId,
                                   int attemptNumber) { }
    private record RetryContext(UUID jobId, UUID jobVersionId, UUID planVersionId, UUID planId,
                                UUID recruitmentTaskId,
                                UUID rootRunId, String status) { }
    private record ExecutionRow(UUID id, UUID companyId, UUID workspaceId, UUID jobVersionId,
                                UUID planVersionId, String providerTaskId, String status, String scenario,
                                long unitPriceMinor, UUID createdBy, String jobSnapshot, String rulesSnapshot,
                                String executionContext, int totalItems) { }
    private record ItemExecutionRow(UUID id, UUID candidateId, UUID parseVersionId, String headline,
                                    int yearsExperience, String education, List<String> skills, String summary,
                                    String workExperience, String rawText) { }

    public record DimensionInput(String name, int weight, String description, boolean required,
                                 String exclusionRule, String missingPolicy) { }
    public record PlanInput(UUID jobId, String name, List<DimensionInput> dimensions, UUID recruitmentTaskId) { }
    public record PlanUpdateInput(List<DimensionInput> dimensions) { }
    public record QuoteInput(UUID planId, List<UUID> candidateIds) { }
    public record RunInput(UUID planId, List<UUID> candidateIds, String scenario, UUID quoteId) { }
    public record RetryInput(UUID quoteId) { }
    public record ScreeningPricingView(String pricingVersion, long unitPriceMinor, long quoteTtlSeconds) { }
    public record OutboxClaim(UUID eventId, UUID runId, int attempts) { }
    public record ScreeningQuoteView(UUID id, UUID workspaceId, UUID planId, UUID planVersionId,
                                     int candidateCount, String pricingVersion, long unitPriceMinor,
                                     long estimatedAmountMinor, long availableAmountMinor, Instant expiresAt) { }
    public record ScreeningPlanView(UUID id, UUID companyId, UUID workspaceId, UUID recruitmentTaskId, UUID jobId, String jobTitle,
                                    UUID currentVersionId, int versionNumber, List<DimensionInput> dimensions,
                                    String name, String status, Instant createdAt, Instant updatedAt) { }
    public record ScreeningRunSummary(UUID id, UUID jobId, String jobTitle, String status, int progress,
                                      int totalItems, int succeededItems, long estimatedAmountMinor,
                                      long settledAmountMinor, Instant createdAt, Instant completedAt,
                                      UUID recruitmentTaskId) { }
    public record ScreeningItemView(UUID id, UUID candidateId, String candidateName, String status,
                                    String errorCode, int attemptNumber, Integer score, String level,
                                    List<String> matchedPoints, List<String> unmatchedPoints,
                                    List<String> negotiablePoints, List<String> missingInformation,
                                    List<String> risks, List<String> evidence) { }
    public record ScreeningRunDetail(UUID id, UUID jobId, String jobTitle, UUID planId, String planName,
                                     String status, int progress, String scenario, String pricingVersion,
                                     long unitPriceMinor, long estimatedAmountMinor, long settledAmountMinor,
                                     List<ScreeningItemView> items, Instant createdAt, Instant completedAt,
                                     UUID recruitmentTaskId) { }
}
