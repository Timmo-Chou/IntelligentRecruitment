package com.intelligentrecruitment.boss.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.shared.error.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Anti-corruption layer for the BOSS control plane.  Recruitment business code must use this
 * class instead of reading a local identity, tenant or billing table as an authority source.
 */
@Component
public class BossControlPlaneClient {
    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private volatile MachineToken machineToken;

    public BossControlPlaneClient(ObjectMapper json,
                                 @Value("${app.boss.base-url}") String baseUrl,
                                 @Value("${app.boss.internal-client-id:}") String clientId,
                                 @Value("${app.boss.internal-client-secret:}") String clientSecret) {
        this.json = json;
        this.baseUrl = trimSlash(baseUrl);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public Challenge challenge(String phone, String purpose) {
        JsonNode body = request("POST", "/api/v1/auth/challenges", null,
                "{\"phone\":" + quoted(phone) + ",\"purpose\":" + quoted(purpose) + "}", null).body();
        return new Challenge(uuid(body, "challenge_id"), instant(body, "expires_at"), text(body, "mock_code"));
    }

    public Session verify(UUID challengeId, String phone, String code, String userAgent) {
        return session(request("POST", "/api/v1/auth/verify", null,
                "{\"challenge_id\":\"" + challengeId + "\",\"phone\":" + quoted(phone)
                        + ",\"code\":" + quoted(code) + "}", userAgent));
    }

    public Session refresh(String refreshCookie, String userAgent) {
        return session(request("POST", "/api/v1/auth/refresh", null, "{}", userAgent, refreshCookie));
    }

    public Session passwordLogin(String phone, String password, String userAgent) {
        return session(request("POST", "/api/v1/auth/password-login", null,
                "{\"phone\":" + quoted(phone) + ",\"password\":" + quoted(password) + "}", userAgent));
    }

    public Session resetPassword(UUID challengeId, String phone, String code, String newPassword, String userAgent) {
        return session(request("POST", "/api/v1/auth/password-reset", null,
                "{\"challenge_id\":\"" + challengeId + "\",\"phone\":" + quoted(phone)
                        + ",\"code\":" + quoted(code) + ",\"new_password\":" + quoted(newPassword) + "}", userAgent));
    }

    public void setPassword(String accessToken, String password, String currentPassword) {
        String current = currentPassword == null ? "null" : quoted(currentPassword);
        request("POST", "/api/v1/auth/password", accessToken,
                "{\"password\":" + quoted(password) + ",\"current_password\":" + current + "}", null);
    }

    public void logout(String accessToken) {
        request("POST", "/api/v1/auth/logout", accessToken, "{}", null);
    }

    public User currentUser(String accessToken) {
        JsonNode body = request("GET", "/api/v1/me", accessToken, null, null).body();
        return new User(uuid(body, "user_id"), text(body, "display_name"), text(body, "masked_phone"), text(body, "status"));
    }

    public User updateDisplayName(String accessToken, String displayName) {
        JsonNode body = request("PUT", "/api/v1/me", accessToken,
                "{\"display_name\":" + quoted(displayName) + "}", null).body();
        return new User(uuid(body, "user_id"), text(body, "display_name"), text(body, "masked_phone"), text(body, "status"));
    }

    public Contexts contexts(String accessToken) {
        JsonNode body = request("GET", "/api/v1/me/contexts", accessToken, null, null).body();
        List<Tenant> tenants = new ArrayList<>();
        for (JsonNode tenant : body.path("tenants")) {
            tenants.add(new Tenant(uuid(tenant, "tenant_id"), text(tenant, "product_domain"), text(tenant, "tenant_type"),
                    text(tenant, "tenant_name"), text(tenant, "tenant_status"), text(tenant, "role_code"),
                    tenant.path("is_owner").asBoolean(false), tenant.path("seat_assigned").asBoolean(false)));
        }
        return new Contexts(uuid(body, "user_id"), tenants);
    }

    public TenantOverview tenantOverview(String accessToken, UUID tenantId) {
        JsonNode body = request("GET", "/api/v1/recruitment/tenants/" + tenantId + "/overview", accessToken, null, null).body();
        return new TenantOverview(uuid(body,"tenant_id"), text(body,"tenant_type"), text(body,"tenant_status"),
                body.path("talent_pool_sharing_enabled").asBoolean(false), body.path("job_pool_sharing_enabled").asBoolean(false));
    }

    public boolean hasPermission(String accessToken, UUID tenantId, String permissionCode) {
        JsonNode body = request("GET", "/api/v1/recruitment/tenants/" + tenantId + "/permissions/" + encode(permissionCode), accessToken, null, null).body();
        return body.path("allowed").asBoolean(false);
    }

    public Map<String, Object> creditCodeAvailability(String accessToken, String creditCode) {
        JsonNode body = request("GET", "/api/v1/recruitment/enterprise-registrations/credit-code-availability?creditCode=" + encode(creditCode), accessToken, null, null).body();
        return Map.of("available", body.path("available").asBoolean(false));
    }

    /** Streams the enterprise licence file to BOSS, which owns the registration document. */
    public JsonNode uploadEnterpriseLicense(String accessToken, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException("EMPTY_FILE", "营业执照文件不能为空", HttpStatus.BAD_REQUEST);
        try {
            String boundary = "----RecruitmentTenant" + UUID.randomUUID().toString().replace("-", "");
            String filename = (file.getOriginalFilename() == null ? "license" : file.getOriginalFilename()).replace("\"", "_");
            String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            byte[] opening = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/recruitment/enterprise-registration-documents"))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(Arrays.asList(opening, file.getBytes(), closing))).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw bossError(response.statusCode(), body);
            return body;
        } catch (ApiException exception) { throw exception; }
        catch (IOException exception) { throw new ApiException("BOSS_UNAVAILABLE", "BOSS 文件服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new ApiException("BOSS_UNAVAILABLE", "BOSS 文件服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE); }
    }

    public JsonNode submitEnterpriseRegistration(String accessToken, String legalName, String creditCode, UUID licenseDocumentId,
                                                  String contactName, String contactPhone) {
        return request("POST", "/api/v1/recruitment/enterprise-registrations", accessToken,
                "{\"legal_name\":" + quoted(legalName) + ",\"credit_code\":" + quoted(creditCode) + ",\"license_document_id\":\"" + licenseDocumentId
                        + "\",\"contact_name\":" + quoted(contactName) + ",\"contact_phone\":" + quoted(contactPhone) + "}", null).body();
    }

    /** 查询当前用户最近一次企业注册申请（含待审核状态与驳回原因），透传 BOSS 响应 */
    public JsonNode myEnterpriseRegistration(String accessToken) {
        return request("GET", "/api/v1/recruitment/enterprise-registrations/me", accessToken, null, null).body();
    }

    public List<DirectoryTenant> searchEnterprises(String accessToken, String keyword) {
        JsonNode body = request("GET", "/api/v1/recruitment/enterprises/search?keyword=" + encode(keyword), accessToken, null, null).body();
        List<DirectoryTenant> result = new ArrayList<>();
        for (JsonNode item : body) result.add(new DirectoryTenant(uuid(item, "tenant_id"), text(item, "tenant_name"), text(item, "legal_name")));
        return result;
    }

    public JsonNode applyToJoin(String accessToken, UUID tenantId, String roleCode, String reason) { return request("POST", "/api/v1/recruitment/tenants/" + tenantId + "/join-applications", accessToken, "{\"role_code\":" + quoted(roleCode) + ",\"reason\":" + quoted(reason) + "}", null).body(); }
    public JsonNode enterpriseOverview(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "overview"); }
    public JsonNode enterpriseMembers(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "members"); }
    public JsonNode enterpriseInvitations(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "invitations"); }
    public JsonNode enterpriseJoinApplications(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "join-applications"); }
    public JsonNode enterpriseBilling(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "billing"); }
    public JsonNode myCreditConsumptions(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "my-credit-consumptions"); }
    public JsonNode enterpriseCreditConsumptions(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "credit-consumptions"); }
    public JsonNode enterpriseRoles(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "roles"); }
    public JsonNode permissionCatalog(String accessToken, UUID tenantId) { return recruitmentGet(accessToken, tenantId, "permission-catalog"); }
    public JsonNode createInvitation(String accessToken, UUID tenantId, String roleCode, int maxUses, Instant expiresAt, String note) { return request("POST", "/api/v1/recruitment/tenants/" + tenantId + "/invitations", accessToken, "{\"role_code\":" + quoted(roleCode) + ",\"max_uses\":" + maxUses + ",\"expires_at\":" + quoted(expiresAt.toString()) + ",\"note\":" + quoted(note) + "}", null).body(); }
    public void claimInvitation(String accessToken, String invitationToken) { request("POST", "/api/v1/recruitment/invitations/claim", accessToken, "{\"invitation_token\":" + quoted(invitationToken) + "}", null); }
    public void decideJoinApplication(String accessToken, UUID applicationId, boolean approve, String reason) { request("POST", "/api/v1/recruitment/join-applications/" + applicationId + "/decision", accessToken, "{\"approve\":" + approve + ",\"reason\":" + quoted(reason) + "}", null); }
    public void assignOwnerSeat(String accessToken, UUID tenantId) { request("POST", "/api/v1/recruitment/tenants/" + tenantId + "/owner-seat/assign", accessToken, "{}", null); }
    public void releaseOwnerSeat(String accessToken, UUID tenantId) { request("POST", "/api/v1/recruitment/tenants/" + tenantId + "/owner-seat/release", accessToken, "{}", null); }
    public void removeMember(String accessToken, UUID tenantId, UUID userId) { request("POST", "/api/v1/recruitment/tenants/" + tenantId + "/members/" + userId + "/remove", accessToken, "{}", null); }
    public void updateFeatureSettings(String accessToken, UUID tenantId, boolean talent, boolean jobs) { request("PUT", "/api/v1/recruitment/tenants/" + tenantId + "/feature-settings", accessToken, "{\"talent_pool_sharing_enabled\":" + talent + ",\"job_pool_sharing_enabled\":" + jobs + "}", null); }
    public JsonNode createRole(String accessToken, UUID tenantId, String code, String displayName, JsonNode permissionCodes) { return request("POST", "/api/v1/recruitment/tenants/" + tenantId + "/roles", accessToken, "{\"code\":" + quoted(code) + ",\"display_name\":" + quoted(displayName) + ",\"permission_codes\":" + permissionCodes + "}", null).body(); }
    public void updateRolePermissions(String accessToken, UUID tenantId, UUID roleId, JsonNode permissionCodes) { request("PUT", "/api/v1/recruitment/tenants/" + tenantId + "/roles/" + roleId + "/permissions", accessToken, "{\"permission_codes\":" + permissionCodes + "}", null); }
    public void updateMemberRole(String accessToken, UUID tenantId, UUID userId, String roleCode) { request("PUT", "/api/v1/recruitment/tenants/" + tenantId + "/members/" + userId + "/role", accessToken, "{\"role_code\":" + quoted(roleCode) + "}", null); }
    private JsonNode recruitmentGet(String accessToken, UUID tenantId, String resource) { return request("GET", "/api/v1/recruitment/tenants/" + tenantId + "/" + resource, accessToken, null, null).body(); }

    private Session session(Response response) {
        JsonNode body = response.body();
        return new Session(uuid(body, "user_id"), text(body, "access_token"), instant(body, "expires_at"),
                body.path("new_user").asBoolean(false), body.path("password_setup_required").asBoolean(false), response.setCookie());
    }

    private String machineAccessToken() {
        return machineIdentity().value();
    }

    /** 供受信任的下游内部服务调用携带 BOSS OAuth machine token；不得暴露给浏览器。 */
    public String internalAccessToken() {
        return machineAccessToken();
    }

    public JsonNode claimRecruitmentDataDeletion() {
        return request("POST", "/internal/v1/recruitment-data-deletion/claim", machineAccessToken(), "{}", null).body();
    }

    public void completeRecruitmentDataDeletion(UUID taskId) {
        request("POST", "/internal/v1/recruitment-data-deletion/" + taskId + "/complete", machineAccessToken(), "{}", null);
    }

    public void failRecruitmentDataDeletion(UUID taskId, String error) {
        request("POST", "/internal/v1/recruitment-data-deletion/" + taskId + "/fail", machineAccessToken(),
                "{\"error\":" + quoted(error == null ? "招聘数据删除失败" : error) + "}", null);
    }

    public AiAuthorization authorizeAiExecution(UUID tenantId, UUID actorId, String taskId, String capability,
                                                String productDomain, String idempotencyKey) {
        JsonNode body = request("POST", "/internal/ai/execution-authorizations", machineAccessToken(),
                "{\"tenant_id\":\"" + tenantId + "\",\"actor_user_id\":\"" + actorId
                        + "\",\"task_id\":" + quoted(taskId) + ",\"product_domain\":" + quoted(productDomain)
                        + ",\"capability_code\":" + quoted(capability) + ",\"idempotency_key\":" + quoted(idempotencyKey) + "}", null).body();
        return new AiAuthorization(uuid(body,"authorization_id"), uuid(body,"grant_id"), uuid(body,"reservation_id"),
                text(body,"grant"), text(body,"idempotency_key"), instant(body,"authorization_expires_at"),
                text(body,"model_id"), text(body,"tokenizer_id"), uuid(body,"tenant_id"));
    }

    public void reportAiUsage(AiAuthorization auth, String status, String model, long input, long output,
                              int retryCount, String resultReference) {
        request("POST", "/internal/ai/usage-reports", machineAccessToken(),
                "{\"authorization_id\":\"" + auth.authorizationId() + "\",\"reservation_id\":\"" + auth.reservationId()
                        + "\",\"idempotency_key\":" + quoted(auth.idempotencyKey()) + ",\"task_status\":" + quoted(status)
                        + ",\"model_id\":" + quoted(model) + ",\"input_tokens\":" + input + ",\"output_tokens\":" + output
                        + ",\"retry_count\":" + retryCount + ",\"result_reference\":" + quoted(resultReference) + "}", null);
    }

    public void cancelAiExecution(AiAuthorization auth) {
        request("POST", "/internal/ai/execution-cancellations", machineAccessToken(),
                "{\"authorization_id\":\"" + auth.authorizationId() + "\",\"reservation_id\":\"" + auth.reservationId()
                        + "\",\"idempotency_key\":" + quoted(auth.idempotencyKey()) + "}", null);
    }

    public record AiAuthorization(UUID authorizationId, UUID grantId, UUID reservationId, String grant,
                                  String idempotencyKey, Instant expiresAt, String modelId, String tokenizerId,
                                  UUID tenantId) {
        public AiAuthorization(UUID authorizationId, UUID grantId, UUID reservationId, String grant,
                               String idempotencyKey, Instant expiresAt, String modelId, String tokenizerId) {
            this(authorizationId, grantId, reservationId, grant, idempotencyKey, expiresAt, modelId, tokenizerId, null);
        }
    }

    /** Internal, read-only BOSS operations data. Never expose this machine credential to browsers. */
    public JsonNode internalRecruitmentPlatformQuery(String path) {
        return request("GET", "/internal/v1/recruitment-platform" + path, machineAccessToken(), null, null).body();
    }

    /** Resolves a Recruitment tenant name for local records that persist only tenant_id. */
    public TenantIdentity internalTenantIdentity(UUID tenantId) {
        JsonNode body = internalRecruitmentPlatformQuery("/tenant?tenantId=" + tenantId);
        return new TenantIdentity(uuid(body, "tenant_id"), text(body, "tenant_name"),
                text(body, "tenant_type"), text(body, "tenant_status"));
    }

    private MachineIdentity machineIdentity() {
        MachineToken cached = machineToken;
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            return new MachineIdentity(cached.value(), cached.apiClientId());
        }
        synchronized (this) {
            cached = machineToken;
            if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
                return new MachineIdentity(cached.value(), cached.apiClientId());
            }
            if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
                throw new ApiException("BOSS_INTERNAL_CLIENT_NOT_CONFIGURED", "BOSS 集成尚未完成配置", HttpStatus.SERVICE_UNAVAILABLE);
            }
            Response response = request("POST", "/internal/v1/oauth/token", null,
                    "{\"client_id\":" + quoted(clientId) + ",\"client_secret\":" + quoted(clientSecret) + "}", null);
            String token = text(response.body(), "access_token");
            long expires = response.body().path("expires_in").asLong(900);
            machineToken = new MachineToken(token, null, Instant.now().plusSeconds(expires));
            return new MachineIdentity(token, null);
        }
    }

    private Response request(String method, String path, String bearer, String body, String userAgent) {
        return request(method, path, bearer, body, userAgent, null, "application/json");
    }

    private Response request(String method, String path, String bearer, String body, String userAgent, String cookie) {
        return request(method, path, bearer, body, userAgent, cookie, "application/json");
    }

    private Response request(String method, String path, String bearer, String body, String userAgent, String cookie,
                             String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json");
            if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
            if (userAgent != null && !userAgent.isBlank()) builder.header("User-Agent", userAgent);
            if (cookie != null && !cookie.isBlank()) builder.header("Cookie", "boss_refresh=" + cookie);
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw bossError(response.statusCode(), parsed);
            return new Response(parsed, response.headers().firstValue("Set-Cookie").orElse(null));
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException("BOSS_UNAVAILABLE", "BOSS 服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException("BOSS_UNAVAILABLE", "BOSS 服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            throw new ApiException("BOSS_PROTOCOL_ERROR", "BOSS 返回了无法处理的响应", HttpStatus.BAD_GATEWAY);
        }
    }

    private static ApiException bossError(int status, JsonNode body) {
        String code = text(body, "code");
        String message = text(body, "message");
        HttpStatus mapped = switch (status) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new ApiException(code == null || code.isBlank() ? "BOSS_REQUEST_FAILED" : code,
                message == null || message.isBlank() ? "BOSS 请求失败" : message, mapped);
    }

    private static UUID uuid(JsonNode node, String field) { return UUID.fromString(node.path(field).asText()); }
    private static UUID uuidOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.path(field).asText().isBlank()
                ? UUID.fromString(node.path(field).asText()) : null;
    }
    private static Instant instant(JsonNode node, String field) { return Instant.parse(node.path(field).asText()); }
    private static Instant instantOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.path(field).asText().isBlank()
                ? Instant.parse(node.path(field).asText()) : null;
    }
    private static String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asText() : null; }
    private String quoted(String value) { try { return json.writeValueAsString(value == null ? "" : value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }

    public record Challenge(UUID id, Instant expiresAt, String mockCode) { }
    public record Session(UUID userId, String accessToken, Instant expiresAt, boolean newUser,
                          boolean passwordSetupRequired, String setCookie) { }
    public record User(UUID userId, String displayName, String maskedPhone, String status) { }
    public record Contexts(UUID userId, List<Tenant> tenants) { }
    public record TenantIdentity(UUID tenantId, String tenantName, String tenantType, String tenantStatus) { }
    public record Tenant(UUID tenantId, String productDomain, String tenantType, String tenantName, String tenantStatus,
                         String roleCode, boolean owner, boolean seatAssigned) { }
    public record TenantOverview(UUID tenantId, String tenantType, String tenantStatus, boolean talentPoolSharingEnabled, boolean jobPoolSharingEnabled) { }
    public record DirectoryTenant(UUID tenantId, String tenantName, String legalName) { }
    private record MachineToken(String value, UUID apiClientId, Instant expiresAt) { }
    private record MachineIdentity(String value, UUID apiClientId) { }
    private record Response(JsonNode body, String setCookie) { }
}
