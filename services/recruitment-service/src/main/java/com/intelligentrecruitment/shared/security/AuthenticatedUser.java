package com.intelligentrecruitment.shared.security;

import java.security.Principal;
import java.util.UUID;

public record AuthenticatedUser(UUID userId) implements Principal {
    @Override
    public String getName() {
        return userId.toString();
    }
}
