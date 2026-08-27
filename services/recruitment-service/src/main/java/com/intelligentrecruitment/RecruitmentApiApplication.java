package com.intelligentrecruitment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RecruitmentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecruitmentApiApplication.class, args);
    }
}
