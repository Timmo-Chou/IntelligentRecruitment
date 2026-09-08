package com.intelligentrecruitment.boss.application;

import com.intelligentrecruitment.shared.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Request-scoped BOSS bearer token. Never copy this value into async jobs, logs or persistence. */
public final class BossRequestContext {
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private BossRequestContext() { }
    public static void set(UUID userId, String accessToken) { CURRENT.set(new Principal(userId, accessToken)); }
    public static void clear() { CURRENT.remove(); }
    public static String accessToken(UUID expectedUserId) {
        Principal principal = CURRENT.get();
        if (principal == null || !principal.userId().equals(expectedUserId)) {
            throw new ApiException("BOSS_SESSION_REQUIRED", "BOSS 登录上下文缺失，请重新登录", HttpStatus.UNAUTHORIZED);
        }
        return principal.accessToken();
    }
    private record Principal(UUID userId, String accessToken) { }
}
