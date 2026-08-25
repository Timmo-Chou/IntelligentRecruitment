package com.intelligentrecruitment.identity;

import com.intelligentrecruitment.shared.security.SecurityHashes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHashesTest {
    @Test
    void createsOpaqueNonRepeatingTokensAndStableHashes() {
        String first = SecurityHashes.randomToken();
        String second = SecurityHashes.randomToken();

        assertThat(first).isNotBlank().isNotEqualTo(second);
        assertThat(SecurityHashes.sha256(first)).hasSize(64).isEqualTo(SecurityHashes.sha256(first));
    }
}
