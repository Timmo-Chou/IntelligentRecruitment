package com.intelligentrecruitment.tenancy;

import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.TenancyService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenancyIsolationTest {

    @Test
    void blocksCreatingASecondPersonalWorkspace() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BillingService billing = mock(BillingService.class);
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(true);

        TenancyService service = new TenancyService(jdbc, billing);

        assertThatThrownBy(() -> service.createPersonalWorkspace(userId, "另一个个人空间"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("PERSONAL_WORKSPACE_LIMIT"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void companyRoleAloneDoesNotGrantWorkspaceBillingAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(workspaceId), eq(userId))).thenReturn(false);

        BillingService service = new BillingService(jdbc);

        assertThatThrownBy(() -> service.view(userId, workspaceId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("WORKSPACE_NOT_FOUND"));
    }
}
