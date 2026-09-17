package com.intelligentrecruitment.platform.admin.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlatformAdminService 单元测试。
 * 使用 Mockito mock JdbcTemplate，验证管理员 CRUD 的业务逻辑和参数校验。
 */
class PlatformAdminServiceTest {

    private JdbcTemplate jdbc;
    private PlatformAdminService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new PlatformAdminService(jdbc);
    }

    @Test
    void listAdmins_returnsAllRows() {
        // 准备：mock 返回两条管理员记录
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
                new PlatformAdminService.PlatformAdminRow(UUID.randomUUID(), null, "管理员A",
                        "hash-a", "SUPER_ADMIN", "ACTIVE", Instant.now(), Instant.now()),
                new PlatformAdminService.PlatformAdminRow(UUID.randomUUID(), null, "管理员B",
                        "hash-b", "PLATFORM_OPERATOR", "ACTIVE", Instant.now(), Instant.now())
        ));

        List<PlatformAdminService.PlatformAdminRow> result = service.listAdmins();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).displayName()).isEqualTo("管理员A");
    }

    @Test
    void getAdmin_existingId_returnsRow() {
        UUID adminId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(adminId))).thenReturn(List.of(
                new PlatformAdminService.PlatformAdminRow(adminId, null, "管理员A",
                        "hash-a", "SUPER_ADMIN", "ACTIVE", Instant.now(), Instant.now())
        ));

        PlatformAdminService.PlatformAdminRow result = service.getAdmin(adminId);

        assertThat(result.id()).isEqualTo(adminId);
        assertThat(result.role()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void getAdmin_notFound_throwsApiException() {
        UUID adminId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(adminId))).thenReturn(List.of());

        assertThatThrownBy(() -> service.getAdmin(adminId))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("ADMIN_NOT_FOUND"));
    }

    @Test
    void createAdmin_validInput_insertsAndReturnsRow() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        PlatformAdminService.PlatformAdminRow result = service.createAdmin("新管理员", "secret123", "SUPER_ADMIN");

        assertThat(result.displayName()).isEqualTo("新管理员");
        assertThat(result.role()).isEqualTo("SUPER_ADMIN");
        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void createAdmin_invalidRole_throwsApiException() {
        assertThatThrownBy(() -> service.createAdmin("管理员", "secret123", "INVALID_ROLE"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("INVALID_ROLE"));
    }

    @Test
    void createAdmin_shortKey_throwsApiException() {
        assertThatThrownBy(() -> service.createAdmin("管理员", "123", "SUPER_ADMIN"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("INVALID_KEY"));
    }

    @Test
    void createAdmin_blankDisplayName_throwsApiException() {
        assertThatThrownBy(() -> service.createAdmin("  ", "secret123", "SUPER_ADMIN"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void updateAdmin_validInput_updatesAndReturnsRow() {
        UUID adminId = UUID.randomUUID();
        Instant now = Instant.now();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(adminId))).thenReturn(List.of(
                new PlatformAdminService.PlatformAdminRow(adminId, null, "管理员A",
                        "hash-a", "PLATFORM_OPERATOR", "ACTIVE", now, now)
        ));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        PlatformAdminService.PlatformAdminRow result = service.updateAdmin(adminId, "SUPER_ADMIN", "DISABLED");

        assertThat(result.role()).isEqualTo("SUPER_ADMIN");
        assertThat(result.status()).isEqualTo("DISABLED");
    }

    @Test
    void disableAdmin_activeAdmin_setsDisabled() {
        UUID adminId = UUID.randomUUID();
        Instant now = Instant.now();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(adminId))).thenReturn(List.of(
                new PlatformAdminService.PlatformAdminRow(adminId, null, "管理员A",
                        "hash-a", "PLATFORM_OPERATOR", "ACTIVE", now, now)
        ));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.disableAdmin(adminId);

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void disableAdmin_alreadyDisabled_throwsConflict() {
        UUID adminId = UUID.randomUUID();
        Instant now = Instant.now();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(adminId))).thenReturn(List.of(
                new PlatformAdminService.PlatformAdminRow(adminId, null, "管理员A",
                        "hash-a", "PLATFORM_OPERATOR", "DISABLED", now, now)
        ));

        assertThatThrownBy(() -> service.disableAdmin(adminId))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("ADMIN_ALREADY_DISABLED"));
    }

    @Test
    void deleteAdmin_existingAdmin_deletesRow() {
        UUID adminId = UUID.randomUUID();
        Instant now = Instant.now();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(adminId))).thenReturn(List.of(
                new PlatformAdminService.PlatformAdminRow(adminId, null, "管理员A",
                        "hash-a", "PLATFORM_OPERATOR", "ACTIVE", now, now)
        ));
        when(jdbc.update(anyString(), eq(adminId))).thenReturn(1);

        service.deleteAdmin(adminId);

        verify(jdbc).update(anyString(), eq(adminId));
    }
}
