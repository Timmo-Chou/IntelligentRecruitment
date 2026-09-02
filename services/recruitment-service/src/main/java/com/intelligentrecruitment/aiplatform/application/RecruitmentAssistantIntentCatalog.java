package com.intelligentrecruitment.aiplatform.application;

import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The temporary AI Platform's recruitment-assistant routing registry.
 *
 * <p>It is deliberately a routing and Skill/Tool allow-list only. It does not
 * grant access to a candidate, create a billable run, or invoke a provider.
 * The business service must still turn a matching route into a PolicyDecision
 * and ExecutionContext before any Skill or MCP tool can run.</p>
 */
public final class RecruitmentAssistantIntentCatalog {

    public enum ExecutionTreatment {
        CONVERSATION,
        BUSINESS_QUERY,
        BUSINESS_COMMAND,
        SKILL_EXECUTION,
        EXTERNAL_ACTION
    }

    public record Definition(
            String secondaryIntent,
            FlowCapability capability,
            RouteDecision.Operation operation,
            ExecutionTreatment executionTreatment,
            String skillId,
            List<String> pincaimaoMcpToolIds,
            boolean requiresUserConfirmation,
            List<String> requiredInputs,
            List<String> keywords
    ) {
        public Definition {
            pincaimaoMcpToolIds = List.copyOf(pincaimaoMcpToolIds);
            requiredInputs = List.copyOf(requiredInputs);
            keywords = List.copyOf(keywords);
        }
    }

    public record Resolution(Definition definition, double confidence) { }

