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
import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.jobs.application.JobService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class RecruitmentService {

    private static final Logger log = LoggerFactory.getLogger(RecruitmentService.class);
    private static final String JD_PRICING_VERSION = "JD_MOCK_V1";
    private static final String LEGACY_DEFAULT_TASK_TITLE = "高级 Java 开发工程师招聘";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessService workspaceAccess;
    private final BillingService billing;
    private final RecruitmentFlowCoordinator flowCoordinator;
    private final AiPlatformClient aiPlatform;
    private final JdStructuredResultMapper structuredResultMapper;
    private final JobService jobs;
    private final long jdPriceMinor;
    private final long outboxLeaseSeconds;

    public RecruitmentService(JdbcTemplate jdbc, ObjectMapper objectMapper, WorkspaceAccessService workspaceAccess,
                              BillingService billing, RecruitmentFlowCoordinator flowCoordinator,
                              AiPlatformClient aiPlatform,
                              JdStructuredResultMapper structuredResultMapper,
                              JobService jobs,
                              @Value("${app.phase3.jd-generation-price-minor:80}") long jdPriceMinor,
                              @Value("${app.phase3.outbox-lease-seconds:300}") long outboxLeaseSeconds) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workspaceAccess = workspaceAccess;
        this.billing = billing;
        this.flowCoordinator = flowCoordinator;
        this.aiPlatform = aiPlatform;
        this.structuredResultMapper = structuredResultMapper;
        this.jobs = jobs;
        this.jdPriceMinor = jdPriceMinor;
        this.outboxLeaseSeconds = outboxLeaseSeconds;
    }

    @Transactional
    public TaskDetail createTask(UUID userId, UUID workspaceId, String idempotencyKey, CreateTaskInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        String key = requiredIdempotencyKey(idempotencyKey);
        if (input == null) throw validation("招聘任务不能为空");
        String title = required(input.title(), "招聘任务名称不能为空", 200);
        String requirement = required(input.initialRequirement(), "请描述招聘需求", 20_000);
        String requestHash = SecurityHashes.sha256(title + "\n" + requirement);
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
                 request_hash,created_by,created_at,updated_at)
                VALUES (?,?,?, ?,?,'ACTIVE','COLLECTING_REQUIREMENTS',?,?, ?,?,?)
                """, taskId, scope.companyId(), workspaceId, title, requirement, key, requestHash, userId,
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
                SELECT t.id,t.company_id,t.workspace_id,t.title,t.status,t.current_stage,t.created_by,
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
                rs.getObject("job_id", UUID.class), rs.getString("job_title"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), workspaceId);
    }

    public TaskDetail getTask(UUID userId, UUID workspaceId, UUID taskId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return detailScoped(workspaceId, taskId);
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
                WHERE id=? AND workspace_id=? AND current_stage<>'JD_CONFIRMED'
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
                message, List.of(FlowCapability.JD_GENERATION, FlowCapability.RESUME_PARSING,
                        FlowCapability.SCREENING_PLAN_GENERATION, FlowCapability.CANDIDATE_SCREENING,
                        FlowCapability.INTERVIEW_KIT_GENERATION, FlowCapability.TASK_ASSISTANCE)));
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
                availableAmountMinor, jdPriceMinor, null, true);
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
                JD_PRICING_VERSION, jdPriceMinor, userId, timestamp(now), json(Map.of(
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
        billing.reserve(userId, workspaceId, billingReference, jdPriceMinor);
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
            Map<String, Object> aiInput = new LinkedHashMap<>();
            aiInput.put("requirement", input.get("requirement"));
            aiInput.put("scenario", input.get("scenario"));
            AiTask aiTask = aiPlatform.startTask(new StartAiTaskCommand(run.workspaceId().toString(),
                    run.companyId() == null ? null : run.companyId().toString(), run.createdBy().toString(),
                    run.taskId().toString(), run.idempotencyKey(), AiCapability.JD_GENERATION, aiInput,
                    executionContext(run.executionContext())));
            jdbc.update("UPDATE ai_runs SET status='RUNNING',progress=15,provider_task_id=? WHERE id=?",
                    aiTask.aiTaskId(), run.id());
            appendRunEvent(run, "status", Map.of("status", "RUNNING", "progress", 15));
        }
        return true;
    }

    @Transactional
    public void emitJdDelta(UUID runId, int progress, String delta) {
        RunExecution run = runExecution(runId, true);
        if (!"RUNNING".equals(run.status())) return;
        emitDelta(run, progress, delta);
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
        billing.settleSystem(run.workspaceId(), "jd-run:" + run.id(), jdPriceMinor);
        jdbc.update("UPDATE ai_runs SET status='COMPLETED',progress=100,settled_amount_minor=?,completed_at=? WHERE id=?",
                jdPriceMinor, timestamp(completed), run.id());
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
                    education=?,job_type=?,responsibilities=?,requirements=?,skills=?,talent_profile=?,warnings=?::jsonb,
                    updated_by=?,updated_at=?
                WHERE id=? AND recruitment_task_id=? AND workspace_id=? AND revision=? AND status IN ('DRAFT','CONFIRMED')
                """, required(input.title(), "职位名称不能为空", 200),
                required(input.companyName(), "企业名称不能为空", 200), optional(input.location(), 200),
                optional(input.experienceLevel(), 80), optional(input.education(), 80),
                defaulted(input.jobType(), "全职", 50), optional(input.responsibilities(), 20_000),
                optional(input.requirements(), 20_000), optional(input.skills(), 4_000),
                optional(input.talentProfile(), 10_000), warnings, userId, timestamp(Instant.now()), input.id(), taskId,
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
                draft.responsibilities(), draft.requirements(), draft.skills(), draft.experienceLevel(),
                draft.education(), draft.jobType());
        JobService.JobView job = jobs.createFromConfirmedJd(userId, workspaceId, taskId, draft.id(), sourceAiRunId, jobInput,
                draft.talentProfile(), json(draft.warnings()));
        Instant now = Instant.now();
        jdbc.update("UPDATE jd_drafts SET status='CONFIRMED',updated_by=?,updated_at=? WHERE id=?",
                userId, timestamp(now), draft.id());
        jdbc.update("""
                UPDATE recruitment_tasks SET status='COMPLETED',current_stage='JD_CONFIRMED',updated_at=? WHERE id=?
                """, timestamp(now), taskId);
        insertMessage(scope, task.conversationId(), "ASSISTANT",
                "JD 已确认并进入职位库，后续修改将创建新的职位版本。", "JD_GENERATION", null, now);
        audit(userId, scope, "JD_CONFIRMED", "RECRUITMENT_TASK", taskId);
        return job;
    }

    private TaskDetail detailScoped(UUID workspaceId, UUID taskId) {
        List<TaskSummary> summaries = jdbc.query("""
                SELECT t.id,t.company_id,t.workspace_id,t.title,t.status,t.current_stage,t.created_by,
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
                rs.getString("content"), rs.getString("capability"), rs.getInt("sequence_number"),
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
        return new TaskDetail(summaries.getFirst(), conversationId, messages, drafts, draft,
                runs.isEmpty() ? null : runs.getFirst());
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
                SELECT id,revision,title,company_name,location,experience_level,education,job_type,
                       responsibilities,requirements,skills,talent_profile,warnings::text,status,updated_at
                FROM jd_drafts WHERE recruitment_task_id=? AND workspace_id=? ORDER BY created_at
                """, (rs, n) -> new JdDraftView(rs.getObject("id", UUID.class), rs.getInt("revision"),
                rs.getString("title"), rs.getString("company_name"), rs.getString("location"),
                rs.getString("experience_level"), rs.getString("education"), rs.getString("job_type"),
                rs.getString("responsibilities"), rs.getString("requirements"), rs.getString("skills"),
                rs.getString("talent_profile"), parseWarnings(rs.getString("warnings")), rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()), taskId, workspaceId);
    }

    private List<Map<String, String>> conversationContext(UUID conversationId, UUID workspaceId) {
        return jdbc.query("""
                SELECT role,content FROM (
                    SELECT role,content,sequence_number FROM messages
                    WHERE conversation_id=? AND workspace_id=?
                    ORDER BY sequence_number DESC LIMIT 30
                ) recent ORDER BY sequence_number
                """, (rs, n) -> Map.of("role", rs.getString("role"), "content", rs.getString("content")),
                conversationId, workspaceId);
    }

    private Map<String, Object> jdDraftContext(UUID workspaceId, UUID taskId, UUID draftId) {
        List<JdDraftView> drafts = draftRows(workspaceId, taskId);
        if (drafts.isEmpty()) return Map.of();
        JdDraftView draft = drafts.stream().filter(item -> draftId == null || item.id().equals(draftId)).findFirst().orElse(drafts.getFirst());
        return Map.ofEntries(Map.entry("title", draft.title()), Map.entry("company_name", draft.companyName()),
                Map.entry("responsibilities", draft.responsibilities()), Map.entry("requirements", draft.requirements()),
                Map.entry("skills", draft.skills()), Map.entry("talent_profile", draft.talentProfile()),
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
                 location,experience_level,education,job_type,responsibilities,requirements,skills,talent_profile,
                 warnings,status,updated_by,created_at,updated_at)
                VALUES (?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?::jsonb,'DRAFT',?,?,?)
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), taskId, runId, draft.title(),
                draft.companyName(), draft.location(), draft.experienceLevel(), draft.education(), draft.jobType(),
                draft.responsibilities(), draft.requirements(), draft.skills(), draft.talentProfile(),
                json(draft.warnings()), userId, timestamp(now), timestamp(now));
    }

    private void updateDraftInPlace(WorkspaceScope scope, UUID taskId, UUID draftId, UUID userId, JdDraftContent draft) {
        int updated = jdbc.update("""
                UPDATE jd_drafts SET status=CASE WHEN status='CONFIRMED' THEN 'DRAFT' ELSE status END,title=?,company_name=?,location=?,experience_level=?,education=?,job_type=?,
                 responsibilities=?,requirements=?,skills=?,talent_profile=?,warnings=?::jsonb,updated_by=?,updated_at=?
                WHERE id=? AND recruitment_task_id=? AND workspace_id=? AND status IN ('DRAFT','CONFIRMED')
                """, draft.title(), draft.companyName(), draft.location(), draft.experienceLevel(), draft.education(),
                draft.jobType(), draft.responsibilities(), draft.requirements(), draft.skills(), draft.talentProfile(),
                json(draft.warnings()), userId, timestamp(Instant.now()), draftId, taskId, scope.workspaceId());
        if (updated == 0) throw new ApiException("JD_DRAFT_NOT_FOUND", "当前 JD 不存在，无法更新", HttpStatus.CONFLICT);
    }

    private void insertAdditionalDraft(WorkspaceScope scope, UUID taskId, UUID userId, JdDraftContent draft) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO jd_drafts (id,company_id,workspace_id,recruitment_task_id,revision,title,company_name,
                  location,experience_level,education,job_type,responsibilities,requirements,skills,talent_profile,
                  warnings,status,updated_by,created_at,updated_at)
                VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?::jsonb,'DRAFT',?,?,?)
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), taskId, draft.title(), draft.companyName(),
                draft.location(), draft.experienceLevel(), draft.education(), draft.jobType(), draft.responsibilities(),
                draft.requirements(), draft.skills(), draft.talentProfile(), json(draft.warnings()), userId,
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
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), conversationId, role, content,
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
        try {
            Map<String, Object> source = objectMapper.readValue(value, new TypeReference<>() { });
            Map<String, String> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(key, item == null ? "" : String.valueOf(item)));
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JD run input is invalid", exception);
        }
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

    public record CreateTaskInput(String title, String initialRequirement) { }

    public record RenameTaskInput(String title) { }

    public record MessageInput(String content, UUID jdDraftId) { }

    public record RouteMessageInput(String message) { }

    public record GenerateJdInput(String requirement, String title, String companyName, String location,
                                  String experienceLevel, String education, String jobType, String skills,
                                  String scenario) { }

    public record UpdateDraftInput(UUID id, int revision, String title, String companyName, String location,
                                   String experienceLevel, String education, String jobType,
                                   String responsibilities, String requirements, String skills,
                                   String talentProfile, List<String> warnings) { }

    public record TaskSummary(UUID id, UUID companyId, UUID workspaceId, String title, String status,
                              String currentStage, UUID jobId, String jobTitle, UUID createdBy,
                              Instant createdAt, Instant updatedAt) { }

    public record MessageView(UUID id, String role, String content, String capability, int sequenceNumber,
                              UUID createdBy, Instant createdAt) { }

    public record JdDraftView(UUID id, int revision, String title, String companyName, String location,
                              String experienceLevel, String education, String jobType, String responsibilities,
                              String requirements, String skills, String talentProfile, List<String> warnings,
                              String status, Instant updatedAt) { }

    public record AiRunView(UUID id, String providerTaskId, String status, int progress, int attemptNumber,
                            String pricingVersion, long estimatedAmountMinor, long settledAmountMinor,
                            String errorCode, String errorMessage, Instant createdAt, Instant completedAt) { }

    public record TaskDetail(TaskSummary task, UUID conversationId, List<MessageView> messages, List<JdDraftView> jdDrafts,
                             JdDraftView jdDraft, AiRunView latestAiRun) { }

    public record RunEvent(long eventId, UUID runId, String eventType, String data, Instant createdAt) { }

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

    private record TaskRow(UUID id, String title, String initialRequirement, UUID conversationId) { }

    private record RunExecution(UUID id, UUID companyId, UUID workspaceId, UUID taskId, UUID createdBy,
                                UUID conversationId, String idempotencyKey, String status, int progress,
                                String providerTaskId, String inputPayload, String executionContext) { }
}
