package com.intelligentrecruitment.aiplatform.application;

import java.util.List;
import java.util.UUID;

/**
 * AI 面试出题命令与结构化返回 DTO。
 * 走同步 DeepSeek JSON 接口（response_format=json_object），
 * 输出直接映射到 InterviewQuestionKit，供 InterviewService 写入 interview_kits / interview_questions 表。
 */
public final class InterviewQuestionContract {

    private InterviewQuestionContract() {}

    /** 传给 AI 平台的输入：职位快照 + 候选人上下文 + 期望题目数量 */
    public record GenerateInterviewQuestionsInput(
            UUID workspaceId,        // 工作空间ID（仅用于审计/埋点，不参与 Prompt）
            JobSnapshot job,         // 职位快照
            CandidateSnapshot candidate, // 候选人简历解析结果
            int requestedCount       // 期望题目数量（4~20，默认 8）
    ) {
        public record JobSnapshot(
                String title,
                String companyName,
                String location,
                String experienceLevel,
                String education,
                String responsibilities,
                String requirements,
                String skills
        ) {}

        public record CandidateSnapshot(
                String name,
                String headline,
                List<String> skills,
                String summary,
                String resumeText
        ) {}
    }

    /** 一项核心胜任力（Prompt 输出 core_competencies[].name/description） */
    public record Competency(String name, String description) {}

    /** 一道面试题（与 interview_questions 表字段一一对应，另补 coreCompetency 用于 UI 分组） */
    public record Question(
            String category,          // 四选一：专业能力 | 项目实践 | 行为协作 | 场景决策
            String content,           // 题面
            String rationale,         // 出题理由（→ rationale）
            String focusPoints,       // 追问要点（→ focus_points）
            String referenceAnswerPoints, // 好回答要点（→ reference_answer_points）
            String scoringPoints,     // 评分要点（→ scoring_points）
            String evidenceRefs,      // 证据引用（→ evidence_refs）
            String coreCompetency     // 对应胜任力名（用于 UI 分组，不作为 DB 列）
    ) {}

    /** 面试题包完整结构化输出 */
    public record InterviewQuestionKit(
            String matchSummary,        // 匹配度总结（替换原 matchSummary()）
            List<Competency> competencies, // 3 项核心胜任力（替换原 competencies()）
            List<Question> questions    // 面试题列表（4~20 道）
    ) {}
}
