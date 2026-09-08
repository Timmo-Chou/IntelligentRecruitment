package com.intelligentrecruitment.shared.security;

import java.security.Principal;
import java.util.UUID;

/** Identity is issued by BOSS. The opaque token is request-only and must never be persisted. */
public record AuthenticatedUser(UUID userId, String bossAccessToken) implements Principal {
    @Override
    public String getName() {
        return userId.toString();
    }
}
