package com.intelligentrecruitment.aiplatform.domain;

import java.util.Locale;
import java.util.Map;

/** One explicit status contract for task reads and asynchronous status events. */
public final class PlatformTaskStatusProjection {
    private static final Map<String,String> EVENT_STATUS = Map.ofEntries(
            Map.entry("task.started","RUNNING"),
            Map.entry("task.waiting_for_input","WAITING_FOR_INPUT"),
            Map.entry("task.item_completed","PARTIALLY_COMPLETED"),
            Map.entry("task.item_failed","PARTIALLY_COMPLETED"),
            Map.entry("task.partially_completed","PARTIALLY_COMPLETED"),
            Map.entry("task.completed","SUCCEEDED"),
            Map.entry("task.failed","FAILED"),
            Map.entry("task.cancelled","CANCELLED"),
            Map.entry("task.cancel_requested","CANCEL_REQUESTED"),
            Map.entry("task.reconciliation_required","RECONCILIATION_REQUIRED"),
            Map.entry("task.result_mapping_failed","RESULT_MAPPING_FAILED"));

    private PlatformTaskStatusProjection() { }

    public static AiTaskStatus fromTaskRead(String status) {
        if(status==null)throw new IllegalArgumentException("AI Platform task status is missing");
        return switch(status.toLowerCase(Locale.ROOT)) {
            case "queued" -> AiTaskStatus.QUEUED;
            case "running" -> AiTaskStatus.RUNNING;
            case "waiting_for_input" -> AiTaskStatus.WAITING_FOR_INPUT;
            case "partially_completed" -> AiTaskStatus.PARTIALLY_COMPLETED;
            case "cancel_requested" -> AiTaskStatus.CANCEL_REQUESTED;
            case "reconciliation_required" -> AiTaskStatus.RECONCILIATION_REQUIRED;
            case "result_mapping_failed" -> AiTaskStatus.RESULT_MAPPING_FAILED;
            case "succeeded","completed" -> AiTaskStatus.COMPLETED;
            case "failed" -> AiTaskStatus.FAILED;
            case "cancelled" -> AiTaskStatus.CANCELLED;
            default -> throw new IllegalArgumentException("AI Platform returned unsupported task status: "+status);
        };
    }

    public static AiTaskStatus fromWebhook(String eventType,String status) {
        if(eventType==null||status==null)return null;
        String expected=EVENT_STATUS.get(eventType);
        if(expected==null&&!("task.progress".equals(eventType)||"usage.reported".equals(eventType)))return null;
        if(expected!=null&&!expected.equals(status))return null;
        try{return fromTaskRead(status);}catch(IllegalArgumentException ignored){return null;}
    }
}
