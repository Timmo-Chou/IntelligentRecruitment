package com.intelligentrecruitment.identity.application;

/**
 * Delivers an already generated verification code. Implementations must not
 * persist or log the plaintext code.
 */
public interface VerificationCodeSender {

    void send(String phone, String code);

    boolean isLocalTestMode();
}
