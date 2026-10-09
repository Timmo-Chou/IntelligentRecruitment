package com.intelligentrecruitment.boss;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies Recruitment sends the published BOSS JSON field names for trusted-service calls. */
class BossControlPlaneClientContractTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsSnakeCaseMachineCredentialsAndTenantRegistrationPayload() throws Exception {
        AtomicReference<JsonNode> tokenBody = new AtomicReference<>();
        AtomicReference<JsonNode> registrationBody = new AtomicReference<>();
        server.createContext("/internal/v1/oauth/token", exchange -> {
            tokenBody.set(readJson(exchange));
            writeJson(exchange, "{\"access_token\":\"machine-token\",\"expires_in\":900}");
        });
        server.createContext("/api/v1/recruitment/enterprise-registrations", exchange -> {
            registrationBody.set(readJson(exchange));
            writeJson(exchange, "{}");
        });
        server.start();

        BossControlPlaneClient client = new BossControlPlaneClient(json, baseUrl, "recruitment-service", "secret");
        assertThat(client.internalAccessToken()).isEqualTo("machine-token");
        client.submitEnterpriseRegistration("user-token", "示例企业", "91110000ABCDEF1234",
                UUID.randomUUID(), "联系人", "13800138000");

        assertThat(tokenBody.get().path("client_id").asText()).isEqualTo("recruitment-service");
        assertThat(tokenBody.get().path("client_secret").asText()).isEqualTo("secret");
        assertThat(tokenBody.get().has("clientId")).isFalse();
        assertThat(registrationBody.get().path("legal_name").asText()).isEqualTo("示例企业");
        assertThat(registrationBody.get().path("license_document_id").asText()).isNotBlank();
        assertThat(registrationBody.get().has("legalName")).isFalse();
    }

    @Test
    void propagatesRequestAndIdempotencyHeadersToAiWriteCalls() throws Exception {
        AtomicReference<String> authorizationRequestId = new AtomicReference<>();
        AtomicReference<String> authorizationIdempotencyKey = new AtomicReference<>();
        AtomicReference<String> usageRequestId = new AtomicReference<>();
        AtomicReference<String> usageIdempotencyKey = new AtomicReference<>();
        AtomicReference<String> cancellationRequestId = new AtomicReference<>();
        AtomicReference<String> cancellationIdempotencyKey = new AtomicReference<>();
        UUID authorizationId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String key = "header-audit-key";
        server.createContext("/internal/v1/oauth/token", exchange ->
                writeJson(exchange, "{\"access_token\":\"machine-token\",\"expires_in\":900}"));
        server.createContext("/internal/ai/execution-authorizations", exchange -> {
            authorizationRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            authorizationIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            writeJson(exchange, "{\"authorization_id\":\"" + authorizationId + "\",\"grant_id\":\"" + grantId
                    + "\",\"reservation_id\":\"" + reservationId + "\",\"grant\":\"grant\",\"idempotency_key\":\"" + key
                    + "\",\"authorization_expires_at\":\"2030-01-01T00:00:00Z\",\"model_id\":\"model\",\"tokenizer_id\":\"tokenizer\",\"tenant_id\":\"" + tenantId + "\"}");
        });
        server.createContext("/internal/ai/usage-reports", exchange -> {
            usageRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            usageIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            writeJson(exchange, "{}");
        });
        server.createContext("/internal/ai/execution-cancellations", exchange -> {
            cancellationRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            cancellationIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            writeJson(exchange, "{}");
        });
        server.start();

        BossControlPlaneClient client = new BossControlPlaneClient(json, baseUrl, "recruitment-service", "secret");
        MDC.put("request_id", "request-header-audit");
        try {
            BossControlPlaneClient.AiAuthorization auth = client.authorizeAiExecution(
                    tenantId, UUID.randomUUID(), "task-1", "JD_GENERATION", "RECRUITMENT", key);
            client.reportAiUsage(auth, "SUCCEEDED", "model", 2L, 3L, 0, "task-1");
            client.cancelAiExecution(auth);
        } finally {
            MDC.remove("request_id");
        }

        assertThat(authorizationRequestId).hasValue("request-header-audit");
        assertThat(authorizationIdempotencyKey).hasValue(key);
        assertThat(usageRequestId).hasValue("request-header-audit");
        assertThat(usageIdempotencyKey).hasValue(key);
        assertThat(cancellationRequestId).hasValue("request-header-audit");
        assertThat(cancellationIdempotencyKey).hasValue(key);
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        return json.readTree(exchange.getRequestBody().readAllBytes());
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
