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
