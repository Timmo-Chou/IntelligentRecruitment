package com.intelligentrecruitment.aiplatform.domain;

public enum AiTaskStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_INPUT,
    PARTIALLY_COMPLETED,
    CANCEL_REQUESTED,
    RECONCILIATION_REQUIRED,
    RESULT_MAPPING_FAILED,
    COMPLETED,
    FAILED,
    CANCELLED
}
