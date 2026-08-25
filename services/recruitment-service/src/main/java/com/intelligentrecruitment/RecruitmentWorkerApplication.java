package com.intelligentrecruitment;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public final class RecruitmentWorkerApplication {

    private RecruitmentWorkerApplication() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(RecruitmentApiApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("worker")
                .run(args);
    }
}

