package com.tradeagent.core.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.model.*;
import com.tradeagent.modules.ai.intent.ValidatedPlanExecutor;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import com.tradeagent.modules.ai.tools.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static com.tradeagent.core.dag.model.DagExecutionState.Status.*;
import static com.tradeagent.core.dag.ExecutionCheckpointService.conflict;

/** Executes the already validated plan; no LLM is called during recovery. */
@Service
public class CheckpointedDagExecutor {
    private final ExecutionCheckpointService checkpoints;
    private final ToolRegistry tools;
    private final ObjectMapper json;
    private final SagaCompensationManager saga;
    private final ReflectionCorrectionHandler reflection;
    public CheckpointedDagExecutor(ExecutionCheckpointService checkpoints, ToolRegistry tools, ObjectMapper json) {
        this(checkpoints, tools, json, new SagaCompensationManager(checkpoints, tools,
                (cp, step, event) -> { throw new IllegalStateException("Saga audit store is required"); }), null);
    }
    public CheckpointedDagExecutor(ExecutionCheckpointService checkpoints, ToolRegistry tools, ObjectMapper json, SagaCompensationManager saga) {
        this(checkpoints, tools, json, saga, null);
    }
    @Autowired
    public CheckpointedDagExecutor(ExecutionCheckpointService checkpoints, ToolRegistry tools, ObjectMapper json,
                                  SagaCompensationManager saga, ReflectionCorrectionHandler reflection) {
        this.checkpoints = checkpoints; this.tools = tools; this.json = json; this.saga = saga;
        this.reflection = reflection;
    }
    public record View(String checkpointId, long version, long createdAt, String sessionId, String taskId, String status, int currentStepIndex,
                       int totalSteps, List<String> completedStepIds, String approvalId, String toolId,
                       Map<String, Object> parameters, String riskReason, String message, long expiresAt,
                       TurnTelemetry telemetry, List<ReActStep> reactSteps, List<CompensationProgress> compensations,
                       Map<Integer, Integer> reflectionAttempts, Timing timings) {}
    public record CompensationProgress(String stepId, String toolId, String status, int attempts, String idempotencyKey, String errorCode, long latencyMs) {}
    public record Timing(long executionWallMs, long forwardToolMs, long reflectionModelMs, long compensationToolMs,
                         long approvalWaitMs, String scope) {}

