package com.intelligentrecruitment.foundation.application;

import com.intelligentrecruitment.foundation.domain.AsyncProbe;
import com.intelligentrecruitment.foundation.infrastructure.AsyncProbeRepository;
import com.intelligentrecruitment.foundation.infrastructure.FoundationMessagingConfiguration;
import com.intelligentrecruitment.shared.error.ApiException;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AsyncProbeService {

    private final AsyncProbeRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public AsyncProbeService(AsyncProbeRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public AsyncProbe create() {
        AsyncProbe probe = repository.save(AsyncProbe.queued());
        rabbitTemplate.convertAndSend(
                FoundationMessagingConfiguration.EXCHANGE,
                FoundationMessagingConfiguration.ROUTING_KEY,
                new ProbeMessage(probe.getId())
        );
        return probe;
    }

    @Transactional(readOnly = true)
    public AsyncProbe get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException("FOUNDATION_PROBE_NOT_FOUND", "探针任务不存在", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public void complete(UUID id) {
        repository.findById(id).ifPresent(probe -> {
            probe.complete();
            repository.save(probe);
        });
    }
}

