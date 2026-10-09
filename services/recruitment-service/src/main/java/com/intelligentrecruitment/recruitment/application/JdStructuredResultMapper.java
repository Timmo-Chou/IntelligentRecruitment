package com.intelligentrecruitment.recruitment.application;

import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.recruitment.application.JdDraftGenerator.JdDraftContent;
import com.intelligentrecruitment.shared.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class JdStructuredResultMapper {

    public JdDraftContent toDraft(StructuredResult result) {
        if (result == null || !List.of(FlowCapability.JD_GENERATION, FlowCapability.JD_IN_PLACE_REVISION).contains(result.capability())
                || !List.of(StructuredResult.Status.DRAFT_READY, StructuredResult.Status.COMPLETED).contains(result.status())) {
            throw invalid("JD 生成未返回可用的结构化结果");
        }
        Map<String, Object> data = result.data();
        String title = required(data, "title");
        String company = required(data, "company_name");
        String location = text(data, "location");
        String experience = text(data, "experience_level");
        String education = text(data, "education");
        String jobType = defaulted(data, "job_type", "全职");
        String salary = text(data, "salary_range");
        String responsibilities = required(data, "responsibilities");
        String requirements = required(data, "requirements");
        String skills = required(data, "skills");
        String niceToHaves = text(data, "nice_to_haves");
        String benefits = text(data, "benefits");
        String profile = text(data, "talent_profile");
        String body = data.get("jd_text") instanceof String value && !value.isBlank() ? value.trim()
                : JdDraftGenerator.renderJdText(title, company, location, salary, experience, education, jobType,
                responsibilities, requirements, skills, niceToHaves, benefits, profile);
        return new JdDraftContent(title, company, location, experience, education, jobType, salary, body,
                responsibilities, requirements, skills, niceToHaves, benefits, profile, strings(data.get("warnings")));
    }

    private static String required(Map<String, Object> data, String field) {
        String value = text(data, field);
        if (value.isBlank()) throw invalid("JD 结构化结果缺少 " + field);
        return value;
    }

    private static String defaulted(Map<String, Object> data, String field, String fallback) {
        String value = text(data, field);
        return value.isBlank() ? fallback : value;
    }

    private static String text(Map<String, Object> data, String field) {
        Object value = data == null ? null : data.get(field);
        if (value instanceof List<?> items) {
            return items.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .map(item -> item.startsWith("-") ? item : "- " + item)
                    .collect(Collectors.joining("\n"));
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    private static ApiException invalid(String message) {
        return new ApiException("AI_CONTRACT_INVALID", message, HttpStatus.BAD_GATEWAY);
    }
}
