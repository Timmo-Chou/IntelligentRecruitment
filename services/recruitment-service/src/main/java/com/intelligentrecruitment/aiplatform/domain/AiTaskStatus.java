package com.intelligentrecruitment.aiplatform.domain;

public enum AiTaskStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_INPUT,
    PARTIALLY_COMPLETED,
    COMPLETED,
    FAILED,
    CANCELLED
}

