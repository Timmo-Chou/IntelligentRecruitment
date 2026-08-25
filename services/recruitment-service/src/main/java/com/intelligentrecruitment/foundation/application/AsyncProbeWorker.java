package com.intelligentrecruitment.foundation.application;

import com.intelligentrecruitment.foundation.infrastructure.FoundationMessagingConfiguration;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AsyncProbeWorker {

    private final AsyncProbeService service;

    public AsyncProbeWorker(AsyncProbeService service) {
        this.service = service;
    }

    @RabbitListener(queues = FoundationMessagingConfiguration.QUEUE)
    public void handle(ProbeMessage message) {
        service.complete(message.probeId());
    }
}

