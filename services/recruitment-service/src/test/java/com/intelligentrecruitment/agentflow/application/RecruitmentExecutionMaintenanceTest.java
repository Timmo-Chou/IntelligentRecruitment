package com.intelligentrecruitment.agentflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecruitmentExecutionMaintenanceTest {

    @Test
    void cancelledRootExecutionIsTerminalForUpgradeDrain() {
        assertThat(RecruitmentExecutionMaintenance.isTerminalRootExecutionStatus("CANCELLED")).isTrue();
        assertThat(RecruitmentExecutionMaintenance.isTerminalRootExecutionStatus("SETTLED")).isTrue();
        assertThat(RecruitmentExecutionMaintenance.isTerminalRootExecutionStatus("RELEASED")).isTrue();
        assertThat(RecruitmentExecutionMaintenance.isTerminalRootExecutionStatus("RUNNING")).isFalse();
    }
}
