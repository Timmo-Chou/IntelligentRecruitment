package com.intelligentrecruitment.aiplatform.infrastructure;

import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.ConversationAgentCommand;
import com.intelligentrecruitment.aiplatform.application.RecruitmentAssistantIntentCatalog;
import com.intelligentrecruitment.aiplatform.application.RouteAgentCommand;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai-platform.mode", havingValue = "mock")
public class MockAiPlatformClient implements AiPlatformClient {

    private final Map<String, AiTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Map<String, StructuredResult> results = new ConcurrentHashMap<>();

    @Override
    public AiTask startTask(StartAiTaskCommand command) {
        String existingTaskId = idempotencyIndex.get(command.idempotencyKey());
        if (existingTaskId != null) {
            return tasks.get(existingTaskId);
        }

        String aiTaskId = "mock_ait_" + UUID.randomUUID();
        AiTask task = new AiTask(
                aiTaskId,
                command.businessTaskId(),
                command.capability(),
                AiTaskStatus.COMPLETED,
                1,
                1,
                100,
                Instant.now()
        );
        tasks.put(aiTaskId, task);
        idempotencyIndex.put(command.idempotencyKey(), aiTaskId);
        results.put(aiTaskId, resultFor(aiTaskId, command));
        return task;
    }

    @Override
    public AiTask getTask(String aiTaskId) {
        AiTask task = tasks.get(aiTaskId);
        if (task == null) {
            throw new ApiException("AI_TASK_NOT_FOUND", "AI 任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    @Override
    public AiTask cancelTask(String aiTaskId, String idempotencyKey) {
        AiTask current = getTask(aiTaskId);
        if (current.status() == AiTaskStatus.COMPLETED || current.status() == AiTaskStatus.FAILED) {
            return current;
        }
        AiTask cancelled = new AiTask(
                current.aiTaskId(),
                current.businessTaskId(),
                current.capability(),
                AiTaskStatus.CANCELLED,
                current.completed(),
                current.total(),
                current.percent(),
                current.acceptedAt()
        );
        tasks.put(aiTaskId, cancelled);
        return cancelled;
    }

    @Override
    public RouteDecision routeMessage(RouteAgentCommand command) {
        RecruitmentAssistantIntentCatalog.Resolution resolution = RecruitmentAssistantIntentCatalog.route(
                command.message(), command.allowedCapabilities());
        if (resolution == null) {
            return new RouteDecision(UUID.randomUUID(), RouteDecision.Kind.CLARIFY, null, null, null, 0.35, true,
                    List.of("user_clarification"), "请说明你希望生成 JD、解析简历、筛选候选人，还是设计面试题。",
                    RouteDecision.SuggestedNextAction.COLLECT_REQUIREMENT, Instant.now());
        }
        RecruitmentAssistantIntentCatalog.Definition route = resolution.definition();
        return new RouteDecision(UUID.randomUUID(), RouteDecision.Kind.ROUTE, route.capability(), route.secondaryIntent(),
                route.operation(), resolution.confidence(), true, route.requiredInputs(), null,
                suggestedAction(route), Instant.now());
    }

    private static RouteDecision.SuggestedNextAction suggestedAction(RecruitmentAssistantIntentCatalog.Definition route) {
        if (route.requiresUserConfirmation()) return RouteDecision.SuggestedNextAction.SHOW_QUOTE;
        if (route.requiredInputs().contains("job_version")) return RouteDecision.SuggestedNextAction.SELECT_JOB_VERSION;
        if (route.requiredInputs().contains("candidate_scope")) return RouteDecision.SuggestedNextAction.SELECT_CANDIDATES;
        if (route.requiredInputs().contains("screening_plan")) return RouteDecision.SuggestedNextAction.PREPARE_SCREENING_PLAN;
        return route.operation() == RouteDecision.Operation.INSPECT
                ? RouteDecision.SuggestedNextAction.INSPECT_TASK
                : RouteDecision.SuggestedNextAction.COLLECT_REQUIREMENT;
    }

    @Override
    public String continueConversation(ConversationAgentCommand command) {
        Map<String, String> latest = command.messages().isEmpty() ? Map.of() : command.messages().getLast();
        return "已收到你的补充：" + latest.getOrDefault("content", "").trim()
                + "。我会基于当前任务上下文继续协助；确认后可生成新的 JD 修订版本。";
    }

    @Override
    public StructuredResult reviseJdInPlace(ConversationAgentCommand command) {
        return new StructuredResult(UUID.randomUUID(), "mock_revision_" + UUID.randomUUID(), FlowCapability.JD_GENERATION,
                StructuredResult.Status.DRAFT_READY, "jd-v1", command.jdDraft(), List.of(), List.of(),
                new StructuredResult.Provenance("mock-jd", "v1", "mock-revision-v1", "mock"),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    @Override
    public StructuredResult getStructuredResult(String aiTaskId) {
        StructuredResult result = results.get(aiTaskId);
        if (result == null) throw new ApiException("AI_TASK_NOT_FOUND", "AI 任务不存在", HttpStatus.NOT_FOUND);
        return result;
    }

    private StructuredResult resultFor(String aiTaskId, StartAiTaskCommand command) {
        if (command.capability() == com.intelligentrecruitment.aiplatform.domain.AiCapability.JD_GENERATION) {
            Map<String, Object> input = command.input();
            String title = text(input, "title", "待确认职位");
            String requirement = text(input, "requirement", "请补充岗位需求");
            String skills = text(input, "skills", "岗位专业能力、沟通协作、问题解决");
            Map<String, Object> data = Map.ofEntries(
                    Map.entry("title", title),
                    Map.entry("company_name", text(input, "companyName", "企业名称待确认")),
                    Map.entry("location", text(input, "location", "工作地点待确认")),
                    Map.entry("experience_level", text(input, "experienceLevel", "经验待确认")),
                    Map.entry("education", text(input, "education", "学历待确认")),
                    Map.entry("job_type", text(input, "jobType", "全职")),
                    Map.entry("salary_range", text(input, "salaryRange", "薪资待确认")),
                    Map.entry("responsibilities", "1. 围绕“%s”推进岗位核心工作；\n2. 与团队协作交付可验证的业务结果。".formatted(requirement)),
                    Map.entry("requirements", "具备与岗位相关的专业能力、结构化沟通与问题解决能力。"),
                    Map.entry("skills", skills),
                    Map.entry("nice_to_haves", "具备相关行业经验或可验证项目成果者优先。"),
                    Map.entry("benefits", "福利待遇待确认"),
                    Map.entry("talent_profile", "优先寻找具备“%s”能力组合且有可验证成果的人选。".formatted(skills)),
                    Map.entry("warnings", List.of("薪资范围尚未提供"))
            );
            return new StructuredResult(command.executionContext() == null ? null : command.executionContext().executionId(),
                    aiTaskId, FlowCapability.JD_GENERATION, StructuredResult.Status.DRAFT_READY, "jd-v1", data,
                    List.of(), List.of(), new StructuredResult.Provenance("mock-jd", "v1", "mock-jd-v1", "mock"),
                    new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
        }
        if (command.capability() == com.intelligentrecruitment.aiplatform.domain.AiCapability.CANDIDATE_SCREENING) {
            Map<String, Object> candidate = map(command.input().get("candidate"));
            Map<String, Object> job = map(command.input().get("job"));
            String resume = text(candidate, "resume_text", "");
            String skills = text(candidate, "skills", "");
            String jobSkills = text(job, "skills", "");
            int score = Math.min(95, Math.max(55, 65 + matchingSkillCount(skills, jobSkills) * 10));
            String level = score >= 85 ? "STRONG_MATCH" : score >= 70 ? "MATCH" : "GENERAL_MATCH";
            Map<String, Object> data = Map.of(
                    "score", score, "level", level,
                    "matched_points", List.of("AI 已基于简历和岗位要求识别相关经历与技能"),
                    "unmatched_points", List.of(),
                    "negotiable_points", List.of("建议在面试中核验岗位关键能力与项目成果"),
                    "missing_information", resume.isBlank() ? List.of("简历正文不足，建议人工补充核验") : List.of(),
                    "risks", List.of("AI 评分仅供招聘人员辅助判断，不得自动淘汰候选人"),
                    "evidence", List.of("AI Platform mock 根据冻结的职位、筛选方案和简历解析结果生成")
            );
            return new StructuredResult(command.executionContext() == null ? null : command.executionContext().executionId(),
                    aiTaskId, FlowCapability.CANDIDATE_SCREENING, StructuredResult.Status.COMPLETED, "screening-v1", data,
                    List.of(), List.of(), new StructuredResult.Provenance("mock-candidate-screening", "v1", "mock-screening-v1", "mock"),
                    new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
        }
        if (command.capability() == com.intelligentrecruitment.aiplatform.domain.AiCapability.RESUME_PARSING) {
            return mockResumeStructuredResult(aiTaskId, command);
        }
        return new StructuredResult(command.executionContext() == null ? null : command.executionContext().executionId(),
                aiTaskId, toFlowCapability(command.capability()), StructuredResult.Status.COMPLETED, "v1", Map.of(),
                List.of(), List.of(), new StructuredResult.Provenance("mock-" + command.capability().name().toLowerCase(),
                "v1", "mock-v1", "mock"), new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    /**
     * Mock 简历解析结构化结果：解析上传简历的提取文本，生成中文 Markdown 摘要。
     * 区分"是否关联职位"两版输出：有职位时追加匹配度分析与面试建议。
     * 该方法 public 可被 DeepSeek 客户端在真实 Prompt 未接入前兜底复用。
     */
    public StructuredResult mockResumeStructuredResult(String aiTaskId, StartAiTaskCommand command) {
        Map<String, Object> input = command.input() == null ? Map.of() : command.input();
        List<Map<String, Object>> resumes = mapList(input.get("resumes"));
        Map<String, Object> job = map(input.get("job"));
        boolean hasJob = !job.isEmpty();
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (Map<String, Object> resume : resumes) {
            index += 1;
            String filename = text(resume, "filename", "简历 " + index);
            String text = text(resume, "text", "");
            int tokenEstimate = Math.max(10, text.codePointCount(0, text.length()));
            String personName = extractPersonName(text, filename);
            String jobIntention = extractKeyword(text, "意向|求职|目标|应聘|期望", "未明确");
            String topSkills = summarizeSkills(text);
            String exp = summarizeExperience(text);
            String edu = summarizeEducation(text);

            builder.append("## 简历 ").append(index).append("：").append(filename).append("\n");
            builder.append("### 1. 基本信息\n");
            builder.append("- 姓名：").append(personName).append("\n");
            builder.append("- 求职意向：").append(jobIntention).append("\n");
            builder.append("- 文档大小估算：约 ").append(tokenEstimate).append(" 字符\n\n");
            builder.append("### 2. 教育背景\n").append(edu.isBlank() ? "未识别到明确教育背景（可人工复核 PDF/DOCX 是否为扫描件）。\n\n" : edu + "\n\n");
            builder.append("### 3. 工作/项目经历摘要\n").append(exp.isBlank() ? "文本较短，建议人工打开原简历补充查看详细经历。\n\n" : exp + "\n\n");
            builder.append("### 4. 核心技能标签\n").append(topSkills.isBlank() ? "未识别到技能关键词，建议人工查看简历。\n\n" : "- " + topSkills + "\n\n");
            builder.append("### 5. 候选人亮点与风险\n");
            builder.append("- 亮点：").append(highlightsFor(text, hasJob)).append("\n");
            builder.append("- 风险/待核验：").append(risksFor(text)).append("\n");
            if (hasJob) {
                String jobTitle = text(job, "title", "目标职位");
                String jobSkills = text(job, "skills", "");
                String req = text(job, "requirements", "");
                int matchScore = matchScore(text, jobSkills, req);
                String level = matchScore >= 85 ? "高度匹配" : matchScore >= 70 ? "基本匹配" : matchScore >= 55 ? "部分匹配" : "匹配度较低";
                builder.append("\n### 6. 与职位《").append(jobTitle).append("》匹配度分析\n");
                builder.append("- 匹配得分：").append(matchScore).append("/100（AI mock 评估）\n");
                builder.append("- 判定：").append(level).append("\n");
                builder.append("- 匹配点：").append(matchedPointsFor(text, jobSkills, req)).append("\n");
                builder.append("- 差距：").append(gapPointsFor(text, jobSkills, req)).append("\n");
                builder.append("- 面试建议：").append(interviewTips(jobTitle, jobSkills)).append("\n");
            }
            builder.append("\n");
        }
        if (resumes.isEmpty()) {
            builder.append("当前暂未上传简历文件。点击左侧「上传简历」补充 PDF / DOCX / TXT，AI 将自动重新解析并生成结构化结果。\n");
        } else {
            builder.append("### 7. 后续建议\n");
            builder.append("1. 与候选人核实简历中的空白或矛盾信息；\n");
            builder.append("2. 结合职位库的硬性条件再次核验学历、年限、核心技能；\n");
            builder.append("3. 若需要，可在右侧 AI招聘助手中继续追问，生成候选人对比表或面试题。\n");
        }
        Map<String, Object> data = Map.of(
                "markdown", builder.toString(),
                "resume_count", resumes.size(),
                "job_linked", hasJob,
                "warnings", resumes.isEmpty() ? List.of("请先上传至少一份简历") : List.of("解析结果仅供招聘人员参考，录用决策请结合人工复核")
        );
        return new StructuredResult(command.executionContext() == null ? null : command.executionContext().executionId(),
                aiTaskId, FlowCapability.RESUME_PARSING, StructuredResult.Status.DRAFT_READY, "resume-parsing-v1", data,
                List.of(), List.of(), new StructuredResult.Provenance("mock-resume-parsing", "v1", "mock-resume-parsing-v1", "mock"),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    private static String extractPersonName(String text, String filename) {
        // 优先用文件名（去扩展名）
        String base = filename.replaceAll("(?i)\\.(pdf|docx?|txt)$", "").trim();
        if (!base.isBlank() && base.length() <= 10) return base;
        // 回退：从文本开头 120 字里寻找 2-4 字中文组合
        String prefix = text.length() <= 120 ? text : text.substring(0, 120);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,4}").matcher(prefix);
        if (matcher.find()) return matcher.group();
        return "姓名待确认";
    }

    private static String extractKeyword(String text, String pattern, String fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:" + pattern + ")\\s*[:：]?\\s*([^\\r\\n，。；;,]{1,40})").matcher(text);
        if (matcher.find()) return matcher.group(1).trim();
        return fallback;
    }

    private static String summarizeEducation(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("((?:19|20)\\d{2})\\s*[-–—~～]\\s*((?:19|20)\\d{2}|至今|在读)\\s*([^\\r\\n，。；;,]{1,30})").matcher(text);
        List<String> items = new java.util.ArrayList<>();
        int count = 0;
        while (matcher.find() && count < 3) {
            items.add("- " + matcher.group(1) + " – " + matcher.group(2) + "  " + matcher.group(3).trim());
            count += 1;
        }
        if (!items.isEmpty()) return String.join("\n", items);
        List<String> schools = List.of("大学", "学院", "高中", "School", "University");
        for (String school : schools) {
            int idx = text.indexOf(school);
            if (idx >= 0) {
                int start = Math.max(0, idx - 8);
                int end = Math.min(text.length(), idx + 24);
                return "- " + text.substring(start, end).replaceAll("[\\r\\n\\s]+", " ").trim();
            }
        }
        return "";
    }

    private static String summarizeExperience(String text) {
        String[] lines = text.split("\\r?\\n");
        List<String> bullets = new java.util.ArrayList<>();
        for (String line : lines) {
            String clean = line.replaceAll("[\\s·•\\-]+", " ").trim();
            if (clean.length() >= 10 && (clean.contains("负责") || clean.contains("参与") || clean.contains("设计")
                    || clean.contains("开发") || clean.contains("优化") || clean.contains("项目")
                    || clean.contains("研发") || clean.contains("运营") || clean.contains("销售"))) {
                bullets.add("- " + clean.substring(0, Math.min(clean.length(), 60)) + (clean.length() > 60 ? "…" : ""));
            }
            if (bullets.size() >= 4) break;
        }
        return String.join("\n", bullets);
    }

    private static String summarizeSkills(String text) {
        List<String> keywords = List.of("Java", "Python", "Go", "C++", "TypeScript", "JavaScript", "React", "Vue",
                "Spring", "MySQL", "Redis", "Kafka", "Docker", "Kubernetes", "机器学习", "大模型", "RAG",
                "Prompt", "数据分析", "SQL", "Excel", "沟通协作", "项目管理", "英语", "日语");
        List<String> found = new java.util.ArrayList<>();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(java.util.Locale.ROOT)) && !found.contains(keyword)) found.add(keyword);
        }
        return String.join("、", found);
    }

    private static String highlightsFor(String text, boolean hasJob) {
        String skills = summarizeSkills(text);
        if (skills.isBlank()) return "需要人工进一步挖掘亮点（当前简历提取文本不足）。";
        return (hasJob ? "与目标职位相关关键词已覆盖：" : "简历中已体现技能关键词：") + skills + "，整体结构完整。";
    }

    private static String risksFor(String text) {
        if (text.length() < 400) return "简历文本长度较短，可能存在扫描件 / 图片 PDF 的情况，建议人工核验原件。";
        if (!text.contains("手机") && !text.contains("电话") && !text.contains("@") && !text.contains("邮箱")) return "未识别到联系方式（手机/邮箱），需要通过人才库或上传渠道补齐。";
        return "AI 解析可能会漏读部分排版、换行或项目细节，面试前请用原件复核。";
    }

    private static int matchScore(String resumeText, String jobSkills, String jobRequirements) {
        List<String> resume = new java.util.ArrayList<>(java.util.Arrays.asList(resumeText.toLowerCase(java.util.Locale.ROOT).split("[\\s,，、;；/\\-。()（）\\[\\]【】\\r\\n]+")));
        int hit = 0;
        int total = 0;
        for (String s : (jobSkills + "、" + jobRequirements).split("[\\s,，、;；/\\-。()（）\\[\\]【】\\r\\n]+")) {
            String keyword = s.trim();
            if (keyword.length() < 2) continue;
            total += 1;
            if (resume.stream().anyMatch(token -> token.contains(keyword.toLowerCase(java.util.Locale.ROOT)))) hit += 1;
        }
        if (total == 0) return 65;
        double ratio = (double) hit / total;
        return Math.min(95, Math.max(50, 50 + (int) Math.round(ratio * 50)));
    }

    private static String matchedPointsFor(String text, String jobSkills, String jobRequirements) {
        List<String> found = new java.util.ArrayList<>();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String raw : (jobSkills + "、" + jobRequirements).split("[\\s,，、;；/\\-。()（）\\[\\]【】\\r\\n]+")) {
            String s = raw.trim();
            if (s.length() < 2) continue;
            if (lower.contains(s.toLowerCase(java.util.Locale.ROOT)) && !found.contains(s)) found.add(s);
            if (found.size() >= 5) break;
        }
        if (found.isEmpty()) return "暂未识别到明确匹配点，请人工查看简历。";
        return "简历与职位关键词重合：" + String.join("、", found);
    }

    private static String gapPointsFor(String text, String jobSkills, String jobRequirements) {
        List<String> missing = new java.util.ArrayList<>();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String raw : (jobSkills + "、" + jobRequirements).split("[\\s,，、;；/\\-。()（）\\[\\]【】\\r\\n]+")) {
            String s = raw.trim();
            if (s.length() < 2) continue;
            if (!lower.contains(s.toLowerCase(java.util.Locale.ROOT)) && !missing.contains(s)) missing.add(s);
            if (missing.size() >= 4) break;
        }
        if (missing.isEmpty()) return "暂未识别到明确差距。";
        return "以下关键词在简历中未明确出现：" + String.join("、", missing) + "（可能是同义词或换行导致，建议人工核实）。";
    }

    private static String interviewTips(String jobTitle, String jobSkills) {
        List<String> first = new java.util.ArrayList<>();
        for (String raw : jobSkills.split("[\\s,，、;；/\\-。()（）\\[\\]【】\\r\\n]+")) {
            String s = raw.trim();
            if (s.length() < 2) continue;
            first.add(s);
            if (first.size() >= 2) break;
        }
        if (first.isEmpty()) return "围绕" + jobTitle + "的真实项目案例、问题解决过程和团队协作，追问 STAR 细节。";
        return "围绕" + jobTitle + "的真实项目案例，重点考察「" + String.join("、", first) + "」的落地深度，并补充追问 STAR 细节和团队协作。";
    }

    private static String text(Map<String, Object> input, String field, String fallback) {
        Object value = input.get(field);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) result.add((Map<String, Object>) raw);
            }
            return result;
        }
        return List.of();
    }

    private static int matchingSkillCount(String candidateSkills, String jobSkills) {
        List<String> candidate = List.of(candidateSkills.toLowerCase().split("[、,，/;；\\n\\r]+"));
        return (int) List.of(jobSkills.toLowerCase().split("[、,，/;；\\n\\r]+")).stream()
                .filter(skill -> !skill.isBlank() && candidate.stream().anyMatch(value -> value.trim().equals(skill.trim()))).count();
    }

    private static FlowCapability toFlowCapability(com.intelligentrecruitment.aiplatform.domain.AiCapability capability) {
        return FlowCapability.fromValue(capability.name());
    }
}