    private static final List<Definition> DEFINITIONS = List.of(
            definition("collect_requirement", FlowCapability.REQUIREMENT_CHAT, RouteDecision.Operation.CONTINUE,
                    ExecutionTreatment.CONVERSATION, "recruitment.dialogue.v1", false, List.of(), "需求", "招聘一名", "招一个"),
            definition("supplement_requirement", FlowCapability.REQUIREMENT_CHAT, RouteDecision.Operation.CONTINUE,
                    ExecutionTreatment.CONVERSATION, "recruitment.dialogue.v1", false, List.of(), "补充", "另外", "还有"),
            definition("domain_question", FlowCapability.RECRUITMENT_QA, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.CONVERSATION, "recruitment.qa.v1", false, List.of(), "怎么", "是什么", "为什么", "价格", "余额"),

            definition("create_another_jd", FlowCapability.JD_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "jd.generate.v1", false, List.of(), "另一个", "另一份", "新增职位", "再新建", "新建一个"),
            definition("create_from_template", FlowCapability.JD_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "jd.generate.v1", false, List.of(), "模板", "参考jd"),
            definition("create_from_job_library", FlowCapability.JD_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "jd.generate.v1", false, List.of(), "职位库", "复制职位"),
            definition("create_from_document", FlowCapability.JD_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "jd.generate.v1", false, List.of("resume_file"), "上传文件", "word", "pdf", "文档生成"),
            definition("save_jd_draft", FlowCapability.JD_GENERATION, RouteDecision.Operation.CONFIRM,
                    ExecutionTreatment.BUSINESS_COMMAND, null, false, List.of(), "保存草稿", "保存jd"),
            definition("publish_jd", FlowCapability.JD_GENERATION, RouteDecision.Operation.CONFIRM,
                    ExecutionTreatment.BUSINESS_COMMAND, null, true, List.of(), "发布岗位", "确认发布", "发布jd"),
            definition("unpublish_jd", FlowCapability.JD_GENERATION, RouteDecision.Operation.CONFIRM,
                    ExecutionTreatment.BUSINESS_COMMAND, null, true, List.of(), "撤回", "下线岗位", "下线jd"),
            definition("inspect_jd", FlowCapability.JD_GENERATION, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.BUSINESS_QUERY, null, false, List.of(), "查看jd", "看看jd", "jd状态"),
            definition("optimize_current_jd", FlowCapability.JD_GENERATION, RouteDecision.Operation.REVISE,
                    ExecutionTreatment.SKILL_EXECUTION, "jd.revise.v1", false, List.of(), "优化jd", "精简jd", "扩写jd"),
            definition("revise_current_jd", FlowCapability.JD_GENERATION, RouteDecision.Operation.REVISE,
                    ExecutionTreatment.SKILL_EXECUTION, "jd.revise.v1", false, List.of(), "修改", "改为", "调整", "改一下"),
            definition("generate_jd_description", FlowCapability.JD_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "jd.generate.v1", false, List.of(), "岗位描述", "岗位职责", "任职要求"),
            definition("create_jd", FlowCapability.JD_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "jd.generate.v1", false, List.of(), "jd", "职位", "岗位", "发布新岗位"),

            definition("batch_parse_resumes", FlowCapability.RESUME_PARSING, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "resume.batch_parse.v1", true, List.of("resume_file"), "批量", "多份简历"),
            definition("reparse_resume", FlowCapability.RESUME_PARSING, RouteDecision.Operation.RETRY,
                    ExecutionTreatment.SKILL_EXECUTION, "resume.parse.v1", false, List.of("resume_file"), "重新解析", "再解析"),
            definition("inspect_resume_profile", FlowCapability.RESUME_PARSING, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.BUSINESS_QUERY, null, false, List.of(), "查看简历", "解析结果", "人才信息"),
            definition("parse_uploaded_resume", FlowCapability.RESUME_PARSING, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "resume.parse.v1", false, List.of("resume_file"), "上传简历", "解析简历", "简历解析"),

            definition("revise_screening_plan", FlowCapability.SCREENING_PLAN_GENERATION, RouteDecision.Operation.REVISE,
                    ExecutionTreatment.SKILL_EXECUTION, "screening.plan.revise.v1", false, List.of("job_version"), "修改筛选", "调整权重", "淘汰条件"),
            definition("inspect_screening_plan", FlowCapability.SCREENING_PLAN_GENERATION, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.BUSINESS_QUERY, null, false, List.of(), "查看筛选方案", "筛选方案"),
            definition("confirm_screening_plan", FlowCapability.SCREENING_PLAN_GENERATION, RouteDecision.Operation.CONFIRM,
                    ExecutionTreatment.BUSINESS_COMMAND, null, true, List.of("screening_plan"), "确认筛选方案", "采用筛选方案"),
            definition("create_screening_plan", FlowCapability.SCREENING_PLAN_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "screening.plan.generate.v1", false, List.of("job_version"), "筛选标准", "筛选维度", "筛选方案"),

            definition("compare_candidates", FlowCapability.CANDIDATE_SCREENING, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.SKILL_EXECUTION, "screening.compare.v1", false, List.of("screening_result"), "比较候选人", "候选人对比"),
            definition("continue_screening_run", FlowCapability.CANDIDATE_SCREENING, RouteDecision.Operation.CONTINUE,
                    ExecutionTreatment.SKILL_EXECUTION, "screening.evaluate.v1", true, List.of("job_version", "candidate_scope", "screening_plan"), "继续筛", "剩余简历"),
            definition("rerun_screening", FlowCapability.CANDIDATE_SCREENING, RouteDecision.Operation.RETRY,
                    ExecutionTreatment.SKILL_EXECUTION, "screening.evaluate.v1", true, List.of("job_version", "candidate_scope", "screening_plan"), "重新筛选", "再筛选"),
            definition("inspect_screening_result", FlowCapability.CANDIDATE_SCREENING, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.BUSINESS_QUERY, null, false, List.of("screening_result"), "筛选结果", "筛选进度", "匹配结果"),
            definition("cancel_screening_run", FlowCapability.CANDIDATE_SCREENING, RouteDecision.Operation.CANCEL,
                    ExecutionTreatment.BUSINESS_COMMAND, null, false, List.of(), "取消筛选", "停止筛选"),
            definition("create_screening_run", FlowCapability.CANDIDATE_SCREENING, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "screening.evaluate.v1", true,
                    List.of("job_version", "candidate_scope", "screening_plan"), "筛", "匹配候选人", "筛简历"),

            definition("search_external_candidates", FlowCapability.CANDIDATE_SOURCING, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.EXTERNAL_ACTION, "candidate.sourcing.v1", true, List.of("job_version"),
                    "聘才猫找", "人才库找", "搜索人才", "找候选人", "寻访"),
            definition("inspect_external_candidate", FlowCapability.CANDIDATE_SOURCING, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.EXTERNAL_ACTION, "candidate.sourcing.v1", true, List.of(), "外部候选人", "人才详情"),
            definition("import_external_candidate", FlowCapability.CANDIDATE_SOURCING, RouteDecision.Operation.CONFIRM,
                    ExecutionTreatment.EXTERNAL_ACTION, "candidate.sourcing.v1", true, List.of("candidate_scope"), "导入候选人", "导入人才"),

            definition("publish_to_pincaimao", FlowCapability.JOB_DISTRIBUTION, RouteDecision.Operation.CONFIRM,
                    ExecutionTreatment.EXTERNAL_ACTION, "job.distribution.v1", true, List.of("job_version"), "发布到聘才猫", "同步到聘才猫"),
            definition("send_candidate_invitation", FlowCapability.CANDIDATE_OUTREACH, RouteDecision.Operation.CONFIRM,
                    ExecutionTreatment.EXTERNAL_ACTION, "candidate.outreach.v1", true, List.of("candidate_scope", "job_version"), "发送邀约", "邀约候选人", "联系候选人"),

            definition("create_candidate_specific_kit", FlowCapability.INTERVIEW_KIT_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "interview.kit.generate.v1", true, List.of("job_version", "candidate_scope"), "针对候选人", "候选人面试题"),
            definition("revise_interview_kit", FlowCapability.INTERVIEW_KIT_GENERATION, RouteDecision.Operation.REVISE,
                    ExecutionTreatment.SKILL_EXECUTION, "interview.kit.revise.v1", false, List.of(), "修改面试题", "调整面试题"),
            definition("inspect_or_export_interview_kit", FlowCapability.INTERVIEW_KIT_GENERATION, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.BUSINESS_QUERY, null, false, List.of(), "查看面试题", "导出面试题"),
            definition("create_interview_kit", FlowCapability.INTERVIEW_KIT_GENERATION, RouteDecision.Operation.CREATE,
                    ExecutionTreatment.SKILL_EXECUTION, "interview.kit.generate.v1", false, List.of("job_version"), "面试题", "准备面试", "出题"),

            definition("retry_failed_task", FlowCapability.TASK_ASSISTANCE, RouteDecision.Operation.RETRY,
                    ExecutionTreatment.BUSINESS_COMMAND, null, true, List.of(), "重试", "再试一次"),
            definition("cancel_task", FlowCapability.TASK_ASSISTANCE, RouteDecision.Operation.CANCEL,
                    ExecutionTreatment.BUSINESS_COMMAND, null, false, List.of(), "取消任务", "停止任务"),
            definition("inspect_task_history", FlowCapability.TASK_ASSISTANCE, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.BUSINESS_QUERY, null, false, List.of(), "任务历史", "历史任务"),
            definition("inspect_task_progress", FlowCapability.TASK_ASSISTANCE, RouteDecision.Operation.INSPECT,
                    ExecutionTreatment.BUSINESS_QUERY, null, false, List.of(), "任务进度", "任务状态", "进度")
    );

