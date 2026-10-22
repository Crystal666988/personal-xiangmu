package com.tradeagent.core.dag.model;

import lombok.Data;
import java.util.Map;

@Data
public class ExecutedStepSnapshot {
    public enum CompensationStatus { PENDING, RUNNING, APPLIED, COMPLETED, FAILED, UNCERTAIN }
    private String stepId;
    private String toolId;
    private Map<String, Object> originalParams;
    private Object forwardResult;
    private boolean sideEffects;
    private boolean compensationSupported;
    private boolean compensationRetrySafe;
    private String compensationKey;
    private CompensationStatus compensationStatus = CompensationStatus.PENDING;
    private int compensationAttempts;
    private Object compensationResult;
    private long compensationLatencyMs;
    private long lastCompensationLatencyMs;
    private String compensationErrorCode;
}
