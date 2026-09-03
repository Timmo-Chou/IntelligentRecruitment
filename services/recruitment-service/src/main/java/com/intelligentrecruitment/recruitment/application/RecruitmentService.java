package com.intelligentrecruitment.recruitment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.agentflow.application.RecruitmentFlowCoordinator;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.ConversationAgentCommand;
import com.intelligentrecruitment.aiplatform.application.RouteAgentCommand;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.billing.application.PricingService;
import com.intelligentrecruitment.candidates.application.PiiCipher;
import com.intelligentrecruitment.jobs.application.JobService;
import com.intelligentrecruitment.interview.application.InterviewService;
import com.intelligentrecruitment.recruitment.application.JdDraftGenerator.JdDraftContent;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.WorkspaceScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class RecruitmentService {

    private static final Logger log = LoggerFactory.getLogger(RecruitmentService.class);
    private static final String JD_PRICING_VERSION = "JD_MOCK_V1";
    private static final String RESUME_PARSING_PRICING_VERSION = "RESUME_MOCK_V1";
    private static final String LEGACY_DEFAULT_TASK_TITLE = "高级 Java 开发工程师招聘";

    /** 计费项 code，与 pricing_items.code 保持一致 */
    private static final String JD_BILLING_CODE = "JD_GENERATION";
    private static final String RESUME_PARSING_BILLING_CODE = "RESUME_PARSING";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessService workspaceAccess;
    private final BillingService billing;
    private final PricingService pricing;
    private final RecruitmentFlowCoordinator flowCoordinator;
    private final AiPlatformClient aiPlatform;
    private final JdStructuredResultMapper structuredResultMapper;
    private final JdSourceFileService sourceFiles;
    private final ResumeSourceFileService resumeSourceFiles;
    private final JobService jobs;
    private final InterviewService interviewService;
    private final PiiCipher pii;
    /** pricing_items 表没启用对应计费项时的兜底默认值（分） */
    private final long defaultJdPriceMinor;
    private final long defaultResumePriceMinor;
    private final long outboxLeaseSeconds;

    public RecruitmentService(JdbcTemplate jdbc, ObjectMapper objectMapper, WorkspaceAccessService workspaceAccess,
                              BillingService billing, PricingService pricing, RecruitmentFlowCoordinator flowCoordinator,
                              AiPlatformClient aiPlatform,
                              JdStructuredResultMapper structuredResultMapper,
                              JdSourceFileService sourceFiles,
                              ResumeSourceFileService resumeSourceFiles,
                              JobService jobs,
                              InterviewService interviewService,
                              PiiCipher pii,
                              @Value("${app.phase3.jd-generation-price-minor:80}") long defaultJdPriceMinor,
                              @Value("${app.phase4.resume-parsing-price-minor:80}") long defaultResumePriceMinor,
                              @Value("${app.phase3.outbox-lease-seconds:300}") long outboxLeaseSeconds) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workspaceAccess = workspaceAccess;
        this.billing = billing;
        this.pricing = pricing;
        this.flowCoordinator = flowCoordinator;
        this.aiPlatform = aiPlatform;
        this.structuredResultMapper = structuredResultMapper;
        this.sourceFiles = sourceFiles;
        this.resumeSourceFiles = resumeSourceFiles;
        this.jobs = jobs;
        this.interviewService = interviewService;
        this.pii = pii;
        this.defaultJdPriceMinor = defaultJdPriceMinor;
        this.defaultResumePriceMinor = defaultResumePriceMinor;
        this.outboxLeaseSeconds = outboxLeaseSeconds;
    }

    /** JD 生成单价：优先从 pricing_items 查，fallback 到默认值 */
    private long resolveJdPriceMinor() {
        Long configured = pricing.findUnitPriceMinor(JD_BILLING_CODE);
        return configured != null ? configured : defaultJdPriceMinor;
    }

    /** 简历解析单价：优先从 pricing_items 查，fallback 到默认值 */
    private long resolveResumePriceMinor() {
        Long configured = pricing.findUnitPriceMinor(RESUME_PARSING_BILLING_CODE);
        return configured != null ? configured : defaultResumePriceMinor;
    }

    @Transactional
    public TaskDetail createTask(UUID userId, UUID workspaceId, String idempotencyKey, CreateTaskInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        String key = requiredIdempotencyKey(idempotencyKey);
        if (input == null) throw validation("招聘任务不能为空");
        String title = required(input.title(), "招聘任务名称不能为空", 200);
        String requirement = required(input.initialRequirement(), "请描述招聘需求", 20_000);
        String featureType = optional(input.featureType(), 32);
        String linkedJobIdRaw = input.linkedJobId() == null || input.linkedJobId().isBlank() ? null : input.linkedJobId().trim();
        UUID linkedJobId = linkedJobIdRaw == null ? null : UUID.fromString(linkedJobIdRaw);
        String linkedCandidateIdRaw = input.linkedCandidateId() == null || input.linkedCandidateId().isBlank() ? null : input.linkedCandidateId().trim();
        UUID linkedCandidateId = linkedCandidateIdRaw == null ? null : UUID.fromString(linkedCandidateIdRaw);
        String requestHash = SecurityHashes.sha256(title + "\n" + requirement + "\n" + featureType + "\n"
                + (linkedJobIdRaw == null ? "" : linkedJobIdRaw) + "\n" + (linkedCandidateIdRaw == null ? "" : linkedCandidateIdRaw));
        List<ExistingReference> existing = jdbc.query("""
                SELECT id,request_hash FROM recruitment_tasks WHERE workspace_id=? AND idempotency_key=?
                """, (rs, n) -> new ExistingReference(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                workspaceId, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().requestHash().equals(requestHash)) throw idempotencyConflict();
            return detailScoped(workspaceId, existing.getFirst().id());
        }
        UUID taskId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO recruitment_tasks
                (id,company_id,workspace_id,title,initial_requirement,status,current_stage,idempotency_key,
                 request_hash,feature_type,linked_job_id,linked_candidate_id,created_by,created_at,updated_at)
                VALUES (?,?,?, ?,?,'ACTIVE','COLLECTING_REQUIREMENTS',?, ?,?,?,?, ?,?,?)
                """, taskId, scope.companyId(), workspaceId, title, requirement, key, requestHash,
                featureType.isBlank() ? null : featureType, linkedJobId, linkedCandidateId, userId,
                timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO conversations
                (id,company_id,workspace_id,recruitment_task_id,status,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',?,?)
                """, conversationId, scope.companyId(), workspaceId, taskId, timestamp(now), timestamp(now));
        insertMessage(scope, conversationId, "USER", requirement, "REQUIREMENT_CHAT", userId, now);
        audit(userId, scope, "RECRUITMENT_TASK_CREATED", "RECRUITMENT_TASK", taskId);
        return detailScoped(workspaceId, taskId);
    }

    public List<TaskSummary> listTasks(UUID userId, UUID workspaceId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return jdbc.query("""
                SELECT t.id,t.company_id,t.workspace_id,t.title,t.status,t.current_stage,t.feature_type,t.linked_job_id,t.linked_candidate_id,t.created_by,
                       t.created_at,t.updated_at,j.id AS job_id,j.title AS job_title
                FROM recruitment_tasks t
                LEFT JOIN LATERAL (
                    SELECT id,title FROM jobs
                    WHERE recruitment_task_id=t.id AND workspace_id=t.workspace_id AND status<>'ARCHIVED'
                    ORDER BY updated_at DESC LIMIT 1
                ) j ON true
                WHERE t.workspace_id=? ORDER BY t.updated_at DESC LIMIT 100
                """, (rs, n) -> new TaskSummary(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("title"), rs.getString("status"), rs.getString("current_stage"),
                rs.getString("feature_type"),
                rs.getObject("linked_job_id", UUID.class),
                rs.getObject("linked_candidate_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getString("job_title"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), workspaceId);
    }

    public TaskDetail getTask(UUID userId, UUID workspaceId, UUID taskId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return detailScoped(workspaceId, taskId);
    }

    public SourceFileView uploadJdSourceFile(UUID userId, UUID workspaceId, UUID taskId,
                                             org.springframework.web.multipart.MultipartFile file) {
        JdSourceFileService.SourceFileView source = sourceFiles.upload(userId, workspaceId, taskId, file);
        return new SourceFileView(source.id(), source.fileAssetId(), source.filename(), source.mediaType(),
                source.sizeBytes(), source.createdAt());
    }

    @Transactional
    public TaskSummary renameTask(UUID userId, UUID workspaceId, UUID taskId, RenameTaskInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        taskForUpdate(workspaceId, taskId);
        String title = required(input == null ? null : input.title(), "招聘任务名称不能为空", 200);
        jdbc.update("UPDATE recruitment_tasks SET title=?,updated_at=? WHERE id=? AND workspace_id=?",
                title, timestamp(Instant.now()), taskId, workspaceId);
        audit(userId, scope, "RECRUITMENT_TASK_RENAMED", "RECRUITMENT_TASK", taskId);
        return detailScoped(workspaceId, taskId).task();
    }

    @Transactional
    public void deleteTask(UUID userId, UUID workspaceId, UUID taskId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        taskForUpdate(workspaceId, taskId);
        Integer jobCount = jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE recruitment_task_id=? AND workspace_id=?",
                Integer.class, taskId, workspaceId);
        if (jobCount != null && jobCount > 0) {
            throw new ApiException("RECRUITMENT_TASK_HAS_JOB", "该任务已创建职位，无法删除。请保留任务以追溯职位来源。", HttpStatus.CONFLICT);
        }
        Integer screeningPlanCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM screening_plans WHERE recruitment_task_id=? AND workspace_id=?
                """, Integer.class, taskId, workspaceId);
        if (screeningPlanCount != null && screeningPlanCount > 0) {
            throw new ApiException("RECRUITMENT_TASK_HAS_SCREENING", "该任务已创建筛选方案或筛选记录，无法删除。请保留任务以追溯筛选依据与结果。", HttpStatus.CONFLICT);
        }
        jdbc.update("""
                DELETE FROM outbox_events WHERE aggregate_type='AI_RUN'
                AND aggregate_id IN (SELECT id::text FROM ai_runs WHERE recruitment_task_id=? AND workspace_id=?)
                """, taskId, workspaceId);
        jdbc.update("DELETE FROM jd_drafts WHERE recruitment_task_id=? AND workspace_id=?", taskId, workspaceId);
        jdbc.update("DELETE FROM resume_parse_drafts WHERE recruitment_task_id=? AND workspace_id=?", taskId, workspaceId);
        jdbc.update("DELETE FROM resume_source_files WHERE recruitment_task_id=? AND workspace_id=?", taskId, workspaceId);
        jdbc.update("DELETE FROM ai_runs WHERE recruitment_task_id=? AND workspace_id=?", taskId, workspaceId);
        jdbc.update("DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE recruitment_task_id=? AND workspace_id=?)",
                taskId, workspaceId);
        jdbc.update("DELETE FROM conversations WHERE recruitment_task_id=? AND workspace_id=?", taskId, workspaceId);
        jdbc.update("DELETE FROM recruitment_tasks WHERE id=? AND workspace_id=?", taskId, workspaceId);
        audit(userId, scope, "RECRUITMENT_TASK_DELETED", "RECRUITMENT_TASK", taskId);
    }

    @Transactional
    public TaskDetail addMessage(UUID userId, UUID workspaceId, UUID taskId, MessageInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        String content = required(input == null ? null : input.content(), "消息不能为空", 20_000);
        Instant now = Instant.now();
        insertMessage(scope, task.conversationId(), "USER", content, "REQUIREMENT_CHAT", userId, now);
        ConversationAgentCommand command = new ConversationAgentCommand(workspaceId.toString(),
                scope.companyId() == null ? null : scope.companyId().toString(), userId.toString(), taskId.toString(),
                conversationContext(task.conversationId(), workspaceId), jdDraftContext(workspaceId, taskId, input.jdDraftId()));
        String reply;
        try {
            if (!command.jdDraft().isEmpty()) {
                StructuredResult revisedResult = aiPlatform.reviseJdInPlace(command);
                JdDraftContent revised = structuredResultMapper.toDraft(revisedResult);
                if ("CREATE_NEW_JD".equals(revisedResult.data().get("action"))) insertAdditionalDraft(scope, taskId, userId, revised);
                else updateDraftInPlace(scope, taskId, input.jdDraftId(), userId, revised);
                reply = optionalAssistantMessage(revisedResult);
            } else {
                reply = aiPlatform.continueConversation(command);
            }
        } catch (RuntimeException exception) {
            log.warn("Recruitment conversation update failed for task {}: {}", taskId, exception.getMessage());
            reply = "当前无法完成 AI 修改，请稍后重试。你的需求已保存，不会丢失。";
        }
        insertMessage(scope, task.conversationId(), "ASSISTANT", reply, "REQUIREMENT_CHAT", null, Instant.now());
        jdbc.update("""
                UPDATE recruitment_tasks SET current_stage='COLLECTING_REQUIREMENTS',updated_at=?
                WHERE id=? AND workspace_id=?
                """, timestamp(Instant.now()), taskId, workspaceId);
        return detailScoped(workspaceId, taskId);
    }

    /**
     * Routes free-form text only. The returned decision is deliberately not a
     * business authorization and cannot create a billable run.
     */
    public RouteDecision routeMessage(UUID userId, UUID workspaceId, UUID taskId, RouteMessageInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        Integer taskCount = jdbc.queryForObject("SELECT COUNT(*) FROM recruitment_tasks WHERE id=? AND workspace_id=?",
                Integer.class, taskId, workspaceId);
        if (taskCount == null || taskCount == 0) throw taskNotFound();
        String message = required(input == null ? null : input.message(), "消息不能为空", 20_000);
        String requestId = MDC.get("request_id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return aiPlatform.routeMessage(new RouteAgentCommand(requestId, requestId, workspaceId.toString(),
                scope.companyId() == null ? null : scope.companyId().toString(), userId.toString(), taskId.toString(),
                message, List.of(FlowCapability.REQUIREMENT_CHAT, FlowCapability.RECRUITMENT_QA,
                        FlowCapability.JD_GENERATION, FlowCapability.RESUME_PARSING,
                        FlowCapability.SCREENING_PLAN_GENERATION, FlowCapability.CANDIDATE_SCREENING,
                        FlowCapability.CANDIDATE_SOURCING, FlowCapability.JOB_DISTRIBUTION,
                        FlowCapability.CANDIDATE_OUTREACH, FlowCapability.INTERVIEW_KIT_GENERATION,
                        FlowCapability.TASK_ASSISTANCE)));
    }

    @Transactional
    public TaskDetail generateJd(UUID userId, UUID workspaceId, UUID taskId, String idempotencyKey,
                                 GenerateJdInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        String key = requiredIdempotencyKey(idempotencyKey);
        String payloadHash = hash(input);
        List<ExistingReference> existing = jdbc.query("""
                SELECT id,input_hash AS request_hash FROM ai_runs WHERE workspace_id=? AND idempotency_key=?
                """, (rs, n) -> new ExistingReference(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                workspaceId, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().requestHash().equals(payloadHash)) throw idempotencyConflict();
            return detailScoped(workspaceId, taskId);
        }
        String requirement = optional(input == null ? null : input.requirement(), 20_000);
        if (requirement.isBlank()) requirement = task.initialRequirement();
        String scenario = normalizedScenario(input == null ? null : input.scenario());
        UUID runId = UUID.randomUUID();
        int attempt = nextAttempt(taskId);
        Instant now = Instant.now();
        updateLegacyDefaultTaskTitle(task, requirement, now);
        long availableAmountMinor = billing.view(userId, workspaceId).availableAmountMinor();
        PolicyDecision policyDecision = flowCoordinator.evaluate(FlowCapability.JD_GENERATION, scope, userId,
                availableAmountMinor, resolveJdPriceMinor(), null, true);
        ExecutionContext executionContext = flowCoordinator.createExecutionContext(policyDecision, taskId, key,
                "jd-run:" + runId, List.of(new ExecutionContext.InputVersion("conversation_summary",
                taskId.toString(), "frozen", payloadHash)), false);
        jdbc.update("""
                INSERT INTO ai_runs
                (id,company_id,workspace_id,recruitment_task_id,capability,status,progress,attempt_number,
                 idempotency_key,input_hash,pricing_version,estimated_amount_minor,created_by,created_at,
                 input_payload,policy_decision,execution_context)
                VALUES (?,?,?,?, 'JD_GENERATION','QUEUED',0,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                """, runId, scope.companyId(), workspaceId, taskId, attempt, key, payloadHash,
                JD_PRICING_VERSION, resolveJdPriceMinor(), userId, timestamp(now), protectedPayload(Map.of(
                        "requirement", requirement,
                        "scenario", scenario,
                        "title", nullable(value(input, GenerateJdInput::title)),
                        "companyName", nullable(value(input, GenerateJdInput::companyName)),
                        "location", nullable(value(input, GenerateJdInput::location)),
                        "experienceLevel", nullable(value(input, GenerateJdInput::experienceLevel)),
                        "education", nullable(value(input, GenerateJdInput::education)),
                        "jobType", nullable(value(input, GenerateJdInput::jobType)),
                        "skills", nullable(value(input, GenerateJdInput::skills)))), json(policyDecision),
                json(executionContext));
        String billingReference = "jd-run:" + runId;
        billing.reserve(userId, workspaceId, billingReference, resolveJdPriceMinor());
        jdbc.update("""
                INSERT INTO outbox_events
                (id,aggregate_type,aggregate_id,event_type,payload,status,attempts,next_attempt_at,created_at)
                VALUES (?,'AI_RUN',?,'JD_RUN_REQUESTED',?::jsonb,'PENDING',0,?,?)
                """, UUID.randomUUID(), runId.toString(), json(Map.of("run_id", runId.toString())),
                timestamp(now), timestamp(now));
        appendRunEvent(new RunExecution(runId, scope.companyId(), workspaceId, taskId, userId, task.conversationId(),
                key, "QUEUED", 0, null, json(Map.of()), json(executionContext)), "status",
                Map.of("status", "QUEUED", "progress", 0));
        jdbc.update("UPDATE recruitment_tasks SET current_stage='JD_GENERATING',updated_at=? WHERE id=?",
                timestamp(now), taskId);
        audit(userId, scope, "JD_GENERATION_QUEUED", "AI_RUN", runId);
        return detailScoped(workspaceId, taskId);
    }

    @Transactional
    public OutboxClaim claimNextJdRun() {
        Instant now = Instant.now();
        List<OutboxClaim> rows = jdbc.query("""
                UPDATE outbox_events SET status='PROCESSING',attempts=attempts+1,next_attempt_at=?
                WHERE id=(SELECT id FROM outbox_events
                    WHERE event_type='JD_RUN_REQUESTED'
                      AND ((status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                        OR (status='PROCESSING' AND next_attempt_at<=?))
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id,aggregate_id,attempts
                """, (rs, n) -> new OutboxClaim(rs.getObject("id", UUID.class),
                UUID.fromString(rs.getString("aggregate_id")), rs.getInt("attempts")),
                timestamp(now.plus(outboxLeaseSeconds, ChronoUnit.SECONDS)), timestamp(now), timestamp(now));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public boolean prepareJdRun(UUID runId) {
        RunExecution run = runExecution(runId, true);
        if (!List.of("QUEUED", "RUNNING").contains(run.status())) return false;
        Map<String, String> input = stringMap(run.inputPayload());
        if ("QUEUED".equals(run.status())) {
            Map<String, Object> aiInput = payloadMap(run.inputPayload());
            aiInput.remove("scenario");
            aiInput.put("source_documents", sourceFiles.listForGeneration(run.workspaceId(), run.taskId()).stream()
                    .map(file -> Map.<String, Object>of("filename", file.filename(), "text", file.extractedText())).toList());
            AiTask aiTask = aiPlatform.startTask(new StartAiTaskCommand(run.workspaceId().toString(),
                    run.companyId() == null ? null : run.companyId().toString(), run.createdBy().toString(),
                    run.taskId().toString(), run.idempotencyKey(), AiCapability.JD_GENERATION, aiInput,
                    executionContext(run.executionContext())), delta -> emitJdDelta(run.id(), delta));
            jdbc.update("UPDATE ai_runs SET status='RUNNING',progress=15,provider_task_id=? WHERE id=?",
                    aiTask.aiTaskId(), run.id());
            appendRunEvent(run, "status", Map.of("status", "RUNNING", "progress", 15));
        }
        return true;
    }

    @Transactional
    public void emitJdDelta(UUID runId, String delta) {
        RunExecution run = runExecution(runId, true);
        if (!List.of("QUEUED", "RUNNING").contains(run.status())) return;
        int progress = Math.min(95, Math.max(15, run.progress() + 3));
        jdbc.update("UPDATE ai_runs SET progress=? WHERE id=? AND status IN ('QUEUED','RUNNING')", progress, run.id());
        appendRunEvent(run, "delta", Map.of("delta", delta, "progress", progress));
    }

    @Transactional
    public void finalizeJdRunIfReady(UUID runId) {
        RunExecution run = runExecution(runId, true);
        if (!"RUNNING".equals(run.status()) || run.providerTaskId() == null) return;
        AiTask task = aiPlatform.getTask(run.providerTaskId());
        if (task.status() == com.intelligentrecruitment.aiplatform.domain.AiTaskStatus.FAILED
                || task.status() == com.intelligentrecruitment.aiplatform.domain.AiTaskStatus.CANCELLED) {
            failJdRun(run, "AI_PROVIDER_UNAVAILABLE", "AI 生成失败，请重试");
            return;
        }
        if (task.status() == com.intelligentrecruitment.aiplatform.domain.AiTaskStatus.COMPLETED) finalizeJdRun(runId);
    }

    public List<UUID> runningJdRunIds() {
        return jdbc.query("SELECT id FROM ai_runs WHERE capability='JD_GENERATION' AND status='RUNNING' ORDER BY created_at LIMIT 50",
                (rs, n) -> rs.getObject(1, UUID.class));
    }

    @Transactional
    public void finalizeJdRun(UUID runId) {
        RunExecution run = runExecution(runId, true);
        if (!"RUNNING".equals(run.status())) return;
        Map<String, String> input = stringMap(run.inputPayload());
        String scenario = input.getOrDefault("scenario", "NORMAL");
        if (!"NORMAL".equals(scenario)) {
            failJdRun(run, scenario);
            return;
        }
        JdDraftContent draft = structuredResultMapper.toDraft(aiPlatform.getStructuredResult(run.providerTaskId()));
        WorkspaceScope scope = new WorkspaceScope(run.workspaceId(), run.companyId(), null, null, null);
        upsertDraft(scope, run.taskId(), run.id(), run.createdBy(), draft);
        Instant completed = Instant.now();
        billing.settleSystem(run.workspaceId(), "jd-run:" + run.id(), resolveJdPriceMinor());
        jdbc.update("UPDATE ai_runs SET status='COMPLETED',progress=100,settled_amount_minor=?,completed_at=? WHERE id=?",
                resolveJdPriceMinor(), timestamp(completed), run.id());
        insertMessage(scope, run.conversationId(), "ASSISTANT",
                "JD 草稿已生成。请检查职责、任职要求和待确认项，确认后再进入职位库。",
                "JD_GENERATION", null, completed);
        jdbc.update("UPDATE recruitment_tasks SET current_stage='AWAITING_JD_CONFIRMATION',updated_at=? WHERE id=?",
                timestamp(completed), run.taskId());
        appendRunEvent(run, "completed", Map.of("status", "COMPLETED", "progress", 100));
        audit(run.createdBy(), scope, "JD_DRAFT_GENERATED", "AI_RUN", run.id());
    }

    @Transactional
    public void completeJdOutbox(UUID eventId) {
        jdbc.update("UPDATE outbox_events SET status='SENT',sent_at=? WHERE id=?", timestamp(Instant.now()), eventId);
    }

    @Transactional
    public void failJdOutbox(OutboxClaim claim, String error) {
        if (claim.attempts() < 3) {
            jdbc.update("UPDATE outbox_events SET status='PENDING',next_attempt_at=? WHERE id=?",
                    timestamp(Instant.now().plus(claim.attempts(), ChronoUnit.SECONDS)), claim.eventId());
            return;
        }
        RunExecution run = runExecution(claim.runId(), true);
        failJdRun(run, "WORKER", error);
        jdbc.update("UPDATE outbox_events SET status='FAILED',sent_at=? WHERE id=?", timestamp(Instant.now()), claim.eventId());
    }

    public List<RunEvent> runEvents(UUID userId, UUID workspaceId, UUID taskId, long afterEventId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return jdbc.query("""
                SELECT e.event_id,e.run_id,e.event_type,e.data::text,e.created_at
                FROM jd_run_events e JOIN ai_runs r ON r.id=e.run_id
                WHERE e.workspace_id=? AND e.recruitment_task_id=? AND e.event_id>?
                ORDER BY e.event_id LIMIT 200
                """, (rs, n) -> new RunEvent(rs.getLong("event_id"), rs.getObject("run_id", UUID.class),
                rs.getString("event_type"), rs.getString("data"), rs.getTimestamp("created_at").toInstant()),
                workspaceId, taskId, Math.max(0, afterEventId));
    }

    @Transactional
    public TaskDetail updateDraft(UUID userId, UUID workspaceId, UUID taskId, UpdateDraftInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        if (input == null) throw validation("JD 草稿不能为空");
        String warnings = json(input.warnings() == null ? List.of() : input.warnings());
        int updated = jdbc.update("""
                UPDATE jd_drafts SET revision=revision+1,status=CASE WHEN status='CONFIRMED' THEN 'DRAFT' ELSE status END,title=?,company_name=?,location=?,experience_level=?,
                    education=?,job_type=?,salary_range=?,responsibilities=?,requirements=?,skills=?,nice_to_haves=?,benefits=?,talent_profile=?,warnings=?::jsonb,
                    updated_by=?,updated_at=?
                WHERE id=? AND recruitment_task_id=? AND workspace_id=? AND revision=? AND status IN ('DRAFT','CONFIRMED')
                """, required(input.title(), "职位名称不能为空", 200),
                required(input.companyName(), "企业名称不能为空", 200), optional(input.location(), 200),
                optional(input.experienceLevel(), 80), optional(input.education(), 80),
                defaulted(input.jobType(), "全职", 50), optional(input.salaryRange(), 200), optional(input.responsibilities(), 20_000),
                optional(input.requirements(), 20_000), optional(input.skills(), 4_000), optional(input.niceToHaves(), 10_000),
                optional(input.benefits(), 10_000), optional(input.talentProfile(), 10_000), warnings, userId, timestamp(Instant.now()), input.id(), taskId,
                workspaceId, input.revision());
        if (updated == 0) {
            throw new ApiException("JD_DRAFT_VERSION_CONFLICT", "JD 草稿已更新或已确认，请刷新后重试", HttpStatus.CONFLICT);
        }
        insertMessage(scope, task.conversationId(), "SYSTEM", "JD 草稿已由招聘人员编辑保存。",
                "JD_GENERATION", userId, Instant.now());
        audit(userId, scope, "JD_DRAFT_UPDATED", "JD_DRAFT", taskId);
        return detailScoped(workspaceId, taskId);
    }

    @Transactional
    public JobService.JobView confirmDraft(UUID userId, UUID workspaceId, UUID taskId, UUID draftId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        List<JdDraftView> drafts = draftRows(workspaceId, taskId);
        if (drafts.isEmpty()) throw new ApiException("JD_DRAFT_NOT_FOUND", "请先生成 JD 草稿", HttpStatus.CONFLICT);
        JdDraftView draft = drafts.stream().filter(item -> item.id().equals(draftId)).findFirst()
                .orElseThrow(() -> new ApiException("JD_DRAFT_NOT_FOUND", "JD 草稿不存在", HttpStatus.NOT_FOUND));
        if ("CONFIRMED".equals(draft.status())) {
            List<UUID> jobIds = jdbc.query("SELECT id FROM jobs WHERE jd_draft_id=? AND workspace_id=?",
                    (rs, n) -> rs.getObject("id", UUID.class), draft.id(), workspaceId);
            if (!jobIds.isEmpty()) return jobs.get(userId, workspaceId, jobIds.getFirst());
        }
        UUID sourceAiRunId = jdbc.queryForObject("SELECT source_ai_run_id FROM jd_drafts WHERE id=?",
                UUID.class, draft.id());
        JobService.JobInput jobInput = new JobService.JobInput(draft.title(), draft.companyName(), draft.location(),
                draft.salaryRange(), draft.responsibilities(), draft.requirements(), draft.skills(), draft.experienceLevel(),
                draft.education(), draft.jobType(), draft.niceToHaves(), draft.benefits());
        JobService.JobView job = jobs.createFromConfirmedJd(userId, workspaceId, taskId, draft.id(), sourceAiRunId, jobInput,
                draft.talentProfile(), json(draft.warnings()));
        Instant now = Instant.now();
        jdbc.update("UPDATE jd_drafts SET status='CONFIRMED',updated_by=?,updated_at=? WHERE id=?",
                userId, timestamp(now), draft.id());
        // A recruitment task is a persistent workspace. Confirming a draft makes
        // that revision usable, but must not close the task or prevent further
        // conversation, additions, and adjustments.
        jdbc.update("UPDATE recruitment_tasks SET status='ACTIVE',current_stage='JD_CONFIRMED',updated_at=? WHERE id=?",
                timestamp(now), taskId);
        insertMessage(scope, task.conversationId(), "ASSISTANT",
                "JD 已确认并保存到职位库草稿。补全待确认项后，请在职位库发布。", "JD_GENERATION", null, now);
        audit(userId, scope, "JD_CONFIRMED", "RECRUITMENT_TASK", taskId);
        return job;
    }

    private TaskDetail detailScoped(UUID workspaceId, UUID taskId) {
        List<TaskSummary> summaries = jdbc.query("""
                SELECT t.id,t.company_id,t.workspace_id,t.title,t.status,t.current_stage,t.feature_type,t.linked_job_id,t.linked_candidate_id,t.created_by,
                       t.created_at,t.updated_at,j.id AS job_id,j.title AS job_title
                FROM recruitment_tasks t
                LEFT JOIN LATERAL (
                    SELECT id,title FROM jobs
                    WHERE recruitment_task_id=t.id AND workspace_id=t.workspace_id AND status<>'ARCHIVED'
                    ORDER BY updated_at DESC LIMIT 1
                ) j ON true
                WHERE t.id=? AND t.workspace_id=?
                """, (rs, n) -> new TaskSummary(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("title"), rs.getString("status"), rs.getString("current_stage"),
                rs.getString("feature_type"),
                rs.getObject("linked_job_id", UUID.class),
                rs.getObject("linked_candidate_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getString("job_title"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), taskId, workspaceId);
        if (summaries.isEmpty()) throw taskNotFound();
        UUID conversationId = jdbc.queryForObject("""
                SELECT id FROM conversations WHERE recruitment_task_id=? AND workspace_id=?
                """, UUID.class, taskId, workspaceId);
        List<MessageView> messages = jdbc.query("""
                SELECT id,role,content,capability,sequence_number,created_by,created_at
                FROM messages WHERE conversation_id=? AND workspace_id=? ORDER BY sequence_number
                """, (rs, n) -> new MessageView(rs.getObject("id", UUID.class), rs.getString("role"),
                pii.decryptIfEncrypted(rs.getString("content")), rs.getString("capability"), rs.getInt("sequence_number"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant()),
                conversationId, workspaceId);
        List<JdDraftView> drafts = draftRows(workspaceId, taskId);
        JdDraftView draft = drafts.stream().findFirst().orElse(null);
        List<AiRunView> runs = jdbc.query("""
                SELECT id,provider_task_id,status,progress,attempt_number,pricing_version,estimated_amount_minor,
                       settled_amount_minor,error_code,error_message,created_at,completed_at
                FROM ai_runs WHERE recruitment_task_id=? AND workspace_id=? ORDER BY created_at DESC LIMIT 1
                """, (rs, n) -> new AiRunView(rs.getObject("id", UUID.class), rs.getString("provider_task_id"),
                rs.getString("status"), rs.getInt("progress"), rs.getInt("attempt_number"),
                rs.getString("pricing_version"), rs.getLong("estimated_amount_minor"),
                rs.getLong("settled_amount_minor"), rs.getString("error_code"), rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()),
                taskId, workspaceId);
        // 简历解析：源文件 + 解析草稿
        List<ResumeSourceFileView> resumeSourceFileList = resumeSourceFiles.list(workspaceId, taskId).stream()
                .map(item -> new ResumeSourceFileView(item.id(), item.fileAssetId(), item.filename(),
                        item.mediaType(), item.sizeBytes(), item.createdAt())).toList();
        List<ResumeParseDraftView> resumeParseDraftList = resumeParseDraftRows(workspaceId, taskId);
        ResumeParseDraftView resumeParseDraft = resumeParseDraftList.stream().findFirst().orElse(null);
        return new TaskDetail(summaries.getFirst(), conversationId, messages, drafts, draft,
                runs.isEmpty() ? null : runs.getFirst(),
                resumeSourceFileList, resumeParseDraftList, resumeParseDraft);
    }

    private TaskRow taskForUpdate(UUID workspaceId, UUID taskId) {
        List<TaskRow> rows = jdbc.query("""
                SELECT t.id,t.title,t.initial_requirement,c.id AS conversation_id
                FROM recruitment_tasks t JOIN conversations c ON c.recruitment_task_id=t.id
                WHERE t.id=? AND t.workspace_id=? FOR UPDATE OF t
                """, (rs, n) -> new TaskRow(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("initial_requirement"),
                rs.getObject("conversation_id", UUID.class)), taskId, workspaceId);
        if (rows.isEmpty()) throw taskNotFound();
        return rows.getFirst();
    }

    private List<JdDraftView> draftRows(UUID workspaceId, UUID taskId) {
        return jdbc.query("""
                SELECT id,revision,title,company_name,location,experience_level,education,job_type,salary_range,
                       responsibilities,requirements,skills,nice_to_haves,benefits,talent_profile,warnings::text,status,updated_at
                FROM jd_drafts WHERE recruitment_task_id=? AND workspace_id=? ORDER BY created_at
                """, (rs, n) -> new JdDraftView(rs.getObject("id", UUID.class), rs.getInt("revision"),
                rs.getString("title"), rs.getString("company_name"), rs.getString("location"),
                rs.getString("experience_level"), rs.getString("education"), rs.getString("job_type"), rs.getString("salary_range"),
                rs.getString("responsibilities"), rs.getString("requirements"), rs.getString("skills"), rs.getString("nice_to_haves"),
                rs.getString("benefits"), rs.getString("talent_profile"), parseWarnings(rs.getString("warnings")), rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()), taskId, workspaceId);
    }

    /** 简历解析草稿行查询：按版本号倒序返回最新在前 */
    private List<ResumeParseDraftView> resumeParseDraftRows(UUID workspaceId, UUID taskId) {
        return jdbc.query("""
                SELECT id,revision,source_ai_run_id,resume_source_file_id,content,status,created_by,created_at,updated_at
                FROM resume_parse_drafts WHERE recruitment_task_id=? AND workspace_id=? ORDER BY revision DESC
                """, (rs, n) -> new ResumeParseDraftView(rs.getObject("id", UUID.class), rs.getInt("revision"),
                rs.getObject("source_ai_run_id", UUID.class), rs.getObject("resume_source_file_id", UUID.class),
                pii.decryptIfEncrypted(rs.getString("content")), rs.getString("status"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                taskId, workspaceId);
    }

    /** 上传简历源文件：返回 TaskDetail（保证前端重新拉取整体状态） */
    public SourceFileView uploadResumeSourceFile(UUID userId, UUID workspaceId, UUID taskId,
                                                 org.springframework.web.multipart.MultipartFile file) {
        ResumeSourceFileService.SourceFileView source = resumeSourceFiles.upload(userId, workspaceId, taskId, file);
        return new SourceFileView(source.id(), source.fileAssetId(), source.filename(), source.mediaType(),
                source.sizeBytes(), source.createdAt());
    }

    /** 保存（插入或更新）简历解析草稿：支持用户手动编辑解析结果。
     *  若已存在同 revision 草稿则更新内容与时间，不存在则以 revision+1 插入新版本。 */
    @Transactional
    public TaskDetail updateResumeParseDraft(UUID userId, UUID workspaceId, UUID taskId, UpdateResumeParseDraftInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        taskForUpdate(workspaceId, taskId);
        if (input == null) throw validation("解析草稿内容不能为空");
        String content = required(input.content(), "请保存解析结果", 500_000);
        int requestedRevision = Math.max(1, input.revision());
        Instant now = Instant.now();
        Integer currentMax = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision),0) FROM resume_parse_drafts WHERE recruitment_task_id=? AND workspace_id=?
                """, Integer.class, taskId, workspaceId);
        int maxRevision = currentMax == null ? 0 : currentMax;
        int nextRevision = maxRevision == 0 ? 1 : maxRevision + 1;
        int targetRevision = requestedRevision <= maxRevision ? requestedRevision : nextRevision;
        int updated;
        if (targetRevision <= maxRevision) {
            updated = jdbc.update("""
                    UPDATE resume_parse_drafts SET content=?, status=CASE WHEN status='CONFIRMED' THEN 'DRAFT' ELSE status END,
                     updated_by=?,updated_at=?
                    WHERE recruitment_task_id=? AND workspace_id=? AND revision=?
                    """, pii.encrypt(content), userId, timestamp(now), taskId, workspaceId, targetRevision);
        } else {
            updated = jdbc.update("""
                    INSERT INTO resume_parse_drafts
                    (id,company_id,workspace_id,recruitment_task_id,revision,content,status,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,?,?, 'DRAFT',?,?,?)
                    """, UUID.randomUUID(), scope.companyId(), workspaceId, taskId, nextRevision, pii.encrypt(content),
                    userId, timestamp(now), timestamp(now));
        }
        if (updated == 0) throw new ApiException("RESUME_PARSE_DRAFT_NOT_FOUND", "简历解析草稿不存在，无法更新", HttpStatus.CONFLICT);
        jdbc.update("UPDATE recruitment_tasks SET updated_at=? WHERE id=? AND workspace_id=?", timestamp(now), taskId, workspaceId);
        return detailScoped(workspaceId, taskId);
    }

    /**
     * 触发一次 AI 简历解析：把 resume_source_files 中的提取文本 + 可选职位一起送给 AI。
     * 返回 TaskDetail，前端通过 latestAiRun.progress 轮询或 events 流获取进度。
     */
    @Transactional
    public TaskDetail generateResumeParse(UUID userId, UUID workspaceId, UUID taskId, String idempotencyKey,
                                          GenerateResumeParseInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        String key = requiredIdempotencyKey(idempotencyKey);
        // 构造输入 payload：resumes + job（如果有 linked_job_id）
        List<ResumeSourceFileService.SourceFileView> files = resumeSourceFiles.list(workspaceId, taskId);
        // 关键修复：如果不上传文件但选了人才库候选人 → 从 resume_parse_versions.raw_text 注入 1 条虚拟简历
        List<Map<String, Object>> resumeList = new ArrayList<>(files.stream().map(item -> {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.id().toString());
            m.put("filename", item.filename());
            m.put("media_type", item.mediaType() == null ? "" : item.mediaType());
            m.put("size_bytes", item.sizeBytes());
            m.put("text", item.extractedText() == null ? "" : item.extractedText());
            return (Map<String, Object>) m;
        }).toList());
        if (resumeList.isEmpty()) {
            UUID linkedCandidateId = jdbc.queryForObject("""
                    SELECT linked_candidate_id FROM recruitment_tasks WHERE id=? AND workspace_id=?
                    """, UUID.class, taskId, workspaceId);
            if (linkedCandidateId != null) {
                List<CandidateVirtualText> cands = jdbc.query("""
                        SELECT c.id,c.full_name_ciphertext,c.current_parse_version_id,
                               pv.raw_text,
                               COALESCE(pv.headline, '') AS headline,
                               COALESCE(NULLIF(c.profile->>'yearsExperience','')::int, pv.years_experience, 0) AS years_experience,
                               COALESCE(c.profile->>'highestEducation', pv.highest_education, '') AS highest_education,
                               COALESCE(c.profile->'skills', pv.skills, '[]'::jsonb)::text AS skills,
                               f.original_filename
                        FROM candidates c
                        LEFT JOIN resume_parse_versions pv ON pv.id=c.current_parse_version_id
                        LEFT JOIN resume_files rf ON rf.candidate_id=c.id
                        LEFT JOIN file_assets f ON f.id=rf.file_asset_id
                        WHERE c.id=? AND c.workspace_id=? AND c.status<>'DELETED'
                        ORDER BY rf.id NULLS LAST LIMIT 1
                        """, (rs, n) -> new CandidateVirtualText(
                                rs.getObject("id", UUID.class),
                                pii.decrypt(rs.getString("full_name_ciphertext")),
                                pii.decryptIfEncrypted(rs.getString("raw_text")),
                                rs.getString("headline"),
                                rs.getInt("years_experience"),
                                rs.getString("highest_education"),
                                rs.getString("skills"),
                                pii.decryptIfEncrypted(rs.getString("original_filename"))),
                        linkedCandidateId, workspaceId);
                if (!cands.isEmpty()) {
                    CandidateVirtualText c = cands.getFirst();
                    String text = c.rawText() != null && !c.rawText().isBlank() ? c.rawText()
                            : buildCandidateSummary(c);
                    LinkedHashMap<String, Object> virtual = new LinkedHashMap<>();
                    virtual.put("id", c.id().toString());
                    virtual.put("filename", (c.originalFilename() == null || c.originalFilename().isBlank())
                            ? (c.displayNameMasked() + "_人才库简历.txt") : c.originalFilename());
                    virtual.put("media_type", "text/plain");
                    virtual.put("size_bytes", (long) text.length());
                    virtual.put("text", text);
                    virtual.put("source", "candidate_library");
                    resumeList.add(virtual);
                }
            }
        }
        Map<String, Object> jobMap = new LinkedHashMap<>();
        UUID linkedJobId = jdbc.queryForObject("SELECT linked_job_id FROM recruitment_tasks WHERE id=? AND workspace_id=?",
                UUID.class, taskId, workspaceId);
        if (linkedJobId != null) {
            try {
                JobService.JobView job = jobs.get(userId, workspaceId, linkedJobId);
                jobMap.put("id", job.id().toString());
                jobMap.put("title", job.title());
                jobMap.put("company_name", job.companyName() == null ? "" : job.companyName());
                jobMap.put("location", job.location() == null ? "" : job.location());
                jobMap.put("experience_level", job.experienceLevel() == null ? "" : job.experienceLevel());
                jobMap.put("education", job.education() == null ? "" : job.education());
                jobMap.put("job_type", job.jobType() == null ? "" : job.jobType());
                jobMap.put("salary_range", job.salaryRange() == null ? "" : job.salaryRange());
                jobMap.put("skills", job.skills() == null ? "" : job.skills());
                jobMap.put("responsibilities", job.description() == null ? "" : job.description());
                jobMap.put("requirements", job.requirements() == null ? "" : job.requirements());
            } catch (ApiException notFound) {
                // 职位被删除或无权限时，降级为"无职位解析"
                log.warn("Resume parsing linked job {} not found for task {}: {}", linkedJobId, taskId, notFound.getMessage());
            }
        }
        String userPrompt = input == null ? "" : optional(input.requirement(), 4_000);
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "resumes", resumeList,
                "job", jobMap
        ));
        if (!userPrompt.isBlank()) payload.put("requirement", userPrompt);
        String payloadHash = hash(payload);
        List<ExistingReference> existing = jdbc.query("""
                SELECT id,input_hash AS request_hash FROM ai_runs WHERE workspace_id=? AND idempotency_key=?
                """, (rs, n) -> new ExistingReference(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                workspaceId, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().requestHash().equals(payloadHash)) throw idempotencyConflict();
            return detailScoped(workspaceId, taskId);
        }
        UUID runId = UUID.randomUUID();
        int attempt = nextAttempt(taskId);
        Instant now = Instant.now();
        long availableAmountMinor = billing.view(userId, workspaceId).availableAmountMinor();
        PolicyDecision policyDecision = flowCoordinator.evaluate(FlowCapability.RESUME_PARSING, scope, userId,
                availableAmountMinor, resolveResumePriceMinor(), null, true);
        ExecutionContext executionContext = flowCoordinator.createExecutionContext(policyDecision, taskId, key,
                "resume-parse:" + runId, List.of(new ExecutionContext.InputVersion("resume_payload",
                        taskId.toString(), "frozen", payloadHash)), false);
        jdbc.update("""
                INSERT INTO ai_runs
                (id,company_id,workspace_id,recruitment_task_id,capability,status,progress,attempt_number,
                 idempotency_key,input_hash,pricing_version,estimated_amount_minor,created_by,created_at,
                 input_payload,policy_decision,execution_context)
                VALUES (?,?,?,?, 'RESUME_PARSING','QUEUED',0,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                """, runId, scope.companyId(), workspaceId, taskId, attempt, key, payloadHash,
                RESUME_PARSING_PRICING_VERSION, resolveResumePriceMinor(), userId, timestamp(now), protectedPayload(payload),
                json(policyDecision), json(executionContext));
        String billingReference = "resume-parse:" + runId;
        billing.reserve(userId, workspaceId, billingReference, resolveResumePriceMinor());
        jdbc.update("""
                INSERT INTO outbox_events
                (id,aggregate_type,aggregate_id,event_type,payload,status,attempts,next_attempt_at,created_at)
                VALUES (?,'AI_RUN',?,'RESUME_PARSE_RUN_REQUESTED',?::jsonb,'PENDING',0,?,?)
                """, UUID.randomUUID(), runId.toString(), json(Map.of("run_id", runId.toString())),
                timestamp(now), timestamp(now));
        appendRunEvent(new RunExecution(runId, scope.companyId(), workspaceId, taskId, userId, task.conversationId(),
                key, "QUEUED", 0, null, json(payload), json(executionContext)), "status",
                Map.of("status", "QUEUED", "progress", 0));
        jdbc.update("UPDATE recruitment_tasks SET current_stage='RESUME_PARSING',updated_at=? WHERE id=?",
                timestamp(now), taskId);
        audit(userId, scope, "RESUME_PARSE_QUEUED", "AI_RUN", runId);
        return detailScoped(workspaceId, taskId);
    }

    /**
     * 面试出题：直接同步调用 InterviewService.create 生成面试题包。
     * 设计说明：面试出题耗时短（~3-15 秒，远低于 JD/简历解析的分钟级），同步 HTTP 往返比异步 outbox 队列更简单，
     * 同时也能让右侧 AI 助手以 QUEUED/RUNNING/COMPLETED 进度展示（插入 1 条 ai_runs 记录做状态同步）。
     * 如果任务没有 linked_job_id 或 linked_candidate_id，返回明确错误。
     */
    @Transactional
    public TaskDetail generateInterviewKit(UUID userId, UUID workspaceId, UUID taskId, String idempotencyKey,
                                           GenerateInterviewKitInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        String key = requiredIdempotencyKey(idempotencyKey);
        // 查 linked_job_id + linked_candidate_id（从创建任务时写入）
        List<UUID[]> linkedIds = jdbc.query("""
                SELECT linked_job_id, linked_candidate_id FROM recruitment_tasks WHERE id=? AND workspace_id=?
                """, (rs, n) -> new UUID[]{rs.getObject("linked_job_id", UUID.class),
                        rs.getObject("linked_candidate_id", UUID.class)}, taskId, workspaceId);
        if (linkedIds.isEmpty()) throw taskNotFound();
        UUID linkedJobId = linkedIds.getFirst()[0];
        UUID linkedCandidateId = linkedIds.getFirst()[1];
        if (linkedJobId == null) throw badRequest("JOB_REQUIRED", "请先关联职位再发起面试出题");
        if (linkedCandidateId == null) throw badRequest("CANDIDATE_REQUIRED", "请先选择人才再发起面试出题");
        // 用 JobService 查最新 currentVersionId（InterviewService.create 需要 jobVersionId）
        JobService.JobView jobView;
        try {
            jobView = jobs.get(userId, workspaceId, linkedJobId);
        } catch (ApiException cause) {
            throw new ApiException("INTERVIEW_JOB_INVALID", "关联职位不存在或已删除：" + cause.getMessage(), HttpStatus.BAD_REQUEST);
        }
        if (jobView.currentVersionId() == null) throw badRequest("JOB_VERSION_REQUIRED", "关联职位还未生成正式版本，请先确认 JD");

        // --- 构造 payload hash，保证幂等 ---
        int questionCount = input == null || input.questionCount() == null ? 8 : Math.max(4, Math.min(input.questionCount(), 20));
        Map<String, Object> payload = Map.of("jobId", linkedJobId.toString(),
                "jobVersionId", jobView.currentVersionId().toString(),
                "candidateId", linkedCandidateId.toString(),
                "questionCount", questionCount);
        String payloadHash = hash(payload);
        List<ExistingReference> existing = jdbc.query("""
                SELECT id,input_hash AS request_hash FROM ai_runs WHERE workspace_id=? AND idempotency_key=?
                """, (rs, n) -> new ExistingReference(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                workspaceId, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().requestHash().equals(payloadHash)) throw idempotencyConflict();
            return detailScoped(workspaceId, taskId);
        }

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        long availableAmountMinor = billing.view(userId, workspaceId).availableAmountMinor();
        PolicyDecision policyDecision = flowCoordinator.evaluate(FlowCapability.INTERVIEW_KIT_GENERATION, scope, userId,
                availableAmountMinor, 0L, null, true);
        ExecutionContext executionContext = flowCoordinator.createExecutionContext(policyDecision, taskId, key,
                "interview-kit:" + runId, List.of(new ExecutionContext.InputVersion("interview_kit_input",
                        taskId.toString(), "frozen", payloadHash)), false);
        jdbc.update("""
                INSERT INTO ai_runs
                (id,company_id,workspace_id,recruitment_task_id,capability,status,progress,attempt_number,
                 idempotency_key,input_hash,pricing_version,estimated_amount_minor,created_by,created_at,
                 input_payload,policy_decision,execution_context)
                VALUES (?,?,?,?, 'INTERVIEW_KIT_GENERATION','RUNNING',10,1,?,?,0,0,?,?,?::jsonb,?::jsonb,?::jsonb)
                """, runId, scope.companyId(), workspaceId, taskId, key, payloadHash, userId, timestamp(now),
                protectedPayload(payload), json(policyDecision), json(executionContext));
        appendRunEvent(new RunExecution(runId, scope.companyId(), workspaceId, taskId, userId, task.conversationId(),
                key, "RUNNING", 10, null, json(payload), json(executionContext)), "status",
                Map.of("status", "RUNNING", "progress", 10));
        jdbc.update("UPDATE recruitment_tasks SET current_stage='INTERVIEW_KIT_GENERATING',updated_at=? WHERE id=?",
                timestamp(now), taskId);

        InterviewService.KitDetail kit;
        try {
            kit = interviewService.create(userId, workspaceId,
                    new InterviewService.CreateInput(linkedCandidateId, jobView.currentVersionId(), null, questionCount));
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE ai_runs SET status='FAILED',progress=100,error_code=?,error_message=?,completed_at=?
                    WHERE id=?
                    """, "INTERVIEW_KIT_FAILED", optional(exception.getMessage(), 1000),
                    timestamp(Instant.now()), runId);
            appendRunEvent(new RunExecution(runId, scope.companyId(), workspaceId, taskId, userId, task.conversationId(),
                    key, "FAILED", 100, null, json(payload), json(executionContext)), "status",
                    Map.of("status", "FAILED", "progress", 100, "message", optional(exception.getMessage(), 500)));
            throw exception;
        }
        // 把面试题包摘要写入 1 条 ASSISTANT 消息，让右侧 AI 助手可直接看到结果
        StringBuilder summaryText = new StringBuilder(512);
        summaryText.append("✅ 已为你生成面试题包：").append(kit.matchSummary()).append("\n\n核心胜任力：");
        if (kit.coreCompetencies() != null) {
            for (int i = 0; i < kit.coreCompetencies().size(); i++) {
                InterviewService.CoreCompetency c = kit.coreCompetencies().get(i);
                if (c == null) continue;
                summaryText.append("\n").append(i + 1).append(". ").append(c.name());
                if (c.description() != null && !c.description().isBlank()) summaryText.append("：").append(c.description());
            }
        }
        summaryText.append("\n\n共生成 ").append(kit.questions() == null ? 0 : kit.questions().size()).append(" 道面试题（专业能力/项目实践/行为协作/场景决策），可在左侧详情区继续修改或使用。");
        insertMessage(scope, task.conversationId(), "ASSISTANT", summaryText.toString(), "INTERVIEW_KIT_GENERATION", null, Instant.now());

        jdbc.update("""
                UPDATE ai_runs SET status='COMPLETED',progress=100,completed_at=? WHERE id=?
                """, timestamp(Instant.now()), runId);
        appendRunEvent(new RunExecution(runId, scope.companyId(), workspaceId, taskId, userId, task.conversationId(),
                key, "COMPLETED", 100, null, json(payload), json(executionContext)), "status",
                Map.of("status", "COMPLETED", "progress", 100, "kitId", kit.id().toString()));
        audit(userId, scope, "INTERVIEW_KIT_GENERATED", "AI_RUN", runId);
        return detailScoped(workspaceId, taskId);
    }

    @Transactional
    public OutboxClaim claimNextResumeParseRun() {
        Instant now = Instant.now();
        List<OutboxClaim> rows = jdbc.query("""
                UPDATE outbox_events SET status='PROCESSING',attempts=attempts+1,next_attempt_at=?
                WHERE id=(SELECT id FROM outbox_events
                    WHERE event_type='RESUME_PARSE_RUN_REQUESTED'
                      AND ((status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                        OR (status='PROCESSING' AND next_attempt_at<=?))
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id,aggregate_id,attempts
                """, (rs, n) -> new OutboxClaim(rs.getObject("id", UUID.class),
                UUID.fromString(rs.getString("aggregate_id")), rs.getInt("attempts")),
                timestamp(now.plus(outboxLeaseSeconds, ChronoUnit.SECONDS)), timestamp(now), timestamp(now));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public boolean prepareResumeParseRun(UUID runId) {
        RunExecution run = runExecution(runId, true);
        if (!List.of("QUEUED", "RUNNING").contains(run.status())) return false;
        if ("QUEUED".equals(run.status())) {
            Map<String, Object> aiInput = payloadMap(run.inputPayload());
            AiTask aiTask = aiPlatform.startTask(new StartAiTaskCommand(run.workspaceId().toString(),
                    run.companyId() == null ? null : run.companyId().toString(), run.createdBy().toString(),
                    run.taskId().toString(), run.idempotencyKey(), AiCapability.RESUME_PARSING, aiInput,
                    executionContext(run.executionContext())), delta -> emitResumeParseDelta(run.id(), delta));
            jdbc.update("UPDATE ai_runs SET status='RUNNING',progress=15,provider_task_id=? WHERE id=?",
                    aiTask.aiTaskId(), run.id());
            appendRunEvent(run, "status", Map.of("status", "RUNNING", "progress", 15));
        }
        return true;
    }

    @Transactional
    public void emitResumeParseDelta(UUID runId, String delta) {
        RunExecution run = runExecution(runId, true);
        if (!List.of("QUEUED", "RUNNING").contains(run.status())) return;
        int progress = Math.min(95, Math.max(15, run.progress() + 3));
        jdbc.update("UPDATE ai_runs SET progress=? WHERE id=? AND status IN ('QUEUED','RUNNING')", progress, run.id());
        appendRunEvent(run, "delta", Map.of("delta", delta, "progress", progress));
    }

    public List<UUID> runningResumeParseRunIds() {
        return jdbc.query("SELECT id FROM ai_runs WHERE capability='RESUME_PARSING' AND status='RUNNING' ORDER BY created_at LIMIT 50",
                (rs, n) -> rs.getObject(1, UUID.class));
    }

    @Transactional
    public void finalizeResumeParseRunIfReady(UUID runId) {
        RunExecution run = runExecution(runId, true);
        if (!"RUNNING".equals(run.status()) || run.providerTaskId() == null) return;
        AiTask task = aiPlatform.getTask(run.providerTaskId());
        if (task.status() == AiTaskStatus.FAILED || task.status() == AiTaskStatus.CANCELLED) {
            failResumeParseRun(run, "AI_PROVIDER_UNAVAILABLE", "AI 简历解析失败，请重试");
            return;
        }
        if (task.status() == AiTaskStatus.COMPLETED) finalizeResumeParseRun(runId);
    }

    @Transactional
    public void finalizeResumeParseRun(UUID runId) {
        RunExecution run = runExecution(runId, true);
        if (!"RUNNING".equals(run.status())) return;
        StructuredResult result = aiPlatform.getStructuredResult(run.providerTaskId());
        Map<String, Object> data = result.data();
        Object markdownObj = data.get("markdown");
        String markdown = markdownObj == null ? "" : String.valueOf(markdownObj).trim();
        if (markdown.isBlank()) markdown = fallbackMarkdownForResumeParse(run.taskId());
        WorkspaceScope scope = new WorkspaceScope(run.workspaceId(), run.companyId(), null, null, null);
        Instant completed = Instant.now();
        // version 插入：有草稿则生成下一版，否则 V1
        Integer currentMax = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision),0) FROM resume_parse_drafts WHERE recruitment_task_id=? AND workspace_id=?
                """, Integer.class, run.taskId(), run.workspaceId());
        int nextRevision = (currentMax == null ? 0 : currentMax) + 1;
        jdbc.update("""
                INSERT INTO resume_parse_drafts
                (id,company_id,workspace_id,recruitment_task_id,source_ai_run_id,revision,content,status,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'DRAFT',?,?,?)
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), run.taskId(), run.id(),
                nextRevision, pii.encrypt(markdown), run.createdBy(), timestamp(completed), timestamp(completed));
        billing.settleSystem(run.workspaceId(), "resume-parse:" + run.id(), resolveResumePriceMinor());
        jdbc.update("UPDATE ai_runs SET status='COMPLETED',progress=100,settled_amount_minor=?,completed_at=? WHERE id=?",
                resolveResumePriceMinor(), timestamp(completed), run.id());
        insertMessage(scope, run.conversationId(), "ASSISTANT",
                "简历解析已完成，结果已写入左侧「解析结果」文本框，你可以直接编辑并保存版本。"
                        + (run.taskId().version() > 0 ? "" : ""),
                "RESUME_PARSING", null, completed);
        jdbc.update("UPDATE recruitment_tasks SET current_stage='AWAITING_RESUME_PARSE_CONFIRM',updated_at=? WHERE id=?",
                timestamp(completed), run.taskId());
        appendRunEvent(run, "completed", Map.of("status", "COMPLETED", "progress", 100));
        audit(run.createdBy(), scope, "RESUME_PARSE_DRAFT_GENERATED", "AI_RUN", run.id());
    }

    private String fallbackMarkdownForResumeParse(UUID taskId) {
        return "## AI 返回内容为空\n\n可能是大模型供应商临时不可用或输出为空。请在右侧 AI 招聘助手中继续沟通，"
                + "或点击「重新解析」按钮重试。\n\n也可以直接在下方文本框手动编辑解析结果，保存后即成为 V1 版本草稿。";
    }

    @Transactional
    public void completeResumeParseOutbox(UUID eventId) {
        jdbc.update("UPDATE outbox_events SET status='SENT',sent_at=? WHERE id=?", timestamp(Instant.now()), eventId);
    }

    @Transactional
    public void failResumeParseOutbox(OutboxClaim claim, String error) {
        if (claim.attempts() < 3) {
            jdbc.update("UPDATE outbox_events SET status='PENDING',next_attempt_at=? WHERE id=?",
                    timestamp(Instant.now().plus(claim.attempts(), ChronoUnit.SECONDS)), claim.eventId());
            return;
        }
        RunExecution run = runExecution(claim.runId(), true);
        failResumeParseRun(run, "WORKER", error);
        jdbc.update("UPDATE outbox_events SET status='FAILED',sent_at=? WHERE id=?", timestamp(Instant.now()), claim.eventId());
    }

    private void failResumeParseRun(RunExecution run, String code, String detail) {
        Instant completed = Instant.now();
        billing.settleSystem(run.workspaceId(), "resume-parse:" + run.id(), 0);
        String message = "WORKER".equals(code) ? "简历解析任务执行失败，请重试" : detail;
        jdbc.update("""
                UPDATE ai_runs SET status='FAILED',progress=100,error_code=?,error_message=?,completed_at=? WHERE id=?
                """, code, message, timestamp(completed), run.id());
        WorkspaceScope scope = new WorkspaceScope(run.workspaceId(), run.companyId(), null, null, null);
        insertMessage(scope, run.conversationId(), "SYSTEM", "简历解析失败：" + message, "RESUME_PARSING", null, completed);
        appendRunEvent(run, "failed", Map.of("status", "FAILED", "progress", 100, "error_code", code, "error_message", message));
    }

    /** 返回简历源文件的临时下载/预览 URL，供前端直接打开。 */
    public String downloadResumeSourceFileUrl(UUID userId, UUID workspaceId, UUID sourceFileId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        ResumeSourceFileService.AssetDownloadView asset = resumeSourceFiles.requireForDownload(workspaceId, sourceFileId);
        return resumeSourceFiles.downloadUrl(asset);
    }

    private List<Map<String, String>> conversationContext(UUID conversationId, UUID workspaceId) {
        return jdbc.query("""
                SELECT role,content FROM (
                    SELECT role,content,sequence_number FROM messages
                    WHERE conversation_id=? AND workspace_id=?
                    ORDER BY sequence_number DESC LIMIT 30
                ) recent ORDER BY sequence_number
                """, (rs, n) -> Map.of("role", rs.getString("role"), "content", pii.decryptIfEncrypted(rs.getString("content"))),
                conversationId, workspaceId);
    }

    private Map<String, Object> jdDraftContext(UUID workspaceId, UUID taskId, UUID draftId) {
        List<JdDraftView> drafts = draftRows(workspaceId, taskId);
        if (drafts.isEmpty()) return Map.of();
        JdDraftView draft = drafts.stream().filter(item -> draftId == null || item.id().equals(draftId)).findFirst().orElse(drafts.getFirst());
        return Map.ofEntries(Map.entry("title", draft.title()), Map.entry("company_name", draft.companyName()),
                Map.entry("salary_range", draft.salaryRange()), Map.entry("responsibilities", draft.responsibilities()), Map.entry("requirements", draft.requirements()),
                Map.entry("skills", draft.skills()), Map.entry("nice_to_haves", draft.niceToHaves()), Map.entry("benefits", draft.benefits()), Map.entry("talent_profile", draft.talentProfile()),
                Map.entry("status", draft.status()));
    }

    private static String optionalAssistantMessage(StructuredResult result) {
        Object value = result.data().get("assistant_message");
        String message = value == null ? "" : String.valueOf(value).trim();
        return message.isBlank() ? "已根据你的最新要求更新当前 JD 草稿，请查看左侧内容。" : message;
    }

    private void upsertDraft(WorkspaceScope scope, UUID taskId, UUID runId, UUID userId, JdDraftContent draft) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO jd_drafts
                (id,company_id,workspace_id,recruitment_task_id,source_ai_run_id,revision,title,company_name,
                 location,experience_level,education,job_type,salary_range,responsibilities,requirements,skills,nice_to_haves,benefits,talent_profile,
                 warnings,status,updated_by,created_at,updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'DRAFT', ?, ?, ?)
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), taskId, runId, draft.title(),
                draft.companyName(), draft.location(), draft.experienceLevel(), draft.education(), draft.jobType(), draft.salaryRange(),
                draft.responsibilities(), draft.requirements(), draft.skills(), draft.niceToHaves(), draft.benefits(), draft.talentProfile(),
                json(draft.warnings()), userId, timestamp(now), timestamp(now));
    }

    private void updateDraftInPlace(WorkspaceScope scope, UUID taskId, UUID draftId, UUID userId, JdDraftContent draft) {
        int updated = jdbc.update("""
                UPDATE jd_drafts SET status=CASE WHEN status='CONFIRMED' THEN 'DRAFT' ELSE status END,title=?,company_name=?,location=?,experience_level=?,education=?,job_type=?,salary_range=?,
                 responsibilities=?,requirements=?,skills=?,nice_to_haves=?,benefits=?,talent_profile=?,warnings=?::jsonb,updated_by=?,updated_at=?
                WHERE id=? AND recruitment_task_id=? AND workspace_id=? AND status IN ('DRAFT','CONFIRMED')
                """, draft.title(), draft.companyName(), draft.location(), draft.experienceLevel(), draft.education(),
                draft.jobType(), draft.salaryRange(), draft.responsibilities(), draft.requirements(), draft.skills(), draft.niceToHaves(), draft.benefits(), draft.talentProfile(),
                json(draft.warnings()), userId, timestamp(Instant.now()), draftId, taskId, scope.workspaceId());
        if (updated == 0) throw new ApiException("JD_DRAFT_NOT_FOUND", "当前 JD 不存在，无法更新", HttpStatus.CONFLICT);
    }

    private void insertAdditionalDraft(WorkspaceScope scope, UUID taskId, UUID userId, JdDraftContent draft) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO jd_drafts (id,company_id,workspace_id,recruitment_task_id,revision,title,company_name,
                  location,experience_level,education,job_type,salary_range,responsibilities,requirements,skills,nice_to_haves,benefits,talent_profile,
                  warnings,status,updated_by,created_at,updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'DRAFT', ?, ?, ?)
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), taskId, draft.title(), draft.companyName(),
                draft.location(), draft.experienceLevel(), draft.education(), draft.jobType(), draft.salaryRange(), draft.responsibilities(),
                draft.requirements(), draft.skills(), draft.niceToHaves(), draft.benefits(), draft.talentProfile(), json(draft.warnings()), userId,
                timestamp(now), timestamp(now));
    }


    private void insertMessage(WorkspaceScope scope, UUID conversationId, String role, String content,
                               String capability, UUID createdBy, Instant now) {
        jdbc.queryForObject("SELECT id FROM conversations WHERE id=? FOR UPDATE", UUID.class, conversationId);
        Integer sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence_number),0)+1 FROM messages WHERE conversation_id=?
                """, Integer.class, conversationId);
        jdbc.update("""
                INSERT INTO messages
                (id,company_id,workspace_id,conversation_id,role,content,capability,sequence_number,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), conversationId, role, pii.encrypt(content),
                capability, sequence == null ? 1 : sequence, createdBy, timestamp(now));
        jdbc.update("UPDATE conversations SET updated_at=? WHERE id=?", timestamp(now), conversationId);
    }

    private RunExecution runExecution(UUID runId, boolean lock) {
        String suffix = lock ? " FOR UPDATE OF r" : "";
        List<RunExecution> rows = jdbc.query("""
                SELECT r.id,r.company_id,r.workspace_id,r.recruitment_task_id,r.created_by,
                       c.id AS conversation_id,r.idempotency_key,r.status,r.progress,r.provider_task_id,
                       r.input_payload::text,r.execution_context::text
                FROM ai_runs r JOIN conversations c ON c.recruitment_task_id=r.recruitment_task_id
                WHERE r.id=?
                """ + suffix, (rs, n) -> new RunExecution(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("recruitment_task_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("status"), rs.getInt("progress"), rs.getString("provider_task_id"),
                rs.getString("input_payload"), rs.getString("execution_context")), runId);
        if (rows.isEmpty()) throw new IllegalStateException("JD run not found: " + runId);
        return rows.getFirst();
    }

    private void emitDelta(RunExecution run, int progress, String delta) {
        jdbc.update("UPDATE ai_runs SET progress=? WHERE id=? AND status='RUNNING'", progress, run.id());
        appendRunEvent(run, "delta", Map.of("delta", delta, "progress", progress));
    }

    private void failJdRun(RunExecution run, String scenario) {
        String code = "TIMEOUT".equals(scenario) ? "AI_TIMEOUT" : "AI_SCHEMA_INVALID";
        String message = "TIMEOUT".equals(scenario) ? "AI 生成超时，请重试" : "AI 返回结构不合法，请重试";
        failJdRun(run, code, message);
    }

    private void failJdRun(RunExecution run, String code, String detail) {
        String message = "WORKER".equals(code) ? "JD 生成任务执行失败，请重试" : detail;
        Instant completed = Instant.now();
        billing.settleSystem(run.workspaceId(), "jd-run:" + run.id(), 0);
        jdbc.update("""
                UPDATE ai_runs SET status='FAILED',progress=100,error_code=?,error_message=?,completed_at=? WHERE id=?
                """, code, message, timestamp(completed), run.id());
        WorkspaceScope scope = new WorkspaceScope(run.workspaceId(), run.companyId(), null, null, null);
        insertMessage(scope, run.conversationId(), "ASSISTANT", message, "JD_GENERATION", null, completed);
        jdbc.update("UPDATE recruitment_tasks SET current_stage='JD_GENERATION_FAILED',updated_at=? WHERE id=?",
                timestamp(completed), run.taskId());
        appendRunEvent(run, "failed", Map.of("status", "FAILED", "progress", 100,
                "errorCode", code, "message", message));
        audit(run.createdBy(), scope, "JD_GENERATION_FAILED", "AI_RUN", run.id());
    }

    private void appendRunEvent(RunExecution run, String eventType, Map<String, ?> data) {
        jdbc.update("""
                INSERT INTO jd_run_events
                (run_id,company_id,workspace_id,recruitment_task_id,event_type,data,created_at)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """, run.id(), run.companyId(), run.workspaceId(), run.taskId(), eventType, json(data),
                timestamp(Instant.now()));
    }

    private Map<String, String> stringMap(String value) {
        Map<String, Object> source = payloadMap(value);
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(key, item == null ? "" : String.valueOf(item)));
        return result;
    }

    private Map<String, Object> payloadMap(String value) {
        try {
            Map<String, Object> source = objectMapper.readValue(value, new TypeReference<Map<String, Object>>() { });
            Object encrypted = source.get("_encrypted");
            if (encrypted instanceof String ciphertext) {
                return new LinkedHashMap<>(objectMapper.readValue(pii.decryptIfEncrypted(ciphertext), new TypeReference<Map<String, Object>>() { }));
            }
            return new LinkedHashMap<>(source);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JD run input is invalid", exception);
        }
    }

    private String protectedPayload(Map<String, Object> payload) {
        return json(Map.of("_encrypted", pii.encrypt(json(payload))));
    }

    private ExecutionContext executionContext(String value) {
        try {
            return objectMapper.readValue(value, ExecutionContext.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JD execution context is invalid", exception);
        }
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int nextAttempt(UUID taskId) {
        Integer attempt = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number),0)+1 FROM ai_runs WHERE recruitment_task_id=?
                """, Integer.class, taskId);
        return attempt == null ? 1 : attempt;
    }

    private void audit(UUID actor, WorkspaceScope scope, String action, String resourceType, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,company_id,workspace_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), actor, scope.companyId(), scope.workspaceId(), action, resourceType,
                resourceId.toString(), timestamp(Instant.now()));
    }

    private String hash(Object value) {
        try {
            return SecurityHashes.sha256(objectMapper.writeValueAsString(value == null ? Map.of() : value));
        } catch (JsonProcessingException exception) {
            throw validation("请求内容无法处理");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw validation("结构化内容无法保存");
        }
    }

    private List<String> parseWarnings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ApiException("JD_DRAFT_CORRUPTED", "JD 草稿结构异常", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static String requiredIdempotencyKey(String key) {
        return required(key, "缺少 Idempotency-Key", 200);
    }

    private static String normalizedScenario(String scenario) {
        String value = scenario == null || scenario.isBlank() ? "NORMAL" : scenario.trim().toUpperCase();
        if (!List.of("NORMAL", "TIMEOUT", "INVALID_SCHEMA").contains(value)) throw validation("无效的 Mock 场景");
        return value;
    }

    private static String required(String value, String message, int max) {
        if (value == null || value.isBlank()) throw validation(message);
        String clean = value.trim();
        if (clean.length() > max) throw validation(message + "且不能超过" + max + "字");
        return clean;
    }

    private static String optional(String value, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > max) throw validation("字段内容过长");
        return clean;
    }

    private static String defaulted(String value, String fallback, int max) {
        return optional(value == null || value.isBlank() ? fallback : value, max);
    }

    private static <T> String value(T object, java.util.function.Function<T, String> getter) {
        return object == null ? null : getter.apply(object);
    }

    private static ApiException validation(String message) {
        return new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    private static ApiException taskNotFound() {
        return new ApiException("RECRUITMENT_TASK_NOT_FOUND", "招聘任务不存在", HttpStatus.NOT_FOUND);
    }

    private static ApiException idempotencyConflict() {
        return new ApiException("IDEMPOTENCY_CONFLICT", "相同幂等键对应的请求内容不一致", HttpStatus.CONFLICT);
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(code, message, HttpStatus.BAD_REQUEST);
    }

    public record CreateTaskInput(String title, String initialRequirement, String featureType, String linkedJobId, String linkedCandidateId) { }

    public record RenameTaskInput(String title) { }

    public record MessageInput(String content, UUID jdDraftId) { }

    public record RouteMessageInput(String message) { }

    public record GenerateJdInput(String requirement, String title, String companyName, String location,
                                  String experienceLevel, String education, String jobType, String skills,
                                  String scenario) { }

    public record UpdateDraftInput(UUID id, int revision, String title, String companyName, String location,
                                   String experienceLevel, String education, String jobType,
                                   String salaryRange, String responsibilities, String requirements, String skills, String niceToHaves, String benefits,
                                   String talentProfile, List<String> warnings) { }

    public record UpdateResumeParseDraftInput(int revision, String content) { }

    public record GenerateResumeParseInput(String requirement) { }

    public record GenerateInterviewKitInput(Integer questionCount) { }

    public record TaskSummary(UUID id, UUID companyId, UUID workspaceId, String title, String status,
                              String currentStage, String featureType, UUID linkedJobId, UUID linkedCandidateId,
                              UUID jobId, String jobTitle, UUID createdBy,
                              Instant createdAt, Instant updatedAt) { }

    public record MessageView(UUID id, String role, String content, String capability, int sequenceNumber,
                              UUID createdBy, Instant createdAt) { }

    public record JdDraftView(UUID id, int revision, String title, String companyName, String location,
                              String experienceLevel, String education, String jobType, String salaryRange, String responsibilities,
                              String requirements, String skills, String niceToHaves, String benefits, String talentProfile, List<String> warnings,
                              String status, Instant updatedAt) { }

    public record AiRunView(UUID id, String providerTaskId, String status, int progress, int attemptNumber,
                            String pricingVersion, long estimatedAmountMinor, long settledAmountMinor,
                            String errorCode, String errorMessage, Instant createdAt, Instant completedAt) { }

    public record ResumeSourceFileView(UUID id, UUID fileAssetId, String filename, String mediaType, long sizeBytes,
                                       Instant createdAt) { }

    public record ResumeParseDraftView(UUID id, int revision, UUID sourceAiRunId, UUID resumeSourceFileId,
                                       String content, String status, UUID createdBy,
                                       Instant createdAt, Instant updatedAt) { }

    public record TaskDetail(TaskSummary task, UUID conversationId, List<MessageView> messages, List<JdDraftView> jdDrafts,
                             JdDraftView jdDraft, AiRunView latestAiRun,
                             List<ResumeSourceFileView> resumeSourceFiles, List<ResumeParseDraftView> resumeParseDrafts,
                             ResumeParseDraftView resumeParseDraft) { }

    public record RunEvent(long eventId, UUID runId, String eventType, String data, Instant createdAt) { }

    public record SourceFileView(UUID id, UUID fileAssetId, String filename, String mediaType, long sizeBytes,
                                 Instant createdAt) { }

    public record OutboxClaim(UUID eventId, UUID runId, int attempts) { }

    private void updateLegacyDefaultTaskTitle(TaskRow task, String requirement, Instant now) {
        if (!LEGACY_DEFAULT_TASK_TITLE.equals(task.title())) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:招聘|招募|招)(?:一名|1名)?\\s*([^，。；、,.!?！？]{2,40})")
                .matcher(requirement);
        if (!matcher.find()) return;
        String suggested = matcher.group(1).trim() + "招聘";
        if (suggested.equals(LEGACY_DEFAULT_TASK_TITLE)) return;
        jdbc.update("UPDATE recruitment_tasks SET title=?,updated_at=? WHERE id=?", suggested, timestamp(now), task.id());
    }

    private record ExistingReference(UUID id, String requestHash) { }

    private record CandidateVirtualText(UUID id, String displayNameMasked, String rawText, String headline,
                                        int yearsExperience, String highestEducation, String skillsJson,
                                        String originalFilename) { }

    /** 候选人 raw_text 为空时，拼一段结构化摘要作为虚拟简历文本，避免 LLM 只提示"请先上传简历" */
    private String buildCandidateSummary(CandidateVirtualText c) {
        List<String> skills = new ArrayList<>();
        try { skills = objectMapper.readValue(c.skillsJson() == null ? "[]" : c.skillsJson(), new TypeReference<List<String>>() {}); } catch (JsonProcessingException ignore) {}
        StringBuilder sb = new StringBuilder();
        sb.append("【候选人摘要】\n");
        sb.append("姓名：").append(c.displayNameMasked() == null ? "候选人" : c.displayNameMasked()).append("\n");
        if (c.headline() != null && !c.headline().isBlank()) sb.append("简介：").append(c.headline()).append("\n");
        sb.append("工作年限：").append(c.yearsExperience() <= 0 ? "待确认" : c.yearsExperience() + "年").append("\n");
        sb.append("最高学历：").append(c.highestEducation() == null || c.highestEducation().isBlank() ? "待确认" : c.highestEducation()).append("\n");
        sb.append("核心技能：").append(skills.isEmpty() ? "待确认" : String.join("、", skills)).append("\n");
        if (c.originalFilename() != null && !c.originalFilename().isBlank()) sb.append("简历文件名：").append(c.originalFilename()).append("\n");
        return sb.toString();
    }

    private record TaskRow(UUID id, String title, String initialRequirement, UUID conversationId) { }

    private record RunExecution(UUID id, UUID companyId, UUID workspaceId, UUID taskId, UUID createdBy,
                                UUID conversationId, String idempotencyKey, String status, int progress,
                                String providerTaskId, String inputPayload, String executionContext) { }
}
