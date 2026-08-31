package com.intelligentrecruitment.identity.infrastructure;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.intelligentrecruitment.identity.application.VerificationCodeSender;
import com.intelligentrecruitment.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Sends production login codes through Alibaba Cloud SMS SendSms. */
@Component
@ConditionalOnProperty(name = "app.auth.verification-code.provider", havingValue = "aliyun")
public class AliyunVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(AliyunVerificationCodeSender.class);

    private final Client client;
    private final String signName;
    private final String templateCode;

    public AliyunVerificationCodeSender(
            @Value("${app.auth.verification-code.aliyun.access-key-id:}") String accessKeyId,
            @Value("${app.auth.verification-code.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${app.auth.verification-code.aliyun.sign-name:}") String signName,
            @Value("${app.auth.verification-code.aliyun.template-code:}") String templateCode,
            @Value("${app.auth.verification-code.aliyun.endpoint:dysmsapi.aliyuncs.com}") String endpoint,
            @Value("${app.auth.verification-code.aliyun.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.auth.verification-code.aliyun.read-timeout-ms:3000}") int readTimeoutMs) throws Exception {
        if (blank(accessKeyId) || blank(accessKeySecret) || blank(signName) || blank(templateCode)) {
            throw new IllegalStateException("阿里云短信已启用，但 AccessKey、签名或模板编码未配置");
        }
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret)
                .setConnectTimeout(Math.max(1000, connectTimeoutMs))
                .setReadTimeout(Math.max(1000, readTimeoutMs));
        config.endpoint = endpoint;
        this.client = new Client(config);
        this.signName = signName;
        this.templateCode = templateCode;
    }

    @Override
    public void send(String phone, String code) {
        try {
            SendSmsResponse response = client.sendSms(new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(signName)
                    .setTemplateCode(templateCode)
                    .setTemplateParam("{\"code\":\"" + code + "\"}"));
            String responseCode = response.getBody() == null ? null : response.getBody().getCode();
            if (!"OK".equals(responseCode)) {
                String requestId = response.getBody() == null ? null : response.getBody().getRequestId();
                log.warn("Aliyun SMS rejected verification code: code={}, requestId={}", responseCode, requestId);
                throw sendFailed();
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Aliyun SMS request failed: {}", exception.getMessage());
            throw sendFailed();
        }
    }

    @Override
    public boolean isLocalTestMode() {
        return false;
    }

    private static ApiException sendFailed() {
        return new ApiException("SMS_SEND_FAILED", "验证码发送失败，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
