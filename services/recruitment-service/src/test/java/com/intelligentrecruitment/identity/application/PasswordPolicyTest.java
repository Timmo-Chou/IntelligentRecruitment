package com.intelligentrecruitment.identity.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void acceptsPasswordContainingLettersAndNumbers() {
        assertThatCode(() -> IdentityService.validatePassword("Recruit2026"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWeakPasswords() {
        assertThatThrownBy(() -> IdentityService.validatePassword("12345678"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("INVALID_PASSWORD"));
        assertThatThrownBy(() -> IdentityService.validatePassword("password"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> IdentityService.validatePassword("a1"))
                .isInstanceOf(ApiException.class);
    }
}
