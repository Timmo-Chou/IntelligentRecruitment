package com.intelligentrecruitment.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.intelligentrecruitment.identity.application.IdentityService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "recruitment_refresh";
    private final IdentityService identityService;
    private final boolean secureCookie;

    public AuthController(IdentityService identityService,
                          @Value("${app.auth.secure-cookie:true}") boolean secureCookie) {
        this.identityService = identityService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/challenges")
    ChallengeResponse challenge(@Valid @RequestBody ChallengeRequest request) {
        IdentityService.Challenge result = identityService.challenge(request.phone());
        return new ChallengeResponse(result.challengeId(), result.expiresAt(), result.mockCode());
    }

    @PostMapping("/verify")
    ResponseEntity<TokenResponse> verify(@Valid @RequestBody VerifyRequest request, HttpServletRequest servletRequest) {
        return tokenResponse(identityService.verify(request.challengeId(), request.phone(), request.code(),
                servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        return tokenResponse(identityService.refresh(cookie(request), request.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request) {
        String accessHash = authentication == null ? null : String.valueOf(authentication.getCredentials());
        identityService.logout(CurrentUser.id(authentication), accessHash, cookie(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(Authentication authentication) {
        identityService.logoutAll(CurrentUser.id(authentication));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    private ResponseEntity<TokenResponse> tokenResponse(IdentityService.TokenPair pair) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, pair.refreshToken())
                .httpOnly(true).secure(secureCookie).sameSite("Strict").path("/api/v1/auth")
                .maxAge(Duration.ofDays(14)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new TokenResponse(pair.accessToken(), pair.accessExpiresAt(), pair.newUser(), pair.onboardingRequired()));
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(item -> REFRESH_COOKIE.equals(item.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/v1/auth").maxAge(Duration.ZERO).build();
    }

    public record ChallengeRequest(@NotBlank String phone) {}
    public record ChallengeResponse(@JsonProperty("challenge_id") UUID challengeId,
                                    @JsonProperty("expires_at") Instant expiresAt,
                                    @JsonProperty("mock_code") String mockCode) {}
    public record VerifyRequest(@NotNull @JsonProperty("challenge_id") UUID challengeId,
                                @NotBlank String phone, @NotBlank String code) {}
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("access_expires_at") Instant accessExpiresAt,
                                @JsonProperty("new_user") boolean newUser,
                                @JsonProperty("onboarding_required") boolean onboardingRequired) {}
}
