package com.intelligentrecruitment.identity.infrastructure;

import com.intelligentrecruitment.identity.application.VerificationCodeSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local development sender. The code is only returned by the API when explicitly enabled. */
@Component
@ConditionalOnProperty(name = "app.auth.verification-code.provider", havingValue = "local", matchIfMissing = true)
public class LocalVerificationCodeSender implements VerificationCodeSender {

    @Override
    public void send(String phone, String code) {
        // Intentionally no-op: local verification uses the stored challenge only.
    }

    @Override
    public boolean isLocalTestMode() {
        return true;
    }
}
