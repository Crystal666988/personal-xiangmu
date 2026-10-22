package com.tradeagent.core.dag.model;

import com.tradeagent.core.dag.ReActStep;
import com.tradeagent.modules.ai.intent.ValidatedPlanExecutor;
import com.tradeagent.modules.ai.tools.ToolExecutionTrace;
import lombok.Data;
import java.util.*;

/** Durable data only. Model credentials and live Java execution objects must never be stored. */
@Data
public class DagExecutionState {
    public enum Status { READY, RUNNING, WAITING_APPROVAL, APPROVED, REJECTED,
        FAILED, EXECUTION_UNCERTAIN, COMPENSATION_REQUIRED, COMPENSATING, COMPENSATION_FAILED,
        COMPENSATION_UNCERTAIN, COMPLETED, ROLLED_BACK }
    private String originalQuery;
    private List<com.tradeagent.modules.ai.intent.RoutingPreviewService.UserSource> sourceMessages = new ArrayList<>();
    private String dagPlanJson;
    private ValidatedPlanExecutor.Prepared prepared;
    private List<String> completedStepIds = new ArrayList<>();
    private Map<String, Object> slotVariablesPool = new LinkedHashMap<>();
    private Map<Integer, Double> fobResults = new LinkedHashMap<>();
    private List<ToolExecutionTrace> traces = new ArrayList<>();
    private List<ReActStep> reactSteps = new ArrayList<>();
    private int currentStepIndex;
    private Status status = Status.READY;
    private Integer inFlightStep;
    private String approvalId;
    private String riskReason;
    private Map<String, Object> pendingParameters = new LinkedHashMap<>();
    private Map<String, Object> originalApprovalParameters = new LinkedHashMap<>();
    private String decisionAction;
    public record ApprovalDecision(String approvalId, String action, long decidedAt,
                                   Map<String, Object> originalParameters, Map<String, Object> effectiveParameters) {}
    private List<ApprovalDecision> approvalDecisions = new ArrayList<>();
    private long decidedAt;
    private String message;
    private List<ExecutedStepSnapshot> executedSteps = new ArrayList<>();
    private String abortReason;
    private boolean uncertainForwardOutcome;
    private Map<Integer, Map<String, Object>> stepParameterOverrides = new LinkedHashMap<>();
    private Map<Integer, Integer> reflectionAttempts = new LinkedHashMap<>();
    private Map<Integer, List<String>> attemptedParameterHashes = new LinkedHashMap<>();
    private Map<Integer, Set<String>> humanLockedParameters = new LinkedHashMap<>();
    private Set<Integer> forcedApprovalSteps = new LinkedHashSet<>();
    public record CorrectionRecord(int stepIndex, int attempt, String status, List<ToolCorrectionCandidate> evidence, long latencyMs) {}
    private List<CorrectionRecord> corrections = new ArrayList<>();
    private long reflectionModelMs;
    private long approvalRequestedAt;
    private long approvalWaitMs;
    private long finishedAt;
}
