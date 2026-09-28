package com.intelligentrecruitment.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.intelligentrecruitment.notifications.application.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** BOSS-to-IR internal notification endpoint. */
@RestController
@RequestMapping("/internal/v1/platform/notifications")
public class InternalPlatformNotificationController {
    private final NotificationService notifications;
    private final String clientId;
    private final String clientSecret;

    public InternalPlatformNotificationController(
            NotificationService notifications,
            @Value("${app.boss.internal-client-id:}") String clientId,
            @Value("${app.boss.internal-client-secret:}") String clientSecret) {
        this.notifications = notifications;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @PostMapping("/enterprise-registration-rejected")
    void enterpriseRegistrationRejected(
            @RequestHeader("X-Internal-Client-Id") String requestClientId,
            @RequestHeader("X-Internal-Client-Secret") String requestClientSecret,
            @Valid @RequestBody EnterpriseRegistrationRejected request) {
        authenticate(requestClientId, requestClientSecret);
        notifications.createEnterpriseRegistrationRejected(
                request.userId(), request.registrationId(), request.legalName(), request.reason());
    }

    private void authenticate(String requestClientId, String requestClientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()
                || requestClientId == null || requestClientSecret == null
                || !constantTimeEquals(clientId, requestClientId)
                || !constantTimeEquals(clientSecret, requestClientSecret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部服务凭证无效");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public record EnterpriseRegistrationRejected(
            @JsonProperty("user_id") @NotNull UUID userId,
            @JsonProperty("registration_id") @NotNull UUID registrationId,
            @JsonProperty("legal_name") @NotBlank String legalName,
            @JsonProperty("reason") @NotBlank String reason) { }
}
