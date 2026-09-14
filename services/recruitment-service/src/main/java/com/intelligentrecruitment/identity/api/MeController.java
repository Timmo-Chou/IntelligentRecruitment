package com.intelligentrecruitment.identity.api;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
     * 前端期望字段：id、maskedPhone、displayName、personalVerificationStatus。
     * personalVerificationStatus 暂未对接 BOSS，默认返回 UNVERIFIED。
     */
    @GetMapping("/me")
    MeResponse me(Authentication authentication) {
        BossControlPlaneClient.User user = boss.currentUser(CurrentUser.bossAccessToken(authentication));
        return new MeResponse(user.userId(), user.maskedPhone(), user.displayName(), "UNVERIFIED");
    }

    @PutMapping("/me/display-name")
    MeResponse updateDisplayName(@Valid @RequestBody DisplayNameRequest request, Authentication authentication) {
        BossControlPlaneClient.User user = boss.updateDisplayName(CurrentUser.bossAccessToken(authentication), request.displayName());
        return new MeResponse(user.userId(), user.maskedPhone(), user.displayName(), "UNVERIFIED");
    }

    /**
     * 个人实名认证提交。
     * BOSS 暂不支持个人实名认证，先返回 PENDING 状态，后续完善。
     */
    @PostMapping("/personal-verifications")
    PersonalVerificationResponse submitPersonalVerification(@Valid @RequestBody PersonalVerificationRequest request,
                                                            Authentication authentication) {
        // TODO: 对接 BOSS 个人实名认证接口，当前直接返回受理成功
        return new PersonalVerificationResponse("PENDING");
    }

    public record DisplayNameRequest(String displayName) { }

    /** /me 响应体：id=userId，personalVerificationStatus 默认 UNVERIFIED */
    public record MeResponse(UUID id, String maskedPhone, String displayName, String personalVerificationStatus) { }

    /** 个人实名认证请求体 */
    public record PersonalVerificationRequest(@NotBlank String realName, @NotBlank String identityNumber) { }

    /** 个人实名认证响应体 */
    public record PersonalVerificationResponse(String status) { }
}
