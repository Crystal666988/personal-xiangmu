package com.tradeagent.core.dag;

import com.tradeagent.core.dag.model.*;
import com.tradeagent.modules.ai.tools.*;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import static com.tradeagent.core.dag.model.DagExecutionState.Status.*;
import static com.tradeagent.core.dag.ExecutionCheckpointService.conflict;

/** Approval decisions and edited parameters are durably committed before resuming a handler. */
@Service
public class HitlApprovalService {
    public enum Action { APPROVE, REJECT, OVERRIDE }
    private final ExecutionCheckpointService checkpoints;
    private final CheckpointedDagExecutor executor;
    private final ToolRegistry tools;
    public HitlApprovalService(ExecutionCheckpointService checkpoints, CheckpointedDagExecutor executor, ToolRegistry tools) {
        this.checkpoints = checkpoints; this.executor = executor; this.tools = tools;
    }
    public CheckpointedDagExecutor.View decide(String tenant, String session, String task, String approvalId,
                                               Action action, Map<String, Object> overrideParams) {
        return decide(tenant, session, task, approvalId, action, overrideParams, null);
    }
    public CheckpointedDagExecutor.View decide(String tenant, String session, String task, String approvalId,
                                               Action action, Map<String, Object> overrideParams, RoutingModelConfig model) {
        if (action != Action.REJECT) ReflectionCorrectionHandler.requireTenant(tenant, model);
        if (action == null) throw new IllegalArgumentException("缺少审批 action");
        if (action != Action.OVERRIDE && overrideParams != null && !overrideParams.isEmpty())
            throw new IllegalArgumentException("仅 OVERRIDE 可修改参数");
        return checkpoints.locked(tenant, session, task, () -> {
            AgentExecutionCheckpoint cp = checkpoints.getCheckpoint(tenant, session, task)
                    .orElseThrow(() -> conflict("审批不存在或已过期"));
            var state = cp.getState();
            if (!Objects.equals(approvalId, state.getApprovalId()) || state.getStatus() != WAITING_APPROVAL)
                throw conflict("该审批已处理、已过期或不属于当前步骤，请刷新执行状态");
            if (action == Action.OVERRIDE) {
                if (overrideParams == null || overrideParams.isEmpty()) throw new IllegalArgumentException("OVERRIDE 需要非空 overrideParams");
                var call = state.getPrepared().calls().get(state.getCurrentStepIndex());
                if (!state.getPendingParameters().keySet().containsAll(overrideParams.keySet()))
                    throw new IllegalArgumentException("禁止注入未出现在审批卡片中的参数");
                Set<String> changed = new HashSet<>();
                overrideParams.forEach((key, value) -> {
                    if (!Objects.equals(value, state.getPendingParameters().get(key))) changed.add(key);
                });
                if (call.fobFromTask() != null && changed.contains("fobTotalUsd"))
                    throw new IllegalArgumentException("上游推导的 FOB 结果不可改写");
                boolean hasDependent = state.getPrepared().calls().stream().anyMatch(c -> Objects.equals(c.fobFromTask(), call.index()));
                if (hasDependent && changed.stream().anyMatch(Set.of("quantity", "productModel", "wattage")::contains))
                    throw new IllegalArgumentException("改动上游产品或数量会影响下游依赖，请重新规划任务");
                Map<String, Object> merged = new LinkedHashMap<>(state.getPendingParameters()); merged.putAll(overrideParams);
                var definition = tools.getDefinition(call.tool());
                state.setPendingParameters(validateParameters(definition, merged));
                // Re-evaluate even edited risk values; malformed rates cannot be used to bypass policy.
                HitlRiskEvaluator.risk(definition, state.getPendingParameters());
                state.getHumanLockedParameters().computeIfAbsent(state.getCurrentStepIndex(), ignored -> new LinkedHashSet<>()).addAll(changed);
                state.getStepParameterOverrides().put(state.getCurrentStepIndex(), Map.copyOf(state.getPendingParameters()));
            }
            state.setDecisionAction(action.name()); state.setDecidedAt(System.currentTimeMillis());
            state.getApprovalDecisions().add(new DagExecutionState.ApprovalDecision(approvalId, action.name(), state.getDecidedAt(),
                    Map.copyOf(state.getOriginalApprovalParameters()), Map.copyOf(state.getPendingParameters())));
            CheckpointedDagExecutor.closeApprovalWait(state);
            if (action == Action.REJECT) {
                state.setStatus(REJECTED); state.setAbortReason("HUMAN_REJECTED");
                checkpoints.update(cp);
                return executor.reject(cp);
            }
            state.setStatus(APPROVED); checkpoints.update(cp);
            return executor.advance(cp, model);
        });
    }
    static Map<String, Object> validateParameters(ToolDefinition definition, Map<String, Object> params) {
        if (definition == null) throw conflict("工具已下线");
        if (TradeToolRules.ALLOWED.contains(definition.getId())) return TradeToolRules.prepare(definition.getId(), params).parameters();
        var schema = definition.getParameterSchema();
        if (schema == null || !schema.keySet().containsAll(params.keySet())) throw new IllegalArgumentException("工具参数 Schema 不完整");
        schema.forEach((key, spec) -> {
            if (!(spec instanceof Map<?, ?> field)) throw new IllegalArgumentException("不支持的参数 Schema");
            Object value = params.get(key);
            if (value == null) {
                if (Boolean.TRUE.equals(field.get("required")) || params.containsKey(key)) throw new IllegalArgumentException("参数不能为空: " + key);
                return;
            }
            boolean valid = switch (Objects.toString(field.get("type"))) {
                case "string" -> value instanceof String text && !text.isBlank() && text.length() <= 20000;
                case "number", "integer" -> value instanceof Number && finiteNumber(value, "integer".equals(field.get("type")));
                case "boolean" -> value instanceof Boolean;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("参数类型不合法: " + key);
        });
        return Map.copyOf(params);
    }
    private static boolean finiteNumber(Object value, boolean integer) {
        try { BigDecimal n = new BigDecimal(value.toString()); return !integer || n.stripTrailingZeros().scale() <= 0; }
        catch (NumberFormatException e) { return false; }
    }
}
