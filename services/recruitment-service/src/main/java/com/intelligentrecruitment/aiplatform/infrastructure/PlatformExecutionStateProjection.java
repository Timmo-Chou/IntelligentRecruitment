package com.intelligentrecruitment.aiplatform.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Atomic, monotonic projection shared by task reads and webhook events. */
public final class PlatformExecutionStateProjection {
    private PlatformExecutionStateProjection() { }

    public enum Outcome { APPLIED, IDEMPOTENT, STALE, TERMINAL, CONFLICT, NOT_FOUND }

    public static Outcome project(JdbcTemplate jdbc, UUID tenantId, String taskId, long version, String status) {
        List<State> rows = jdbc.query("""
                SELECT state_version,platform_status,final_decision
                FROM ai_execution_records
                WHERE tenant_id=? AND agent_task_id=?
                FOR UPDATE
                """, (rs, n) -> new State(rs.getLong(1), rs.wasNull(), rs.getString(2), rs.getString(3)), tenantId, taskId);
        if (rows.isEmpty()) return Outcome.NOT_FOUND;
        State current = rows.getFirst();
        if (current.finalDecision() != null) return Outcome.TERMINAL;
        long currentVersion = current.versionIsNull() ? -1 : current.version();
        if (version < currentVersion) return Outcome.STALE;
        if (version == currentVersion) {
            return Objects.equals(current.status(), status) ? Outcome.IDEMPOTENT : Outcome.CONFLICT;
        }
        if (isTerminal(current.status())) {
            return Objects.equals(current.status(), status) ? Outcome.IDEMPOTENT : Outcome.TERMINAL;
        }
        int updated = jdbc.update("""
                UPDATE ai_execution_records SET state_version=?,platform_status=?,updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND agent_task_id=? AND final_decision IS NULL
                  AND state_version IS NOT DISTINCT FROM ? AND platform_status IS NOT DISTINCT FROM ?
                """, version, status, tenantId, taskId,
                current.versionIsNull() ? null : current.version(), current.status());
        return updated == 1 ? Outcome.APPLIED : Outcome.STALE;
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private record State(long version, boolean versionIsNull, String status, String finalDecision) { }
}
