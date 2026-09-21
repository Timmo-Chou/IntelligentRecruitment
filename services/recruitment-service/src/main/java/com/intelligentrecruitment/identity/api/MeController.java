package com.intelligentrecruitment.identity.api;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
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

    /**
     * 返回当前用户信息。
     * 用户身份和资料由 BOSS 统一维护。
     */
    @GetMapping("/me")
    MeResponse me(Authentication authentication) {
        BossControlPlaneClient.User user = boss.currentUser(CurrentUser.bossAccessToken(authentication));
        return new MeResponse(user.userId(), user.maskedPhone(), user.displayName());
    }

    @PutMapping("/me/display-name")
    MeResponse updateDisplayName(@Valid @RequestBody DisplayNameRequest request, Authentication authentication) {
        BossControlPlaneClient.User user = boss.updateDisplayName(CurrentUser.bossAccessToken(authentication), request.displayName());
        return new MeResponse(user.userId(), user.maskedPhone(), user.displayName());
    }

    public record DisplayNameRequest(String displayName) { }

    public record MeResponse(UUID id, String maskedPhone, String displayName) { }
}
