package com.intelligentrecruitment.candidates.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 渐进式加密历史数据。该任务可重复执行：带 enc:v1: 标记的数据不会再次加密。
 * 不记录任何原文或密文，避免迁移过程本身成为新的泄露面。
 */
@Component
@ConditionalOnProperty(name = "app.pii.reencrypt-on-startup", havingValue = "true", matchIfMissing = true)
public class PiiAtRestMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final PiiCipher pii;
    private final ObjectMapper objectMapper;

    public PiiAtRestMigrationRunner(JdbcTemplate jdbc, PiiCipher pii, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.pii = pii;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        encryptTextColumn("resume_parse_versions", "raw_text");
        encryptTextColumn("resume_source_files", "filename");
        encryptTextColumn("resume_source_files", "extracted_text");
        encryptTextColumn("resume_parse_drafts", "content");
        encryptTextColumn("messages", "content");
        encryptFileNames();
        protectAiPayloads();
        protectScreeningResults();
        protectInterviewArtifacts();
        backfillCandidateSearchTokens();
    }

    private void encryptTextColumn(String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id," + column + " AS value FROM " + table + " WHERE " + column + " IS NOT NULL");
        for (Map<String, Object> row : rows) {
            String value = String.valueOf(row.get("value"));
            if (!value.isBlank() && !pii.isEncrypted(value)) {
                jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE id=?", pii.encrypt(value), row.get("id"));
            }
        }
    }

    private void encryptFileNames() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,original_filename FROM file_assets WHERE original_filename IS NOT NULL");
        for (Map<String, Object> row : rows) {
            String value = String.valueOf(row.get("original_filename"));
            if (!value.isBlank() && !pii.isEncrypted(value)) {
                jdbc.update("UPDATE file_assets SET original_filename=? WHERE id=?", pii.encrypt(value), row.get("id"));
            }
        }
    }

    private void protectAiPayloads() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,input_payload::text AS payload FROM ai_runs");
        for (Map<String, Object> row : rows) {
            String payload = String.valueOf(row.get("payload"));
            if (isProtectedJson(payload)) continue;
            jdbc.update("UPDATE ai_runs SET input_payload=?::jsonb WHERE id=?", protectedJson(payload), row.get("id"));
        }
    }

    private void protectScreeningResults() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,matched_points::text AS matched_points,unmatched_points::text AS unmatched_points,
                       negotiable_points::text AS negotiable_points,missing_information::text AS missing_information,
                       risks::text AS risks,evidence::text AS evidence,result_snapshot::text AS result_snapshot
                FROM screening_results
                """);
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            for (String column : List.of("matched_points", "unmatched_points", "negotiable_points", "missing_information", "risks", "evidence", "result_snapshot")) {
                String payload = String.valueOf(row.get(column));
                if (!isProtectedJson(payload)) {
                    jdbc.update("UPDATE screening_results SET " + column + "=?::jsonb WHERE id=?", protectedJson(payload), id);
                }
            }
        }
    }

    private void backfillCandidateSearchTokens() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,full_name_ciphertext,phone_ciphertext FROM candidates");
        for (Map<String, Object> row : rows) {
            String name = pii.decrypt(String.valueOf(row.get("full_name_ciphertext")));
            Object storedPhone = row.get("phone_ciphertext");
            String phone = storedPhone == null ? "" : pii.decrypt(String.valueOf(storedPhone));
            // 旧 search_text 可能含姓名、手机号或邮箱，迁移后只保留非 PII 检索字段的后续新写入。
            jdbc.update("UPDATE candidates SET full_name_search_hash=?,phone_search_hash=?,search_text='' WHERE id=?",
                    pii.searchToken(name), pii.searchToken(phone), row.get("id"));
        }
    }

    private void protectInterviewArtifacts() {
        List<Map<String, Object>> kits = jdbc.queryForList("SELECT id,core_competencies::text AS core_competencies,match_summary FROM interview_kits");
        for (Map<String, Object> kit : kits) {
            String competencies = String.valueOf(kit.get("core_competencies"));
            if (!isProtectedJson(competencies)) {
                jdbc.update("UPDATE interview_kits SET core_competencies=?::jsonb WHERE id=?", protectedJson(competencies), kit.get("id"));
            }
            String summary = String.valueOf(kit.get("match_summary"));
            if (!summary.isBlank() && !pii.isEncrypted(summary)) {
                jdbc.update("UPDATE interview_kits SET match_summary=? WHERE id=?", pii.encrypt(summary), kit.get("id"));
            }
        }
        List<Map<String, Object>> questions = jdbc.queryForList("SELECT id,content,rationale,focus_points,reference_answer_points,scoring_points,evidence_refs FROM interview_questions");
        for (Map<String, Object> question : questions) {
            for (String column : List.of("content", "rationale", "focus_points", "reference_answer_points", "scoring_points", "evidence_refs")) {
                Object stored = question.get(column);
                if (stored != null && !String.valueOf(stored).isBlank() && !pii.isEncrypted(String.valueOf(stored))) {
                    jdbc.update("UPDATE interview_questions SET " + column + "=? WHERE id=?", pii.encrypt(String.valueOf(stored)), question.get("id"));
                }
            }
        }
    }

    private boolean isProtectedJson(String value) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(value, new TypeReference<>() { });
            Object ciphertext = parsed.get("_encrypted");
            return ciphertext instanceof String text && pii.isEncrypted(text);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String protectedJson(String value) {
        try {
            return objectMapper.writeValueAsString(Map.of("_encrypted", pii.encrypt(value)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encrypt persisted AI data", exception);
        }
    }
}
