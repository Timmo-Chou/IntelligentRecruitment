package com.intelligentrecruitment.identity.application;

import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class IdentityService {

    public static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    public static final Duration REFRESH_TTL = Duration.ofDays(14);
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final String MOCK_CODE = "123456";

    private final JdbcTemplate jdbc;
    private final boolean exposeMockCode;

    public IdentityService(JdbcTemplate jdbc, @Value("${app.auth.expose-mock-code:false}") boolean exposeMockCode) {
        this.jdbc = jdbc;
        this.exposeMockCode = exposeMockCode;
    }

    @Transactional
    public Challenge challenge(String phone) {
        String normalized = normalizePhone(phone);
        String phoneHash = SecurityHashes.sha256(normalized);
        Integer recent = jdbc.queryForObject("""
                SELECT count(*) FROM verification_challenges WHERE phone_hash = ? AND created_at > ?
                """, Integer.class, phoneHash, timestamp(Instant.now().minus(Duration.ofMinutes(10))));
        if (recent != null && recent >= 5) {
            throw new ApiException("CHALLENGE_RATE_LIMITED", "验证码请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO verification_challenges
                (id, phone_hash, purpose, code_hash, expires_at, attempt_count, created_at)
                VALUES (?, ?, 'LOGIN', ?, ?, 0, ?)
                """, id, phoneHash, SecurityHashes.sha256(id + ":" + MOCK_CODE),
                timestamp(now.plus(CHALLENGE_TTL)), timestamp(now));
        return new Challenge(id, now.plus(CHALLENGE_TTL), exposeMockCode ? MOCK_CODE : null);
    }

    @Transactional
    public TokenPair verify(UUID challengeId, String phone, String code, String deviceInfo) {
        String phoneHash = SecurityHashes.sha256(normalizePhone(phone));
        List<ChallengeRow> rows = jdbc.query("""
                SELECT phone_hash, code_hash, expires_at, attempt_count, consumed_at
                FROM verification_challenges WHERE id = ? FOR UPDATE
                """, (rs, n) -> new ChallengeRow(rs.getString("phone_hash"), rs.getString("code_hash"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getInt("attempt_count"),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()), challengeId);
        if (rows.isEmpty()) {
            throw new ApiException("CHALLENGE_NOT_FOUND", "验证码请求不存在", HttpStatus.BAD_REQUEST);
        }
        ChallengeRow challenge = rows.getFirst();
        if (challenge.consumedAt() != null || challenge.expiresAt().isBefore(Instant.now())) {
            throw new ApiException("CHALLENGE_EXPIRED", "验证码已失效，请重新获取", HttpStatus.BAD_REQUEST);
        }
        if (challenge.attemptCount() >= 5) {
            throw new ApiException("CHALLENGE_LOCKED", "验证码尝试次数过多，请重新获取", HttpStatus.TOO_MANY_REQUESTS);
        }
        jdbc.update("UPDATE verification_challenges SET attempt_count = attempt_count + 1 WHERE id = ?", challengeId);
        if (!challenge.phoneHash().equals(phoneHash)
                || !challenge.codeHash().equals(SecurityHashes.sha256(challengeId + ":" + code))) {
            throw new ApiException("INVALID_VERIFICATION_CODE", "验证码不正确", HttpStatus.BAD_REQUEST);
        }
        jdbc.update("UPDATE verification_challenges SET consumed_at = ? WHERE id = ?", timestamp(Instant.now()), challengeId);

        List<UUID> existing = jdbc.query("SELECT id FROM users WHERE phone_hash = ?",
                (rs, n) -> rs.getObject("id", UUID.class), phoneHash);
        boolean newUser = existing.isEmpty();
        UUID userId = newUser ? createUser(phoneHash, phone) : existing.getFirst();
        return issueTokens(userId, deviceInfo, null, newUser);
    }

    @Transactional
    public TokenPair refresh(String refreshToken, String deviceInfo) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException("REFRESH_REQUIRED", "登录状态已失效，请重新登录", HttpStatus.UNAUTHORIZED);
        }
        List<SessionRow> sessions = jdbc.query("""
                SELECT id, user_id FROM refresh_sessions
                WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ? FOR UPDATE
                """, (rs, n) -> new SessionRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)),
                SecurityHashes.sha256(refreshToken), timestamp(Instant.now()));
        if (sessions.isEmpty()) {
            throw new ApiException("REFRESH_INVALID", "登录状态已失效，请重新登录", HttpStatus.UNAUTHORIZED);
        }
        SessionRow session = sessions.getFirst();
        jdbc.update("UPDATE refresh_sessions SET revoked_at = ? WHERE id = ?", timestamp(Instant.now()), session.id());
        return issueTokens(session.userId(), deviceInfo, session.id(), false);
    }

    @Transactional
    public void logout(UUID userId, String accessTokenHash, String refreshToken) {
        if (accessTokenHash != null) {
            jdbc.update("UPDATE access_tokens SET revoked_at = ? WHERE user_id = ? AND token_hash = ? AND revoked_at IS NULL",
                    timestamp(Instant.now()), userId, accessTokenHash);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            jdbc.update("UPDATE refresh_sessions SET revoked_at = ? WHERE user_id = ? AND token_hash = ? AND revoked_at IS NULL",
                    timestamp(Instant.now()), userId, SecurityHashes.sha256(refreshToken));
        }
    }

    @Transactional
    public void logoutAll(UUID userId) {
        Instant now = Instant.now();
        jdbc.update("UPDATE access_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", timestamp(now), userId);
        jdbc.update("UPDATE refresh_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", timestamp(now), userId);
    }

    public UserView me(UUID userId) {
        return jdbc.queryForObject("""
                SELECT u.id, u.phone_last_four, u.display_name,
                  COALESCE(pi.verification_status, 'UNVERIFIED') personal_verification_status
                FROM users u LEFT JOIN personal_identities pi ON pi.user_id = u.id WHERE u.id = ?
                """, (rs, n) -> new UserView(rs.getObject("id", UUID.class), "*******" + rs.getString("phone_last_four"),
                        rs.getString("display_name"), rs.getString("personal_verification_status")), userId);
    }

    private UUID createUser(String phoneHash, String phone) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    INSERT INTO users (id, phone_hash, phone_last_four, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, id, phoneHash, phone.substring(phone.length() - 4), timestamp(now), timestamp(now));
            return id;
        } catch (DuplicateKeyException race) {
            return jdbc.queryForObject("SELECT id FROM users WHERE phone_hash = ?", UUID.class, phoneHash);
        }
    }

    private TokenPair issueTokens(UUID userId, String deviceInfo, UUID rotatedFrom, boolean newUser) {
        String accessToken = SecurityHashes.randomToken();
        String refreshToken = SecurityHashes.randomToken();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO access_tokens (id, user_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, SecurityHashes.sha256(accessToken),
                timestamp(now.plus(ACCESS_TTL)), timestamp(now));
        jdbc.update("""
                INSERT INTO refresh_sessions
                (id, user_id, token_hash, device_info, expires_at, rotated_from_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, SecurityHashes.sha256(refreshToken), safeDevice(deviceInfo),
                timestamp(now.plus(REFRESH_TTL)), rotatedFrom, timestamp(now));
        boolean onboardingRequired = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT NOT EXISTS (SELECT 1 FROM workspace_memberships WHERE user_id = ? AND status = 'ACTIVE')",
                Boolean.class, userId));
        return new TokenPair(accessToken, now.plus(ACCESS_TTL), refreshToken, now.plus(REFRESH_TTL),
                newUser, onboardingRequired);
    }

    private static String normalizePhone(String phone) {
        String normalized = phone == null ? "" : phone.replaceAll("\\s+", "");
        if (!normalized.matches("1\\d{10}")) {
            throw new ApiException("INVALID_PHONE", "请输入正确的11位手机号", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private static String safeDevice(String value) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), 300));
    }

    public record Challenge(UUID challengeId, Instant expiresAt, String mockCode) {}
    public record TokenPair(String accessToken, Instant accessExpiresAt, String refreshToken,
                            Instant refreshExpiresAt, boolean newUser, boolean onboardingRequired) {}
    public record UserView(UUID id, String maskedPhone, String displayName, String personalVerificationStatus) {}
    private record ChallengeRow(String phoneHash, String codeHash, Instant expiresAt, int attemptCount, Instant consumedAt) {}
    private record SessionRow(UUID id, UUID userId) {}
}
