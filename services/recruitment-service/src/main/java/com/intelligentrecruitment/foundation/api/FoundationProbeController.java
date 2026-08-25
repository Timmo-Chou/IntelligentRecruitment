package com.intelligentrecruitment.foundation.api;

import com.intelligentrecruitment.foundation.application.AsyncProbeService;
import com.intelligentrecruitment.foundation.domain.AsyncProbe;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/internal/foundation/probes")
public class FoundationProbeController {

    private final AsyncProbeService service;

    public FoundationProbeController(AsyncProbeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ProbeResponse create() {
        return ProbeResponse.from(service.create());
    }

    @GetMapping("/{id}")
    ProbeResponse get(@PathVariable UUID id) {
        return ProbeResponse.from(service.get(id));
    }

    record ProbeResponse(UUID id, String status, Instant createdAt, Instant completedAt) {
        static ProbeResponse from(AsyncProbe probe) {
            return new ProbeResponse(
                    probe.getId(),
                    probe.getStatus().name().toLowerCase(),
                    probe.getCreatedAt(),
                    probe.getCompletedAt()
            );
        }
    }
}

