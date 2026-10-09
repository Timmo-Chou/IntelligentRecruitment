package com.intelligentrecruitment.aiplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionBillingQualificationTest {
    @Test
    void completedInPlaceJdRevisionQualifiesForFixedBilling() {
        StructuredResult result = new StructuredResult(null, "task-revise", null, StructuredResult.Status.COMPLETED,
                "1", Map.of("action", "UPDATE_CURRENT_JD", "title", "Platform Engineer",
                        "company_name", "Synthetic Co", "responsibilities", List.of("Build services"),
                        "requirements", "Experience with reliable systems", "skills", List.of("Java")),
                List.of(), List.of(), null, null, null);

        assertEquals(new ExecutionBillingQualification.Decision(1, "VERIFIED_VALID_RESULT",
                        "JD_IN_PLACE_REVISION_COMPLETED"),
                ExecutionBillingQualification.evaluate(result, "JD_IN_PLACE_REVISION", "revise"));
    }

    @Test
    void incompleteInPlaceJdRevisionDoesNotQualify() {
        StructuredResult result = new StructuredResult(null, "task-revise", null, StructuredResult.Status.COMPLETED,
                "1", Map.of("action", "UPDATE_CURRENT_JD", "title", "Platform Engineer"),
                List.of(), List.of(), null, null, null);

        org.junit.jupiter.api.Assertions.assertNull(
                ExecutionBillingQualification.evaluate(result, "JD_IN_PLACE_REVISION", "revise"));
    }

    @Test
    void completedInterviewKitWithThreeCompetenciesAndFourQuestionsQualifiesForFixedBilling() {
        List<Map<String, Object>> competencies = List.of(
                Map.of("name", "系统设计", "description", "核验服务架构经验"),
                Map.of("name", "工程质量", "description", "核验代码质量实践"),
                Map.of("name", "协作沟通", "description", "核验跨团队协作"));
        List<Map<String, Object>> questions = java.util.stream.IntStream.range(0, 4).mapToObj(i -> Map.<String, Object>of(
                "category", "专业能力", "content", "问题 " + i, "rationale", "核验经历",
                "focus_points", "边界与取舍", "reference_answer_points", "约束与结果",
                "scoring_points", "5分/3分/1分", "evidence_refs", "JD与简历",
                "core_competency", "系统设计")).toList();
        StructuredResult result = new StructuredResult(null, "task-interview", null, StructuredResult.Status.COMPLETED,
                "1", Map.of("match_summary", "基本匹配", "core_competencies", competencies, "questions", questions),
                List.of(), List.of(), null, null, null);

        assertEquals(new ExecutionBillingQualification.Decision(1, "VERIFIED_VALID_RESULT",
                        "INTERVIEW_KIT_GENERATION_COMPLETED"),
                ExecutionBillingQualification.evaluate(result, "INTERVIEW_KIT_GENERATION", "generate"));
    }

    @Test
    void interviewKitWithoutRequiredQuestionEvidenceDoesNotQualify() {
        Map<String, Object> incompleteQuestion = Map.of("category", "专业能力", "content", "问题");
        StructuredResult result = new StructuredResult(null, "task-interview", null, StructuredResult.Status.COMPLETED,
                "1", Map.of("match_summary", "基本匹配",
                        "core_competencies", List.of(Map.of("name", "系统设计", "description", "核验"),
                                Map.of("name", "工程质量", "description", "核验"),
                                Map.of("name", "协作沟通", "description", "核验")),
                        "questions", List.of(incompleteQuestion, incompleteQuestion, incompleteQuestion, incompleteQuestion)),
                List.of(), List.of(), null, null, null);

        org.junit.jupiter.api.Assertions.assertNull(
                ExecutionBillingQualification.evaluate(result, "INTERVIEW_KIT_GENERATION", "generate"));
    }

    @Test
    void completedBatchWithOnlyConfirmedCandidateFailuresQualifiesForRelease() {
        Map<String, Object> failedCandidate = Map.of(
                "input_order", 1,
                "attachment_ref", "asset-1",
                "status", "failed",
                "eligibility", "unknown",
                "hard_filter_failed", false,
                "ai_match_executed", false,
                "vector_retrieval_executed", false,
                "error_code", "SYNTHETIC_FAILURE",
                "failure_stage", "MATCH");
        Map<String, Object> data = Map.of(
                "candidates", List.of(failedCandidate),
                "candidate_input_bindings", List.of(Map.of("input_order", 1, "file_asset_id", "asset-1", "sha256", "a".repeat(64))),
                "batch", Map.of("input_count", 1, "failed_count", 1, "hard_filtered_count", 0, "succeeded_count", 0));
        StructuredResult result = new StructuredResult(null, "task-1", null, StructuredResult.Status.COMPLETED,
                "1", data, List.of(), List.of(), null, null, null);

        ExecutionBillingQualification.Decision decision = ExecutionBillingQualification.evaluate(result,
                "CANDIDATE_SCREENING", "match");

        assertEquals(new ExecutionBillingQualification.Decision(0, "CONFIRMED_NO_RESULT",
                "RD_MATCH_BATCH_CONFIRMED_NO_RESULT"), decision);
    }
}
