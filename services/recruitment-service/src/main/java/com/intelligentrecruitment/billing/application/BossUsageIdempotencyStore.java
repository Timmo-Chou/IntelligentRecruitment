package com.intelligentrecruitment.billing.application;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists the immutable usage timestamp outside the AI business transaction. */
@Service
public class BossUsageIdempotencyStore {
    private final JdbcTemplate jdbc;
    public BossUsageIdempotencyStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Instant timestamp(String usageEventId) {
        List<Instant> existing = jdbc.query("SELECT occurred_at FROM boss_usage_reports WHERE usage_event_id=?",
                (row, index) -> row.getTimestamp(1).toInstant(), usageEventId);
        if (!existing.isEmpty()) return existing.getFirst();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO boss_usage_reports(usage_event_id,occurred_at) VALUES(?,?) ON CONFLICT DO NOTHING",
                usageEventId, java.sql.Timestamp.from(now));
        return jdbc.queryForObject("SELECT occurred_at FROM boss_usage_reports WHERE usage_event_id=?",
                (row, index) -> row.getTimestamp(1).toInstant(), usageEventId);
    }
}
