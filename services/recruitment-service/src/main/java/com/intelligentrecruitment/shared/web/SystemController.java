package com.intelligentrecruitment.shared.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping("/ping")
    Map<String, Object> ping() {
        return Map.of(
                "service", "recruitment-service",
                "status", "ok",
                "time", Instant.now().toString()
        );
    }
}

