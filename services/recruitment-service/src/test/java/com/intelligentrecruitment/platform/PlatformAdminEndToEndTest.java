package com.intelligentrecruitment.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台管理端端到端集成测试。
 * 使用本地运行的 PostgreSQL（端口 5432），验证 Controller → Service → Database 完整链路。
 * 测试管理员的创建、查询、更新、禁用全流程。
 * 注意：测试前需确保 PostgreSQL 容器已启动且 intelligent_recruitment 数据库已迁移。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAdminEndToEndTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ADMIN_KEY = "phase2-local-admin";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 指向本地运行的 PostgreSQL（docker-compose 启动的容器）
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/intelligent_recruitment");
        registry.add("spring.datasource.username", () -> "recruitment");
        registry.add("spring.datasource.password", () -> "recruitment_local");
        // 禁用定时任务和异步 worker，避免依赖 Redis/RabbitMQ
        registry.add("app.phase3.worker-enabled", () -> "false");
        registry.add("app.phase5.worker-enabled", () -> "false");
        registry.add("app.pii.reencrypt-on-startup", () -> "false");
        // 数据库已迁移，禁用 Flyway 校验
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void createAndQueryAdmin_fullFlow() throws Exception {
        RestClient client = restClient();

        // 1. 创建管理员
        String createBody = objectMapper.writeValueAsString(Map.of(
                "displayName", "E2E测试管理员-" + System.currentTimeMillis(),
                "key", "e2e-secret-key-123",
                "role", "PLATFORM_OPERATOR"
        ));

        ResponseEntity<String> createResponse = client.post()
                .uri("/api/v1/platform/admins")
                .header("X-Platform-Admin-Key", ADMIN_KEY)
                .header("Content-Type", "application/json")
                .body(createBody)
                .retrieve()
                .toEntity(String.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> created = objectMapper.readValue(createResponse.getBody(), Map.class);
        String adminId = (String) created.get("id");
        assertThat(adminId).isNotNull();
        assertThat(created.get("role")).isEqualTo("PLATFORM_OPERATOR");
        assertThat(created.get("status")).isEqualTo("ACTIVE");

        try {
            // 2. 查询单个管理员详情
            ResponseEntity<String> detailResponse = client.get()
                    .uri("/api/v1/platform/admins/" + adminId)
                    .header("X-Platform-Admin-Key", ADMIN_KEY)
                    .retrieve()
                    .toEntity(String.class);

            assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> detail = objectMapper.readValue(detailResponse.getBody(), Map.class);
            assertThat(detail.get("id")).isEqualTo(adminId);

            // 3. 更新管理员角色为 SUPER_ADMIN
            String updateBody = objectMapper.writeValueAsString(Map.of("role", "SUPER_ADMIN"));
            ResponseEntity<String> updateResponse = client.put()
                    .uri("/api/v1/platform/admins/" + adminId)
                    .header("X-Platform-Admin-Key", ADMIN_KEY)
                    .header("Content-Type", "application/json")
                    .body(updateBody)
                    .retrieve()
                    .toEntity(String.class);

            assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> updated = objectMapper.readValue(updateResponse.getBody(), Map.class);
            assertThat(updated.get("role")).isEqualTo("SUPER_ADMIN");

            // 4. 禁用管理员
            ResponseEntity<Void> disableResponse = client.post()
                    .uri("/api/v1/platform/admins/" + adminId + "/disable")
                    .header("X-Platform-Admin-Key", ADMIN_KEY)
                    .retrieve()
                    .toBodilessEntity();

            assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

            // 5. 验证已禁用
            ResponseEntity<String> afterDisable = client.get()
                    .uri("/api/v1/platform/admins/" + adminId)
                    .header("X-Platform-Admin-Key", ADMIN_KEY)
                    .retrieve()
                    .toEntity(String.class);

            Map<String, Object> disabled = objectMapper.readValue(afterDisable.getBody(), Map.class);
            assertThat(disabled.get("status")).isEqualTo("DISABLED");
        } finally {
            // 清理：删除测试创建的管理员
            try {
                client.delete()
                        .uri("/api/v1/platform/admins/" + adminId)
                        .header("X-Platform-Admin-Key", ADMIN_KEY)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void listAdmins_withInvalidKey_returnsForbidden() {
        RestClient client = restClient();

        // 使用无效密钥，应被 PlatformAdminGuard 拒绝返回 403
        ResponseEntity<String> response = client.get()
                .uri("/api/v1/platform/admins")
                .header("X-Platform-Admin-Key", "invalid-key")
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                        .body(new String(res.getBody().readAllBytes())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
