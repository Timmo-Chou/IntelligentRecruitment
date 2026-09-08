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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Anti-corruption layer for the BOSS control plane.  Recruitment business code must use this
 * class instead of reading a local identity, workspace or billing table as an authority source.
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
                "{\"challengeId\":\"" + challengeId + "\",\"phone\":" + quoted(phone)
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
                "{\"challengeId\":\"" + challengeId + "\",\"phone\":" + quoted(phone)
                        + ",\"code\":" + quoted(code) + ",\"newPassword\":" + quoted(newPassword) + "}", userAgent));
    }

    public void setPassword(String accessToken, String password, String currentPassword) {
        String current = currentPassword == null ? "null" : quoted(currentPassword);
        request("POST", "/api/v1/auth/password", accessToken,
                "{\"password\":" + quoted(password) + ",\"currentPassword\":" + current + "}", null);
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
                "{\"displayName\":" + quoted(displayName) + "}", null).body();
        return new User(uuid(body, "user_id"), text(body, "display_name"), text(body, "masked_phone"), text(body, "status"));
    }

    public Contexts contexts(String accessToken) {
        JsonNode body = request("GET", "/api/v1/me/contexts", accessToken, null, null).body();
        List<Tenant> tenants = new ArrayList<>();
        for (JsonNode tenant : body.path("tenants")) {
            tenants.add(new Tenant(uuid(tenant, "tenant_id"), text(tenant, "tenant_type"),
                    text(tenant, "tenant_name"), text(tenant, "tenant_status")));
        }
        List<Company> companies = new ArrayList<>();
        for (JsonNode company : body.path("companies")) {
            companies.add(new Company(uuid(company, "company_id"), uuid(company, "tenant_id"),
                    text(company, "legal_name"), text(company, "entity_type"),
                    text(company, "company_status"), text(company, "tenant_status"),
                    company.path("is_company_owner").asBoolean(false)));
        }
        return new Contexts(uuid(body, "user_id"), tenants, companies);
    }

    public Registration registerCompany(String accessToken, String legalName, String creditCode,
                                        String licenseReference, String contactName, String contactPhone) {
        JsonNode body = request("POST", "/api/v1/companies/registrations", accessToken,
                "{\"legalName\":" + quoted(legalName) + ",\"creditCode\":" + quoted(creditCode)
                        + ",\"licenseReference\":" + quoted(licenseReference) + ",\"contactName\":"
                        + quoted(contactName) + ",\"contactPhone\":" + quoted(contactPhone) + "}", null).body();
        return new Registration(uuid(body, "tenant_id"), uuid(body, "company_id"), uuid(body, "verification_request_id"), text(body, "status"));
    }

    /** Streams the original license file to BOSS; Recruitment never stores the document. */
    public LicenseDocument uploadLicenseDocument(String accessToken, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("EMPTY_FILE", "营业执照文件不能为空", HttpStatus.BAD_REQUEST);
        }
        try {
            String boundary = "----RecruitmentBoss" + UUID.randomUUID().toString().replace("-", "");
            String filename = file.getOriginalFilename() == null ? "license" : file.getOriginalFilename().replace("\"", "_");
            String type = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            byte[] opening = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/companies/registrations/license-files"))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(Arrays.asList(opening, file.getBytes(), closing)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw bossError(response.statusCode(), body);
            return new LicenseDocument(text(body, "reference"), text(body, "filename"), text(body, "contentType"), body.path("sizeBytes").asLong());
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException("BOSS_UNAVAILABLE", "BOSS 文件服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException("BOSS_UNAVAILABLE", "BOSS 文件服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** Streams transfer evidence directly to BOSS; Recruitment never retains payment evidence. */
    public void uploadTransferProof(String accessToken, UUID companyId, UUID orderId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException("EMPTY_FILE", "转账凭证不能为空", HttpStatus.BAD_REQUEST);
        try {
            String boundary = "----RecruitmentBoss" + UUID.randomUUID().toString().replace("-", "");
            String filename = file.getOriginalFilename() == null ? "transfer-proof" : file.getOriginalFilename().replace("\"", "_");
            String type = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            byte[] opening = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/companies/" + companyId + "/payments/orders/" + orderId + "/transfer-proof"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(Arrays.asList(opening, file.getBytes(), closing))).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw bossError(response.statusCode(), body);
        } catch (ApiException exception) { throw exception; }
        catch (IOException exception) { throw new ApiException("BOSS_UNAVAILABLE", "BOSS 文件服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new ApiException("BOSS_UNAVAILABLE", "BOSS 文件服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE); }
    }

    public List<DirectoryCompany> searchCompanies(String accessToken, String query) {
        JsonNode body = request("GET", "/api/v1/companies/search?q=" + encode(query), accessToken, null, null).body();
        List<DirectoryCompany> companies = new ArrayList<>();
        for (JsonNode item : body) companies.add(new DirectoryCompany(uuid(item, "company_id"), text(item, "legal_name"), item.path("member_count").asInt()));
        return companies;
    }

    public void applyForMembership(String accessToken, UUID companyId) {
        request("POST", "/api/v1/companies/" + companyId + "/membership-applications", accessToken, "{}", null);
    }

    public JsonNode inviteCompanyMember(String accessToken, UUID companyId, String phone) {
        return request("POST", "/api/v1/companies/" + companyId + "/invitations", accessToken,
                "{\"phone\":" + quoted(phone) + "}", null).body();
    }

    public void decideMembershipApplication(String accessToken, UUID applicationId, boolean approve, String reason) {
        String body = "{\"approve\":" + approve + ",\"reason\":" + (reason == null ? "null" : quoted(reason)) + "}";
        request("POST", "/api/v1/membership-applications/" + applicationId + "/decision", accessToken, body, null);
    }

    public void setCompanyMemberStatus(String accessToken, UUID companyId, UUID userId, String status) {
        request("POST", "/api/v1/companies/" + companyId + "/members/" + userId + "/status", accessToken,
                "{\"status\":" + quoted(status) + "}", null);
    }

    public List<PendingRegistration> pendingRegistrations(String accessToken) {
        JsonNode body = request("GET", "/api/v1/me/company-registrations/pending", accessToken, null, null).body();
        List<PendingRegistration> registrations = new ArrayList<>();
        for (JsonNode item : body) registrations.add(new PendingRegistration(uuid(item, "verification_request_id"),
                uuid(item, "company_id"), text(item, "legal_name"), text(item, "status"), instant(item, "created_at")));
        return registrations;
    }

    public JsonNode companyWallet(String accessToken, UUID companyId) {
        return request("GET", "/api/v1/companies/" + companyId + "/wallet", accessToken, null, null).body();
    }

    public JsonNode companyStatements(String accessToken, UUID companyId) {
        return request("GET", "/api/v1/companies/" + companyId + "/statements", accessToken, null, null).body();
    }

    public JsonNode paymentContext(String accessToken, UUID companyId) {
        return request("GET", "/api/v1/companies/" + companyId + "/payments/recharge-context", accessToken, null, null).body();
    }

    public JsonNode createAlipayPayment(String accessToken, UUID companyId, long amountMinor, String payerName) {
        return request("POST", "/api/v1/companies/" + companyId + "/payments/orders/alipay", accessToken,
                "{\"amountMinor\":" + amountMinor + ",\"payerName\":" + quoted(payerName) + "}", null).body();
    }

    public JsonNode createTransferPayment(String accessToken, UUID companyId, long amountMinor, String payerName) {
        return request("POST", "/api/v1/companies/" + companyId + "/payments/orders/transfer", accessToken,
                "{\"amountMinor\":" + amountMinor + ",\"payerName\":" + quoted(payerName) + "}", null).body();
    }

    public JsonNode companyGovernance(String accessToken, UUID companyId, String resource) {
        return request("GET", "/api/v1/companies/" + companyId + "/" + resource, accessToken, null, null).body();
    }

    public JsonNode accessibleOrgUnits(String accessToken, UUID companyId) {
        return request("GET", "/api/v1/companies/" + companyId + "/org-units/accessible", accessToken, null, null).body();
    }

    public List<String> companyPermissions(String accessToken, UUID companyId) {
        JsonNode body = request("GET", "/api/v1/scopes/COMPANY/" + companyId + "/my-permissions", accessToken, null, null).body();
        List<String> result = new ArrayList<>();
        for (JsonNode item : body) result.add(item.asText());
        return result;
    }

    public void rememberOrgUnit(String accessToken, UUID companyId, UUID orgUnitId) {
        request("POST", "/api/v1/companies/" + companyId + "/org-units/recent", accessToken,
                "{\"orgUnitId\":\"" + orgUnitId + "\"}", null);
    }

    public boolean quotaCheck(UUID companyId, String capability) {
        return internal("POST", "/internal/v1/authorization/quota-check", "{\"companyId\":\""
                + companyId + "\",\"capability\":" + quoted(capability) + "}")
                .path("authorized").asBoolean(false);
    }

    public long quoteUnitPrice(UUID companyId, String capability) {
        return internal("POST", "/internal/v1/billing/quote", "{\"companyId\":\""
                + companyId + "\",\"capability\":" + quoted(capability) + "}")
                .path("unit_price_minor").asLong(-1);
    }

    public void reserve(UUID companyId, String reservationKey, long estimatedAmountMinor) {
        JsonNode result = internal("POST", "/internal/v1/billing/reservations", "{\"companyId\":\""
                + companyId + "\",\"reservationKey\":" + quoted(reservationKey)
                + ",\"estimatedAmountMinor\":" + estimatedAmountMinor + "}");
        if (!result.path("reserved").asBoolean(false)) {
            throw new ApiException("INSUFFICIENT_BALANCE", "BOSS 余额不足", HttpStatus.PAYMENT_REQUIRED);
        }
    }

    public void release(UUID companyId, String reservationKey) {
        internal("POST", "/internal/v1/billing/reservations/" + encodePath(reservationKey) + "/release", "{}");
    }

    public void reportUsage(String usageEventId, String executionId, UUID companyId, String capability,
                            String outcome, long successfulUnits, long inputTokens, long outputTokens,
                            Instant occurredAt) {
        MachineIdentity identity = machineIdentity();
        internal("POST", "/internal/v1/usage-events", "{\"usageEventId\":" + quoted(usageEventId)
                + ",\"executionId\":" + quoted(executionId) + ",\"sourceApiClientId\":\""
                + identity.apiClientId() + "\",\"companyId\":\"" + companyId + "\",\"capability\":"
                + quoted(capability) + ",\"outcome\":" + quoted(outcome) + ",\"successfulUnits\":"
                + successfulUnits + ",\"inputTokens\":" + inputTokens + ",\"outputTokens\":"
                + outputTokens + ",\"occurredAt\":" + quoted(occurredAt.toString()) + "}");
    }

    /** Checks BOSS Company membership/roles. A missing capability means ordinary, non-AI RBAC. */
    public boolean permitted(UUID userId, UUID companyId, String permission, String capability) {
        String capabilityValue = capability == null ? "null" : quoted(capability);
        JsonNode body = internal("POST", "/internal/v1/authorization/check", "{\"userId\":\"" + userId
                + "\",\"companyId\":\"" + companyId + "\",\"permission\":" + quoted(permission)
                + ",\"capability\":" + capabilityValue + "}");
        return body.path("permitted").asBoolean(false);
    }

    private Session session(Response response) {
        JsonNode body = response.body();
        return new Session(uuid(body, "user_id"), text(body, "access_token"), instant(body, "expires_at"),
                body.path("new_user").asBoolean(false), body.path("password_setup_required").asBoolean(false), response.setCookie());
    }

    private JsonNode internal(String method, String path, String body) {
        return request(method, path, machineAccessToken(), body, null).body();
    }

    private String machineAccessToken() {
        return machineIdentity().value();
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
            String form = "grant_type=client_credentials&client_id=" + encode(clientId)
                    + "&client_secret=" + encode(clientSecret);
            Response response = request("POST", "/oauth2/token", null, form, null, null,
                    "application/x-www-form-urlencoded");
            String token = text(response.body(), "access_token");
            long expires = response.body().path("expires_in").asLong(900);
            UUID apiClientId = uuid(response.body(), "api_client_id");
            machineToken = new MachineToken(token, apiClientId, Instant.now().plusSeconds(expires));
            return new MachineIdentity(token, apiClientId);
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
    private static Instant instant(JsonNode node, String field) { return Instant.parse(node.path(field).asText()); }
    private static String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asText() : null; }
    private String quoted(String value) { try { return json.writeValueAsString(value == null ? "" : value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String encodePath(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }

    public record Challenge(UUID id, Instant expiresAt, String mockCode) { }
    public record Session(UUID userId, String accessToken, Instant expiresAt, boolean newUser,
                          boolean passwordSetupRequired, String setCookie) { }
    public record User(UUID userId, String displayName, String maskedPhone, String status) { }
    public record Contexts(UUID userId, List<Tenant> tenants, List<Company> companies) { }
    public record Tenant(UUID tenantId, String tenantType, String tenantName, String tenantStatus) { }
    public record Company(UUID companyId, UUID tenantId, String legalName, String entityType,
                          String companyStatus, String tenantStatus, boolean companyOwner) { }
    public record Registration(UUID tenantId, UUID companyId, UUID verificationRequestId, String status) { }
    public record LicenseDocument(String reference, String filename, String contentType, long sizeBytes) { }
    public record DirectoryCompany(UUID companyId, String legalName, int memberCount) { }
    public record PendingRegistration(UUID verificationRequestId, UUID companyId, String legalName,
                                      String status, Instant createdAt) { }
    private record MachineToken(String value, UUID apiClientId, Instant expiresAt) { }
    private record MachineIdentity(String value, UUID apiClientId) { }
    private record Response(JsonNode body, String setCookie) { }
}
