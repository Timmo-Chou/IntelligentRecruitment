package com.intelligentrecruitment.candidates;

import com.intelligentrecruitment.candidates.application.PiiCipher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiCipherTest {

    private final PiiCipher cipher = new PiiCipher("phase4-test-key");

    @Test
    void encryptsAndDecryptsSensitiveFields() {
        String first = cipher.encrypt("13800138000");
        String second = cipher.encrypt("13800138000");

        assertThat(first).isNotEqualTo("13800138000");
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("13800138000");
        assertThat(cipher.decrypt(second)).isEqualTo("13800138000");
    }

    @Test
    void keepsEmptyValuesOutOfCiphertext() {
        assertThat(cipher.encrypt(" ")).isNull();
        assertThat(cipher.decrypt(null)).isEmpty();
    }
}
