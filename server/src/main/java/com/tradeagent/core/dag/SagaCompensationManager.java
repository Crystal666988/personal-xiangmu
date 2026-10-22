package com.tradeagent.core.dag;

import com.tradeagent.core.dag.exception.ToolExecutionException;
import com.tradeagent.core.dag.model.*;
import com.tradeagent.modules.ai.intent.RedisRoutingWorkflowStore;
import com.tradeagent.modules.ai.tools.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.tradeagent.core.dag.model.DagExecutionState.Status.*;
import com.tradeagent.core.dag.model.ExecutedStepSnapshot.CompensationStatus;

/** Called under the checkpoint execution lease. Stops at the first uncertain/failed undo operation. */
@Service
public class SagaCompensationManager {
    private final ExecutionCheckpointService checkpoints;
    private final ToolRegistry tools;
    private final SagaAuditStore audit;
    public SagaCompensationManager(ExecutionCheckpointService checkpoints, ToolRegistry tools, SagaAuditStore audit) {
        this.checkpoints = checkpoints; this.tools = tools; this.audit = audit;
    }
    public void rollback(AgentExecutionCheckpoint cp, String reason, boolean uncertainForward) {
        var state = cp.getState();
        state.setAbortReason(reason); state.setUncertainForwardOutcome(state.isUncertainForwardOutcome() || uncertainForward);
        CheckpointedDagExecutor.closeApprovalWait(state);
        state.setStatus(COMPENSATING); state.setApprovalId(null);
        ensureLegacySnapshots(cp);
        checkpoints.update(cp);
        List<ExecutedStepSnapshot> steps = state.getExecutedSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            var step = steps.get(i);
            if (!step.isSideEffects() || step.getCompensationStatus() == CompensationStatus.COMPLETED) continue;
            if (step.getCompensationKey() == null)
                step.setCompensationKey("saga_" + RedisRoutingWorkflowStore.hash(cp.getCheckpointId() + ":" + step.getStepId()));
            if (step.getCompensationStatus() == CompensationStatus.APPLIED) {
                if (!completeAudit(cp, step)) return;
                continue;
            }
            if (step.getCompensationStatus() == CompensationStatus.UNCERTAIN || (step.getCompensationStatus() == CompensationStatus.RUNNING
                    && !(step.isCompensationRetrySafe() && tools.compensationRetrySafe(step.getToolId())))) {
                step.setCompensationStatus(CompensationStatus.UNCERTAIN); state.setStatus(COMPENSATION_UNCERTAIN);
                state.setMessage("补偿操作结果尚未确认，已停止自动重放，请核查外部系统。");
                checkpoints.update(cp); return;
            }
            if (!step.isCompensationSupported() || !tools.supportsCompensation(step.getToolId())) {
                step.setCompensationStatus(CompensationStatus.FAILED); step.setCompensationErrorCode("COMPENSATION_UNSUPPORTED");
                state.setStatus(COMPENSATION_FAILED); state.setMessage("工具未实现可用的逆向补偿，执行现场已保留，请人工处理。");
                checkpoints.update(cp); return;
            }
            step.setCompensationAttempts(step.getCompensationAttempts() + 1);
            step.setLastCompensationLatencyMs(0);
            step.setCompensationStatus(CompensationStatus.RUNNING); step.setCompensationErrorCode(null);
            checkpoints.update(cp); // durable undo fence
            try { audit.record(cp, step, "STARTED"); }
            catch (RuntimeException e) {
                step.setCompensationStatus(CompensationStatus.FAILED); step.setCompensationErrorCode("AUDIT_UNAVAILABLE");
                state.setStatus(COMPENSATION_FAILED); state.setMessage("补偿审计不可用，未调用外部补偿；修复审计后可恢复。");
                checkpoints.update(cp); return;
            }
            long started = System.nanoTime();
            Object result;
            try {
                result = tools.compensate(step.getToolId(), step.getOriginalParams(), step.getForwardResult(), step.getCompensationKey());
            } catch (Exception e) {
                long cost = Math.max(1, (System.nanoTime() - started) / 1_000_000);
                step.setCompensationLatencyMs(step.getCompensationLatencyMs() + cost);
                step.setLastCompensationLatencyMs(cost);
                boolean noEffect = e instanceof ToolExecutionException typed
                        && typed.getEffectOutcome() == ToolExecutionException.EffectOutcome.NOT_APPLIED;
                boolean retrySafe = step.isCompensationRetrySafe() && tools.compensationRetrySafe(step.getToolId());
                step.setCompensationStatus(noEffect || retrySafe ? CompensationStatus.FAILED : CompensationStatus.UNCERTAIN);
                step.setCompensationErrorCode(e instanceof ToolExecutionException typed ? typed.getCode() : "COMPENSATION_CALL_FAILED");
                state.setStatus(noEffect || retrySafe ? COMPENSATION_FAILED : COMPENSATION_UNCERTAIN);
                state.setMessage(noEffect || retrySafe ? "补偿未完成，现场已保存；可使用同一幂等键恢复补偿。" : "补偿结果不确定，禁止自动重试，请核查外部系统。");
                state.getReactSteps().add(ReActStep.lifecycle(state.getReactSteps().size() + 1, ReActStep.StepType.COMPENSATION,
                        step.getToolId(), "FAILED", Map.of("stepId", step.getStepId(), "errorCode", step.getCompensationErrorCode()), cost));
                checkpoints.update(cp);
                try { audit.record(cp, step, "FAILED"); } catch (RuntimeException ignored) { /* checkpoint retains failure */ }
                return;
            }
            long cost = Math.max(1, (System.nanoTime() - started) / 1_000_000);
            step.setCompensationLatencyMs(step.getCompensationLatencyMs() + cost);
            step.setLastCompensationLatencyMs(cost);
            step.setCompensationResult(result); step.setCompensationStatus(CompensationStatus.APPLIED);
            state.getReactSteps().add(ReActStep.lifecycle(state.getReactSteps().size() + 1, ReActStep.StepType.COMPENSATION,
                    step.getToolId(), "SUCCESS", result, cost));
            // Store APPLIED first. Audit failure/restart must never cause a successful undo to run twice.
            checkpoints.update(cp);
            if (!completeAudit(cp, step)) return;
        }
        state.setStatus(state.isUncertainForwardOutcome() ? EXECUTION_UNCERTAIN : ROLLED_BACK);
        state.setMessage(state.isUncertainForwardOutcome() ? "已补偿此前确认成功的写操作；当前失败写操作的结果仍需人工核查。" : "所有已确认的写操作已按逆序补偿，任务已回滚。");
        if (!state.isUncertainForwardOutcome()) { state.setInFlightStep(null); state.setFinishedAt(System.currentTimeMillis()); }
        checkpoints.update(cp);
        if (state.getStatus() == ROLLED_BACK) checkpoints.purgeCheckpoint(cp);
    }
    private boolean completeAudit(AgentExecutionCheckpoint cp, ExecutedStepSnapshot step) {
        try { audit.record(cp, step, "SUCCEEDED"); }
        catch (RuntimeException e) {
            cp.getState().setStatus(COMPENSATION_FAILED); cp.getState().setMessage("外部补偿已成功，审计尚未写入；恢复时仅补写审计，不重复补偿。");
            checkpoints.update(cp); return false;
        }
        step.setCompensationStatus(CompensationStatus.COMPLETED); cp.getState().setStatus(COMPENSATING); checkpoints.update(cp); return true;
    }
    /** Upgrade old snapshots conservatively. Missing physical results prevent rollback instead of inventing an undo. */
    private void ensureLegacySnapshots(AgentExecutionCheckpoint cp) {
        var state = cp.getState();
        Set<String> present = new HashSet<>(); state.getExecutedSteps().forEach(s -> present.add(s.getStepId()));
        int traceCursor = 0;
        for (var call : state.getPrepared().calls().subList(0, state.getCurrentStepIndex())) {
            ToolExecutionTrace trace = null;
            while (traceCursor < state.getTraces().size()) {
                var candidate = state.getTraces().get(traceCursor++);
                if ("SUCCESS".equals(candidate.getStatus()) && call.tool().equals(candidate.getToolId())) { trace = candidate; break; }
            }
            String id = "step_" + call.index();
            if (present.contains(id)) continue;
            if (trace == null) throw ExecutionCheckpointService.conflict("旧存档缺少已完成步骤的物理结果，无法自动补偿");
            var step = new ExecutedStepSnapshot(); step.setStepId(id); step.setToolId(call.tool());
            step.setOriginalParams(Map.copyOf(trace.getQueryInput()));
            Object output = trace.getOutputResult();
            if (output instanceof Map<?, ?> wrapper && wrapper.containsKey("parameterSources") && wrapper.containsKey("result")) output = wrapper.get("result");
            step.setForwardResult(output); step.setSideEffects(HitlRiskEvaluator.hasSideEffects(tools.getDefinition(call.tool())));
            step.setCompensationSupported(tools.supportsCompensation(call.tool())); step.setCompensationRetrySafe(tools.compensationRetrySafe(call.tool()));
            state.getExecutedSteps().add(step);
        }
        Map<String, Integer> order = new HashMap<>(); for (int i = 0; i < state.getCompletedStepIds().size(); i++) order.put(state.getCompletedStepIds().get(i), i);
        state.getExecutedSteps().sort(Comparator.comparingInt(s -> order.getOrDefault(s.getStepId(), Integer.MAX_VALUE)));
    }
}