    private RecruitmentAssistantIntentCatalog() { }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Resolution route(String message, List<FlowCapability> allowedCapabilities) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        Set<FlowCapability> allowed = Set.copyOf(allowedCapabilities == null ? List.of() : allowedCapabilities);
        List<Definition> matches = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            if (!allowed.contains(definition.capability())) continue;
            if (definition.keywords().stream().anyMatch(normalized::contains)) matches.add(definition);
        }
        // “筛这批简历” must be treated as candidate screening, not resume parsing
        // merely because the sentence contains “简历”.  The plan-design phrases
        // intentionally remain in screening-plan generation.
        boolean requestsScreeningExecution = (normalized.contains("筛") || normalized.contains("匹配"))
                && !normalized.contains("筛选方案") && !normalized.contains("筛选标准")
                && !normalized.contains("筛选维度") && !normalized.contains("调整权重");
        if (requestsScreeningExecution && allowed.contains(FlowCapability.CANDIDATE_SCREENING)) {
            matches.removeIf(definition -> definition.capability() != FlowCapability.CANDIDATE_SCREENING);
        }
        if (matches.isEmpty()) return null;
        matches.sort(Comparator.comparingInt((Definition definition) -> specificity(definition, normalized)).reversed());
        Definition selected = matches.getFirst();
        return new Resolution(selected, Math.min(0.97, 0.72 + (specificity(selected, normalized) * 0.05)));
    }

    public static Definition resolve(FlowCapability capability, String secondaryIntent, RouteDecision.Operation operation,
                                     List<FlowCapability> allowedCapabilities) {
        if (capability == null || secondaryIntent == null || operation == null
                || allowedCapabilities == null || !allowedCapabilities.contains(capability)) return null;
        return DEFINITIONS.stream()
                .filter(definition -> definition.capability() == capability)
                .filter(definition -> definition.secondaryIntent().equals(secondaryIntent))
                .filter(definition -> definition.operation() == operation)
                .findFirst().orElse(null);
    }

    public static String routerPrompt(List<FlowCapability> allowedCapabilities) {
        List<Definition> allowed = DEFINITIONS.stream()
                .filter(definition -> allowedCapabilities != null && allowedCapabilities.contains(definition.capability()))
                .toList();
        String routes = allowed.stream().map(definition -> "- " + definition.capability().value() + " | "
                + definition.secondaryIntent() + " | " + definition.operation().value()).reduce("", (left, right) -> left + "\n" + right);
        return """
                You are the routing component of a Chinese recruitment assistant. Return only one valid JSON object.
                You only classify intent and never execute a business operation, charge a customer, or invoke a tool.
                Pick exactly one route from the allowed route catalog below when the request is clear:
                %s

                Required JSON fields: kind (route|clarify|inform|unsupported), capability (string or null),
                secondary_intent (string or null), operation (create|revise|continue|inspect|confirm|cancel|retry or null),
                confidence (0 to 1), missing_inputs (string array), clarification (string or null),
                suggested_next_action (collect_requirement|select_job_version|select_candidates|prepare_screening_plan|show_quote|inspect_task|none).
                For a route, capability, secondary_intent, and operation must be one catalog row exactly.
                Never invent a job version, candidate scope, screening plan, file, account permission, balance, price, or user confirmation.
                For a request to screen candidates, missing_inputs must include job_version, candidate_scope, and screening_plan unless these are guaranteed by supplied context.
                For resume parsing without an uploaded file, missing_inputs must include resume_file.
                For external sourcing, publishing, or candidate outreach, return the route only; user confirmation and provider MCP invocation are decided by the business service.
                """.formatted(routes);
    }

    private static Definition definition(String secondaryIntent, FlowCapability capability, RouteDecision.Operation operation,
                                         ExecutionTreatment treatment, String skillId, boolean requiresConfirmation,
                                         List<String> requiredInputs, String... keywords) {
        List<String> mcpTools = switch (secondaryIntent) {
            case "search_external_candidates" -> List.of("pincaimao.candidates.search");
            case "inspect_external_candidate" -> List.of("pincaimao.candidate.detail.get");
            case "import_external_candidate" -> List.of("pincaimao.candidate.import");
            case "publish_to_pincaimao" -> List.of("pincaimao.job.publish");
            case "send_candidate_invitation" -> List.of("pincaimao.candidate.invite.send");
            case "create_screening_run", "continue_screening_run", "rerun_screening" -> List.of("pincaimao.candidate.match.evaluate");
            default -> List.of();
        };
        return new Definition(secondaryIntent, capability, operation, treatment, skillId, mcpTools,
                requiresConfirmation, requiredInputs, List.of(keywords));
    }

    private static int specificity(Definition definition, String normalizedMessage) {
        int longest = definition.keywords().stream().filter(normalizedMessage::contains).mapToInt(String::length).max().orElse(0);
        int operationBonus = switch (definition.operation()) {
            case CONFIRM, CANCEL, RETRY -> 8;
            case REVISE, INSPECT -> 5;
            default -> 0;
        };
        return longest + operationBonus;
    }
}
