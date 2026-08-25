package com.intelligentrecruitment.foundation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FoundationMessagingConfiguration {

    public static final String EXCHANGE = "recruitment.foundation";
    public static final String QUEUE = "recruitment.foundation.probe";
    public static final String ROUTING_KEY = "foundation.probe";

    @Bean
    DirectExchange foundationExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue foundationProbeQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    Binding foundationProbeBinding(DirectExchange foundationExchange, Queue foundationProbeQueue) {
        return BindingBuilder.bind(foundationProbeQueue).to(foundationExchange).with(ROUTING_KEY);
    }

    @Bean
    MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}

