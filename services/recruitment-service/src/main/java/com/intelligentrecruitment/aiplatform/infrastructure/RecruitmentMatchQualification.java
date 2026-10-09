package com.intelligentrecruitment.aiplatform.infrastructure;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** One authoritative P0 decision for RD candidate validity and billing eligibility. */
public final class RecruitmentMatchQualification {
    private RecruitmentMatchQualification() { }
    public record Decision(int units, String validity, String reason, String itemStatus, BigDecimal score) { }

    public static Decision evaluate(Map<?, ?> candidate, int expectedCount) {
        return evaluate(candidate,expectedCount,1);
    }
    public static Decision evaluate(Map<?,?> candidate,int expectedCount,int expectedOrder){
        if (candidate == null || expectedCount < 1||expectedCount>5 || !(candidate.get("input_order") instanceof Number order)
                || order.intValue() != expectedOrder || !(candidate.get("attachment_ref") instanceof String ref) || ref.isBlank()
                || !(candidate.get("status") instanceof String status)
                || !(candidate.get("eligibility") instanceof String eligibility)
                || !Set.of("eligible", "ineligible", "unknown").contains(eligibility)
                || !(candidate.get("hard_filter_failed") instanceof Boolean hard)
                || !(candidate.get("ai_match_executed") instanceof Boolean aiExecuted)
                || !(candidate.get("vector_retrieval_executed") instanceof Boolean)) return null;
        Object rawResult = candidate.get("result");
        Map<?, ?> result = rawResult instanceof Map<?, ?> m ? m : null;
        if ("failed".equals(status)) {
            if (hard||result != null || !nonBlank(candidate.get("error_code")) || !nonBlank(candidate.get("failure_stage"))) return null;
            return new Decision(0, "CONFIRMED_NO_RESULT", "RD_CANDIDATE_FAILED", "FAILED", null);
        }
        if (!"completed".equals(status)) return null;
        if (hard) {
            if (!"ineligible".equals(eligibility) || aiExecuted || result != null
                    ||nonBlank(candidate.get("error_code"))||nonBlank(candidate.get("failure_stage"))) return null;
            return new Decision(1, "VERIFIED_VALID_RESULT", "RD_HARD_FILTER_COMPLETED", "HARD_FILTERED", null);
        }
        if (result == null || !Set.of("completed", "partial", "not_evaluated").contains(result.get("result_status"))
                || !Set.of("eligible", "ineligible", "unknown").contains(result.get("eligibility"))
                || !eligibility.equals(result.get("eligibility"))) return null;
        BigDecimal score = decimal(result.get("overall_score"));
        String resultStatus = String.valueOf(result.get("result_status"));
        if (score != null && (score.signum() < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0)) return null;
        if ("not_evaluated".equals(resultStatus)) {
            if (score != null||!(aiExecuted||Boolean.TRUE.equals(candidate.get("vector_retrieval_executed")))
                    ||!nonBlank(result.get("summary"))&&!nonBlank(result.get("recommendation"))
                    ||!hasExplicitReason(result)) return null;
            return new Decision(1, "VERIFIED_VALID_RESULT", "RD_NORMAL_NOT_EVALUATED", "NOT_EVALUATED", null);
        }
        if (score == null || !aiExecuted) return null;
        return new Decision(1, "VERIFIED_VALID_RESULT", "RD_MATCH_COMPLETED", "SUCCEEDED", score);
    }

    private static boolean nonBlank(Object value){return value instanceof String text&&!text.isBlank();}
    private static boolean hasExplicitReason(Map<?,?> result){
        if(nonBlank(result.get("summary"))||nonBlank(result.get("recommendation")))return true;
        Object hard=result.get("hard_requirement_summary");
        if(hard instanceof Map<?,?> summary&&nonBlank(summary.get("reason")))return true;
        for(String key:Set.of("hard_rule_results","missing_items","uncertainties")){
            Object rows=result.get(key);if(rows instanceof java.util.List<?> list&&list.stream().anyMatch(row->row instanceof Map<?,?> map&&(nonBlank(map.get("reason"))||nonBlank(map.get("message")))))return true;
        }
        return false;
    }

    private static BigDecimal decimal(Object value) {
        try { return value instanceof Number n ? new BigDecimal(n.toString()) : null; }
        catch (RuntimeException ignored) { return null; }
    }
}
