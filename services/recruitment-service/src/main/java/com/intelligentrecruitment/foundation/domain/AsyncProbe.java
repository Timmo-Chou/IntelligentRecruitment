package com.intelligentrecruitment.foundation.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "foundation_async_probe")
public class AsyncProbe {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private ProbeStatus status;

    private Instant createdAt;

    private Instant completedAt;

    @Version
    private long version;

    protected AsyncProbe() {
    }

    private AsyncProbe(UUID id) {
        this.id = id;
        this.status = ProbeStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    public static AsyncProbe queued() {
        return new AsyncProbe(UUID.randomUUID());
    }

    public void complete() {
        if (status == ProbeStatus.QUEUED) {
            status = ProbeStatus.COMPLETED;
            completedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public ProbeStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}

