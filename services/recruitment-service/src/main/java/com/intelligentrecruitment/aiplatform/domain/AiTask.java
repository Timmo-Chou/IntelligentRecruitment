package com.intelligentrecruitment.aiplatform.domain;

import java.time.Instant;

public record AiTask(
        String aiTaskId,
        String businessTaskId,
        AiCapability capability,
        AiTaskStatus status,
        int completed,
        int total,
        int percent,
        int retryCount,
        Instant acceptedAt,
        String errorCode,
        String errorMessage,
        long stateVersion
) {
    public AiTask(String aiTaskId,String businessTaskId,AiCapability capability,AiTaskStatus status,int completed,int total,int percent,int retryCount,Instant acceptedAt,String errorCode,String errorMessage){
        this(aiTaskId,businessTaskId,capability,status,completed,total,percent,retryCount,acceptedAt,errorCode,errorMessage,0);
    }
}