    public View start(String tenant, String session, String task, String query, Object plan, ValidatedPlanExecutor.Prepared prepared) {
        return start(tenant, session, task, query, plan, prepared, List.of());
    }
    public View start(String tenant, String session, String task, String query, Object plan, ValidatedPlanExecutor.Prepared prepared,
                      List<com.tradeagent.modules.ai.intent.RoutingPreviewService.UserSource> sources) {
        return start(tenant, session, task, query, plan, prepared, sources, null);
    }
    public View start(String tenant, String session, String task, String query, Object plan, ValidatedPlanExecutor.Prepared prepared,
                      List<com.tradeagent.modules.ai.intent.RoutingPreviewService.UserSource> sources, RoutingModelConfig model) {
        ReflectionCorrectionHandler.requireTenant(tenant, model);
        if (!prepared.ready()) throw new IllegalArgumentException("全计划业务预检未通过");
        DagExecutionState state = new DagExecutionState(); state.setOriginalQuery(query); state.setPrepared(prepared);
        state.setSourceMessages(new ArrayList<>(sources));
        prepared.calls().forEach(call -> state.getSlotVariablesPool().put("step_" + call.index() + ".parameters", call.parameters()));
        try { state.setDagPlanJson(json.writeValueAsString(plan)); }
        catch (Exception e) { throw new IllegalStateException("无法保存计划", e); }
        var cp = checkpoints.saveCheckpoint(tenant, session, task, state);
        return resumeExecution(tenant, session, cp.getCheckpointId(), Map.of(), model);
    }
    public View resumeExecution(String tenant, String session, String checkpointId, Map<String, Object> injectedSlots) {
        return resumeExecution(tenant, session, checkpointId, injectedSlots, null);
    }
    public View resumeExecution(String tenant, String session, String checkpointId, Map<String, Object> injectedSlots, RoutingModelConfig model) {
        if (injectedSlots != null && !injectedSlots.isEmpty()) throw new IllegalArgumentException("修改参数必须通过 OVERRIDE 审批接口");
        var cp = checkpoints.byId(tenant, session, checkpointId);
        if (!Set.of(REJECTED, COMPENSATION_REQUIRED, COMPENSATING, COMPENSATION_FAILED).contains(cp.getState().getStatus()))
            ReflectionCorrectionHandler.requireTenant(tenant, model);
        return checkpoints.locked(tenant, session, cp.getTaskId(), () -> advance(checkpoints.byId(tenant, session, checkpointId), model));
    }
    View advance(AgentExecutionCheckpoint cp) {
        return advance(cp, null);
    }
    View advance(AgentExecutionCheckpoint cp, RoutingModelConfig model) {
        DagExecutionState state = cp.getState();
        validateState(state);
        if (Set.of(COMPLETED, ROLLED_BACK).contains(state.getStatus())) {
            checkpoints.purgeCheckpoint(cp); return view(cp);
        }
        if (Set.of(REJECTED, COMPENSATION_REQUIRED, COMPENSATING, COMPENSATION_FAILED).contains(state.getStatus())) {
            saga.rollback(cp, Objects.toString(state.getAbortReason(), "RECOVER_COMPENSATION"), state.isUncertainForwardOutcome());
            return view(cp);
        }
        if (Set.of(COMPENSATION_UNCERTAIN, EXECUTION_UNCERTAIN, WAITING_APPROVAL).contains(state.getStatus()))
            return view(cp);
        var calls = state.getPrepared().calls();
        // The lease has been reacquired after process death; a write in flight has an unknown outcome.
        if (state.getInFlightStep() != null) {
            var call = calls.get(state.getInFlightStep());
            if (HitlRiskEvaluator.hasSideEffects(tools.getDefinition(call.tool()))) {
                saga.rollback(cp, "FORWARD_OUTCOME_UNKNOWN", true); return view(cp);
            }
            state.setInFlightStep(null);
        }
        while (state.getCurrentStepIndex() < calls.size()) {
            var call = calls.get(state.getCurrentStepIndex());
            Map<String, Object> params = new LinkedHashMap<>(state.getStepParameterOverrides()
                    .getOrDefault(state.getCurrentStepIndex(), call.parameters()));
            if (call.fobFromTask() != null) {
                Double fob = state.getFobResults().get(call.fobFromTask());
                if (fob == null) { fail(cp, "UPSTREAM_RESULT_MISSING", false); return view(cp); }
                params.put("fobTotalUsd", fob);
            }
            boolean approved = state.getStatus() == APPROVED && state.getApprovalId() != null;
            if (approved) params = new LinkedHashMap<>(state.getPendingParameters());
            var definition = tools.getDefinition(call.tool());
            if (definition == null) { fail(cp, "TOOL_NOT_REGISTERED", false); return view(cp); }
            try { params = new LinkedHashMap<>(HitlApprovalService.validateParameters(definition, params)); }
            catch (IllegalArgumentException e) {
                armApproval(cp, params, "当前参数未通过业务校验，请人工修改或驳回。"); return view(cp);
            }
            Optional<String> risk;
            try { risk = HitlRiskEvaluator.risk(definition, params); }
            catch (IllegalArgumentException e) {
                armApproval(cp, params, "当前风险参数无效，请人工修改或驳回。"); return view(cp);
            }
            if ((risk.isPresent() || state.getForcedApprovalSteps().contains(state.getCurrentStepIndex())) && !approved) {
                armApproval(cp, params, risk.orElse("自动修正改变了已审批参数，需要重新确认。")); return view(cp);
            }
            // Persist the execution fence BEFORE the handler; completion is another fenced write.
            state.setStatus(RUNNING); state.setInFlightStep(state.getCurrentStepIndex());
            state.getForcedApprovalSteps().remove(state.getCurrentStepIndex());
            checkpoints.update(cp);
            final Map<String, Object> input = Map.copyOf(params);
            state.getSlotVariablesPool().put("step_" + call.index() + ".parameters", input);
            state.getReactSteps().add(ReActStep.action(state.getReactSteps().size() + 1, call.tool(), definition.getName(), input));
            ToolExecutionTrace trace = approved
                    ? HitlRiskEvaluator.approvedCall(call.tool(), input, () -> tools.executeTool(call.tool(), input))
                    : tools.executeTool(call.tool(), input);
            Object output = trace.getOutputResult();
            if ("calculate_tier_price".equals(call.tool()) && output instanceof Map<?, ?> result
                    && result.get("fobTotalUsdRaw") instanceof Number number && number.doubleValue() > 0)
                state.getFobResults().put(call.index(), number.doubleValue());
            Map<String, String> sources = new LinkedHashMap<>(call.parameterSources());
            for (var correction : state.getCorrections()) if (correction.stepIndex() == state.getCurrentStepIndex()) {
                for (var evidence : correction.evidence()) if (Objects.equals(input.get(evidence.parameter()), evidence.value()))
                    sources.put(evidence.parameter(), "REFLECTION_BACKEND_EVIDENCE:" + evidence.sourceMessageId());
            }
            if (approved && "OVERRIDE".equals(state.getDecisionAction())) input.forEach((key, value) -> {
                if (!Objects.equals(value, state.getOriginalApprovalParameters().get(key))) sources.put(key, "HUMAN_OVERRIDE");
            });
            if (output != null) trace.setOutputResult(Map.of("result", output, "parameterSources", sources));
            state.getTraces().add(trace);
            state.getReactSteps().add(ReActStep.observation(state.getReactSteps().size() + 1, call.tool(), definition.getName(),
                    trace.getStatus(), trace.getOutputResult(), trace.getLatencyMs()));
            if (!"SUCCESS".equals(trace.getStatus())) {
                boolean knownNoEffect = !HitlRiskEvaluator.hasSideEffects(definition) || "NOT_APPLIED".equals(trace.getEffectOutcome());
                if (knownNoEffect) state.setInFlightStep(null);
                if ("RECOVERABLE".equals(trace.getErrorCategory()) && "NOT_APPLIED".equals(trace.getEffectOutcome())) {
                    // Persist reapproval before the model can persist changed parameters, including across a crash.
                    if (approved) state.getForcedApprovalSteps().add(state.getCurrentStepIndex());
                    state.setStatus(READY); checkpoints.update(cp);
                    var correction = reflection == null ? new ReflectionCorrectionHandler.Result(null, "请配置纠参模型或人工修改参数。")
                            : reflection.correct(cp, call, input, trace, model);
                    if (correction.corrected()) {
                        state.setApprovalId(null); state.setPendingParameters(new LinkedHashMap<>());
                        state.setStatus(READY); checkpoints.update(cp); continue;
                    }
                    armApproval(cp, input, correction.reviewReason()); return view(cp);
                }
                fail(cp, Objects.toString(trace.getErrorCode(), "TOOL_EXECUTION_FAILED"), !knownNoEffect);
                return view(cp);
            }
            String stepId = "step_" + call.index();
            var snapshot = new ExecutedStepSnapshot(); snapshot.setStepId(stepId); snapshot.setToolId(call.tool());
            snapshot.setOriginalParams(input); snapshot.setForwardResult(output);
            snapshot.setSideEffects(HitlRiskEvaluator.hasSideEffects(definition));
            snapshot.setCompensationSupported(tools.supportsCompensation(call.tool())); snapshot.setCompensationRetrySafe(tools.compensationRetrySafe(call.tool()));
            state.getExecutedSteps().add(snapshot);
            state.getCompletedStepIds().add(stepId);
            if (output != null) state.getSlotVariablesPool().put(stepId, output);
            state.setCurrentStepIndex(state.getCurrentStepIndex() + 1); state.setInFlightStep(null);
            state.setApprovalId(null); state.setPendingParameters(new LinkedHashMap<>());
            state.setRiskReason(null); state.setStatus(READY); state.setMessage(null); state.setApprovalRequestedAt(0);
            checkpoints.update(cp);
        }
        state.setStatus(COMPLETED); state.setFinishedAt(System.currentTimeMillis()); state.setMessage("全部步骤执行完成。");
        checkpoints.update(cp); checkpoints.purgeCheckpoint(cp);
        return view(cp);
    }
    void fail(AgentExecutionCheckpoint cp, String reason, boolean uncertain) {
        var state = cp.getState();
        boolean writes = state.getExecutedSteps().stream().anyMatch(ExecutedStepSnapshot::isSideEffects)
                || state.getPrepared().calls().subList(0, state.getCurrentStepIndex()).stream()
                    .anyMatch(call -> HitlRiskEvaluator.hasSideEffects(tools.getDefinition(call.tool())));
        if (writes) { saga.rollback(cp, reason, uncertain); return; }
        state.setStatus(uncertain ? EXECUTION_UNCERTAIN : FAILED); state.setUncertainForwardOutcome(uncertain);
        state.setMessage(uncertain ? "写操作结果不确定，禁止自动重试，请核查外部系统。" : "工具执行失败，可人工恢复当前步骤或终止任务。");
        checkpoints.update(cp);
    }
    View reject(AgentExecutionCheckpoint cp) {
        saga.rollback(cp, "HUMAN_REJECTED", false); return view(cp);
    }
    public View cancel(String tenant, String session, String checkpointId) {
        var existing = checkpoints.byId(tenant, session, checkpointId);
        return checkpoints.locked(tenant, session, existing.getTaskId(), () -> {
            var cp = checkpoints.byId(tenant, session, checkpointId); validateState(cp.getState());
            if (Set.of(COMPLETED, ROLLED_BACK).contains(cp.getState().getStatus())) throw conflict("任务已结束，不能再次终止");
            closeApprovalWait(cp.getState());
            boolean uncertain = cp.getState().isUncertainForwardOutcome();
            if (cp.getState().getInFlightStep() != null) uncertain |= HitlRiskEvaluator.hasSideEffects(tools.getDefinition(
                    cp.getState().getPrepared().calls().get(cp.getState().getInFlightStep()).tool()));
            saga.rollback(cp, "HUMAN_CANCELLED", uncertain); return view(cp);
        });
    }
    static void closeApprovalWait(DagExecutionState state) {
        if (state.getApprovalRequestedAt() > 0) {
            state.setApprovalWaitMs(state.getApprovalWaitMs() + Math.max(0, System.currentTimeMillis() - state.getApprovalRequestedAt()));
            state.setApprovalRequestedAt(0);
        }
    }
    private void armApproval(AgentExecutionCheckpoint cp, Map<String, Object> params, String reason) {
        var state = cp.getState(); closeApprovalWait(state);
        state.setStatus(WAITING_APPROVAL); state.setApprovalId("approval_" + UUID.randomUUID());
        state.setRiskReason(reason); state.setPendingParameters(new LinkedHashMap<>(params));
        state.setOriginalApprovalParameters(new LinkedHashMap<>(params)); state.setDecisionAction(null); state.setDecidedAt(0);
        state.setApprovalRequestedAt(System.currentTimeMillis()); state.setMessage("请审核当前参数，批准、修改或驳回任务。");
        checkpoints.update(cp);
    }
    public View view(AgentExecutionCheckpoint cp) {
        var state = cp.getState(); validateState(state); var calls = state.getPrepared().calls();
        long total = state.getTraces().stream().mapToLong(ToolExecutionTrace::getLatencyMs).sum();
        var telemetry = TurnTelemetry.builder().turnId(cp.getCheckpointId()).toolsCalledCount(state.getTraces().size())
                .totalLatencyMs(total).avgLatencyMs(state.getTraces().isEmpty() ? 0 : (double) total / state.getTraces().size())
                .traces(List.copyOf(state.getTraces())).build();
        var compensations = state.getExecutedSteps().stream().filter(ExecutedStepSnapshot::isSideEffects).map(s ->
                new CompensationProgress(s.getStepId(), s.getToolId(), s.getCompensationStatus().name(), s.getCompensationAttempts(),
                        s.getCompensationKey(), s.getCompensationErrorCode(), s.getCompensationLatencyMs())).toList();
        long compensationMs = compensations.stream().mapToLong(CompensationProgress::latencyMs).sum();
        long end = state.getFinishedAt() > 0 ? state.getFinishedAt()
                : Set.of(COMPLETED, ROLLED_BACK).contains(state.getStatus()) ? cp.getUpdatedAt() : System.currentTimeMillis();
        long waitingMs = state.getApprovalWaitMs() + (state.getApprovalRequestedAt() > 0 ? Math.max(0, end - state.getApprovalRequestedAt()) : 0);
        var timing = new Timing(Math.max(0, end - cp.getCreatedAt()), total, state.getReflectionModelMs(), compensationMs, waitingMs,
                "CHECKPOINT_DAG_ONLY_EXCLUDES_PLANNING_AND_BROWSER_GENERATION");
        return new View(cp.getCheckpointId(), cp.getVersion(), cp.getCreatedAt(), cp.getSessionId(), cp.getTaskId(), state.getStatus().name(), state.getCurrentStepIndex(),
                calls.size(), List.copyOf(state.getCompletedStepIds()), state.getApprovalId(),
                state.getCurrentStepIndex() < calls.size() ? calls.get(state.getCurrentStepIndex()).tool() : null,
                Map.copyOf(state.getPendingParameters()), state.getRiskReason(), state.getMessage(), cp.getUpdatedAt() + 86400000L,
                telemetry, List.copyOf(state.getReactSteps()), compensations, Map.copyOf(state.getReflectionAttempts()), timing);
    }
    private static void validateState(DagExecutionState state) {
        if (state == null || state.getPrepared() == null || !state.getPrepared().ready() || state.getStatus() == null)
            throw conflict("存档计划不完整，禁止恢复");
        var calls = state.getPrepared().calls(); int cursor = state.getCurrentStepIndex();
        if (cursor < 0 || cursor > calls.size() || state.getCompletedStepIds() == null
                || !state.getCompletedStepIds().equals(calls.subList(0, cursor).stream().map(c -> "step_" + c.index()).toList())
                || calls.stream().map(ValidatedPlanExecutor.Call::index).distinct().count() != calls.size())
            throw conflict("存档进度与已完成步骤不一致，禁止恢复");
        if (state.getInFlightStep() != null && (state.getInFlightStep() != cursor || cursor >= calls.size()))
            throw conflict("存档中的执行中步骤无效，禁止恢复");
        if (state.getStatus() == COMPLETED && cursor != calls.size())
            throw conflict("存档标记完成但步骤尚未完成，禁止恢复");
        if (Set.of(WAITING_APPROVAL, APPROVED).contains(state.getStatus())
                && (cursor >= calls.size() || state.getApprovalId() == null || state.getPendingParameters() == null))
            throw conflict("审批存档不完整，禁止恢复");
        if (state.getStatus() == APPROVED) {
            var decisions = state.getApprovalDecisions();
            if (decisions == null || decisions.isEmpty()) throw conflict("缺少已持久化的审批决定");
            var decision = decisions.get(decisions.size() - 1);
            if (!Objects.equals(decision.approvalId(), state.getApprovalId())
                    || !Set.of("APPROVE", "OVERRIDE").contains(decision.action())
                    || !decision.effectiveParameters().equals(state.getPendingParameters()))
                throw conflict("审批决定与当前参数不一致，禁止执行");
        }
    }
}
