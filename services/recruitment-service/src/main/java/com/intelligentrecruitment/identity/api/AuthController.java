package com.intelligentrecruitment.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Recruitment is an authentication BFF only. User accounts and sessions are issued by BOSS. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "boss_refresh";
    private final BossControlPlaneClient boss;
    private final boolean secureCookie;

    public AuthController(BossControlPlaneClient boss, @Value("${app.auth.secure-cookie:true}") boolean secureCookie) {
        this.boss = boss;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/challenges")
    ChallengeResponse challenge(@Valid @RequestBody ChallengeRequest request) {
        var result = boss.challenge(request.phone(), request.purpose());
        return new ChallengeResponse(result.id(), result.expiresAt(), result.mockCode());
    }

    @PostMapping("/verify")
    ResponseEntity<TokenResponse> verify(@Valid @RequestBody VerifyRequest request, HttpServletRequest servletRequest) {
        return tokenResponse(boss.verify(request.challengeId(), request.phone(), request.code(), servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/password-login")
    ResponseEntity<TokenResponse> passwordLogin(@Valid @RequestBody PasswordLoginRequest request, HttpServletRequest servletRequest) {
        return tokenResponse(boss.passwordLogin(request.phone(), request.password(), servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/password-reset")
    ResponseEntity<TokenResponse> passwordReset(@Valid @RequestBody PasswordResetRequest request, HttpServletRequest servletRequest) {
        return tokenResponse(boss.resetPassword(request.challengeId(), request.phone(), request.code(), request.newPassword(),
                servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/password")
    ResponseEntity<Void> setPassword(Authentication authentication, @Valid @RequestBody PasswordRequest request) {
        boss.setPassword(CurrentUser.bossAccessToken(authentication), request.password(), request.currentPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        return tokenResponse(boss.refresh(cookie(request), request.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(Authentication authentication) {
        boss.logout(CurrentUser.bossAccessToken(authentication));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    private ResponseEntity<TokenResponse> tokenResponse(BossControlPlaneClient.Session session) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookieHeader(session.setCookie()).toString())
                .body(new TokenResponse(session.userId(), session.accessToken(), session.expiresAt(), session.newUser(),
                        session.passwordSetupRequired()));
    }

    private ResponseCookie cookieHeader(String bossCookie) {
        String token = bossCookie == null ? "" : bossCookie.replaceFirst("(?i)^boss_refresh=([^;]*).*$", "$1");
        return ResponseCookie.from(REFRESH_COOKIE, token).httpOnly(true).secure(secureCookie).sameSite("Lax")
                .path("/api/v1/auth").maxAge(Duration.ofDays(14)).build();
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(item -> REFRESH_COOKIE.equals(item.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(secureCookie).sameSite("Lax")
                .path("/api/v1/auth").maxAge(Duration.ZERO).build();
    }

    public record ChallengeRequest(@NotBlank String phone, String purpose) { }
    public record ChallengeResponse(@JsonProperty("challenge_id") UUID challengeId,
                                    @JsonProperty("expires_at") Instant expiresAt,
                                    @JsonProperty("mock_code") String mockCode) { }
    public record VerifyRequest(@NotNull @JsonProperty("challenge_id") UUID challengeId,
                                @NotBlank String phone, @NotBlank String code) { }
    public record PasswordLoginRequest(@NotBlank String phone, @NotBlank String password) { }
    public record PasswordResetRequest(@NotNull @JsonProperty("challenge_id") UUID challengeId,
                                       @NotBlank String phone, @NotBlank String code,
                                       @NotBlank @JsonProperty("new_password") String newPassword) { }
    public record PasswordRequest(@NotBlank String password, @JsonProperty("current_password") String currentPassword) { }
    public record TokenResponse(@JsonProperty("user_id") UUID userId,
                                @JsonProperty("access_token") String accessToken,
                                @JsonProperty("access_expires_at") Instant accessExpiresAt,
                                @JsonProperty("new_user") boolean newUser,
                                @JsonProperty("password_setup_required") boolean passwordSetupRequired) { }
}
