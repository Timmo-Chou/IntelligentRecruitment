package com.intelligentrecruitment.aiplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Regression coverage for the persisted, one-way terminal settlement choice. */
class AiExecutionLedgerTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID executionRecordId = UUID.randomUUID();
    private final AtomicReference<String> existingEffect = new AtomicReference<>();
    private final AtomicInteger outboxInserts = new AtomicInteger();
    private JdbcTemplate jdbc;
    private BossControlPlaneClient.AiAuthorization authorization;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        authorization = new BossControlPlaneClient.AiAuthorization(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "terminal-effect-key", null, "configured-model", "configured-tokenizer", tenantId);

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            if (sql.startsWith("SELECT id FROM ai_execution_records")) {
                when(resultSet.getObject(1, UUID.class)).thenReturn(executionRecordId);
                return List.of(mapper.mapRow(resultSet, 0));
            }
            if (sql.startsWith("SELECT effect_type,status FROM ai_settlement_outbox")) {
                if (existingEffect.get() == null) return List.of();
                when(resultSet.getString(1)).thenReturn(existingEffect.get());
                when(resultSet.getString(2)).thenReturn("COMPLETED");
                return List.of(mapper.mapRow(resultSet, 0));
            }
            return List.of();
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            if (invocation.<String>getArgument(0).startsWith("INSERT INTO ai_settlement_outbox")) outboxInserts.incrementAndGet();
            return 1;
        });
    }

    @Test
    void queuedUsageMakesLateCancellationObsoleteAfterIrRestart() {
        // A new ledger instance represents an IR restart; the persisted USAGE row is
        // rediscovered before a late cancellation can enqueue another final effect.
        existingEffect.set("USAGE");
        AiExecutionLedger restartedLedger = new AiExecutionLedger(jdbc, new ObjectMapper());

        assertDoesNotThrow(() -> restartedLedger.enqueueCancellation(authorization));
        org.junit.jupiter.api.Assertions.assertEquals(0, outboxInserts.get());
    }

    @Test
    void completedCancellationRejectsLateUsage() {
        existingEffect.set("CANCELLATION");
        AiExecutionLedger ledger = new AiExecutionLedger(jdbc, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> ledger.enqueueUsage(
                authorization.idempotencyKey(), authorization, "SUCCEEDED", "configured-model", 11, 7, 0, "task-1"));
        org.junit.jupiter.api.Assertions.assertEquals(0, outboxInserts.get());
    }
}
