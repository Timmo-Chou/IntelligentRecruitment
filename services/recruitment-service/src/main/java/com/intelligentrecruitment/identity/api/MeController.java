package com.intelligentrecruitment.identity.api;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile reads/writes are delegated to BOSS, preventing a second local account record. */
@RestController
@RequestMapping("/api/v1")
public class MeController {
    private final BossControlPlaneClient boss;
    public MeController(BossControlPlaneClient boss) { this.boss = boss; }

    @GetMapping("/me")
    BossControlPlaneClient.User me(Authentication authentication) {
        return boss.currentUser(CurrentUser.bossAccessToken(authentication));
    }

    @PutMapping("/me/display-name")
    BossControlPlaneClient.User updateDisplayName(@Valid @RequestBody DisplayNameRequest request, Authentication authentication) {
        return boss.updateDisplayName(CurrentUser.bossAccessToken(authentication), request.displayName());
    }

    public record DisplayNameRequest(String displayName) { }
}
