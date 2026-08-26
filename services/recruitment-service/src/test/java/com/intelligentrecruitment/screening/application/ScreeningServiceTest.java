package com.intelligentrecruitment.screening.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningServiceTest {

    @Test
    void keepsMultiWordSkillNamesIntact() {
        assertThat(ScreeningService.tokens("Java, Spring Boot，MySQL / Redis; Kafka"))
                .containsExactly("Java", "Spring Boot", "MySQL", "Redis", "Kafka");
    }
}
