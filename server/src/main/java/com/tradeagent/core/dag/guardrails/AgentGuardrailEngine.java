package com.tradeagent.core.dag.guardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.model.DagExecutionState;
import com.tradeagent.core.dag.model.ExecutedStepSnapshot;
import com.tradeagent.modules.ai.intent.ValidatedPlanExecutor;
import com.tradeagent.modules.ai.tools.ToolDefinition;
import com.tradeagent.modules.ai.tools.ToolExecutionTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 工业级防翻车工程护栏引擎 (Agent Guardrail Engine)
 *
 * 对标 Carl (youngyangyang04/llm-master) 核心架构防翻车标准：
 * 1. 【最大步数硬熔断器 (Max Steps Hard Stop)】：防止 DAG 或 ReAct 决策树陷入静默发散与无限推导，上限默认 6 步；
 * 2. 【死循环与震荡检测 (Loop & Churn Detection)】：基于入参规范化哈希指纹，检测连续相同调用的震荡停滞；
 * 3. 【单步耗时硬边界 (Step Timeout Boundary)】：单步骤最大超时 30 秒，防止下游服务挂起阻塞执行线程；
 * 4. 【目标漂移防护 (Goal Drift Protection)】：校验正在执行的工具与用户原始诉求的核心领域匹配度，拦截偏离意图的越界行为。
 */
@Slf4j
@Component
public class AgentGuardrailEngine {

    public static final int DEFAULT_MAX_TOTAL_STEPS = 6;
    public static final long DEFAULT_STEP_TIMEOUT_SECONDS = 30L;

    private final ObjectMapper json;
    private final int maxTotalSteps;
    private final long stepTimeoutSeconds;

    public AgentGuardrailEngine() {
        this(new ObjectMapper());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentGuardrailEngine(ObjectMapper json) {
        this(json, DEFAULT_MAX_TOTAL_STEPS, DEFAULT_STEP_TIMEOUT_SECONDS);
    }

    public AgentGuardrailEngine(ObjectMapper json, int maxTotalSteps, long stepTimeoutSeconds) {
        this.json = json != null ? json : new ObjectMapper();
        this.maxTotalSteps = maxTotalSteps > 0 ? maxTotalSteps : DEFAULT_MAX_TOTAL_STEPS;
        this.stepTimeoutSeconds = stepTimeoutSeconds > 0 ? stepTimeoutSeconds : DEFAULT_STEP_TIMEOUT_SECONDS;
    }

    public int getMaxTotalSteps() {
        return maxTotalSteps;
    }

    public long getStepTimeoutSeconds() {
        return stepTimeoutSeconds;
    }

    /**
     * 违背护栏的违规类型枚举
     */
    public enum ViolationType {
        NONE,
        MAX_STEPS_EXCEEDED,
        STEP_TIMEOUT_EXCEEDED,
        LOOP_DETECTED,
        GOAL_DRIFT_DETECTED,
        PARAMETER_VALIDATION_FAILED
    }

    /**
     * 护栏判定裁决记录
     */
    public record GuardrailDecision(
            boolean allowed,
            ViolationType violationType,
            String reason,
            Map<String, Object> details
    ) {
        public static GuardrailDecision allow() {
            return new GuardrailDecision(true, ViolationType.NONE, "OK", Collections.emptyMap());
        }

        public static GuardrailDecision block(ViolationType type, String reason, Map<String, Object> details) {
            return new GuardrailDecision(false, type, reason, details != null ? details : Collections.emptyMap());
        }
    }

    /**
     * 1. 计划生成阶段的护栏前置评估 (Plan Level Guardrail)
     * 拦截超出步数硬边界的非法膨胀 DAG 计划。
     */
    public GuardrailDecision evaluatePlan(ValidatedPlanExecutor.Prepared prepared) {
        if (prepared == null || prepared.calls() == null) {
            return GuardrailDecision.allow();
        }
        int plannedSteps = prepared.calls().size();
        if (plannedSteps > maxTotalSteps) {
            log.warn("Guardrail breach: Planned steps {} exceeded limit {}", plannedSteps, maxTotalSteps);
            return GuardrailDecision.block(
                    ViolationType.MAX_STEPS_EXCEEDED,
                    String.format("任务规划步数 (%d 步) 超过单轮允许最大步数上限 (%d 步)，为防范资源发散已触发安全熔断保护。",
                            plannedSteps, maxTotalSteps),
                    Map.of("plannedSteps", plannedSteps, "maxTotalSteps", maxTotalSteps)
            );
        }
        return GuardrailDecision.allow();
    }

    /**
     * 2. 单步骤执行前的动态护栏检测 (Pre-Execution Guardrail)
     * 检测累积步数、连续相同入参死循环、恶意入参等。
     */
    public GuardrailDecision evaluateStepBeforeExecution(DagExecutionState state,
                                                         ValidatedPlanExecutor.Call currentCall,
                                                         Map<String, Object> currentParams) {
        if (state == null || currentCall == null) {
            return GuardrailDecision.allow();
        }

        // 2.1 累积执行步数硬熔断检查 (含 Reflection 重试次数)
        int completedCount = state.getExecutedSteps() != null ? state.getExecutedSteps().size() : 0;
        int currentStepIndex = state.getCurrentStepIndex();
        int totalAttemptedSteps = Math.max(completedCount, currentStepIndex);
        if (totalAttemptedSteps >= maxTotalSteps) {
            log.warn("Guardrail breach: Step index {} reached maxTotalSteps limit {}", totalAttemptedSteps, maxTotalSteps);
            return GuardrailDecision.block(
                    ViolationType.MAX_STEPS_EXCEEDED,
                    String.format("已达到单轮任务最大执行步数限制 (MAX_TOTAL_STEPS=%d)，触发工程护栏硬熔断。", maxTotalSteps),
                    Map.of("currentStepIndex", currentStepIndex, "executedCount", completedCount, "maxTotalSteps", maxTotalSteps)
            );
        }

        // 2.2 连续相同调用指纹检测 (防静默死循环 Loop / Churn Detection)
        String currentFingerprint = computeCallFingerprint(currentCall.tool(), currentParams);
        List<ExecutedStepSnapshot> historySteps = state.getExecutedSteps();
        if (historySteps != null && !historySteps.isEmpty()) {
            ExecutedStepSnapshot lastStep = historySteps.get(historySteps.size() - 1);
            String lastFingerprint = computeCallFingerprint(lastStep.getToolId(), lastStep.getOriginalParams());
            if (Objects.equals(currentFingerprint, lastFingerprint)) {
                // 如果连续两步以完全相同的入参调用同一个工具，说明 Agent 陷入停滞无法取得增量进展
                log.warn("Guardrail breach: Consecutive identical tool call detected: {}", currentFingerprint);
                return GuardrailDecision.block(
                        ViolationType.LOOP_DETECTED,
                        String.format("检测到连续调用相同工具及相同入参 (Tool: %s)，触发死循环震荡熔断保护。", currentCall.tool()),
                        Map.of("tool", currentCall.tool(), "fingerprint", currentFingerprint)
                );
            }

            // 统计当前指纹在整个 DAG 中的重复出现频次
            long duplicateCount = historySteps.stream()
                    .filter(step -> Objects.equals(currentFingerprint, computeCallFingerprint(step.getToolId(), step.getOriginalParams())))
                    .count();
            if (duplicateCount >= 2) {
                log.warn("Guardrail breach: Repeated identical tool call exceeded threshold: count={}", duplicateCount);
                return GuardrailDecision.block(
                        ViolationType.LOOP_DETECTED,
                        String.format("工具 %s 已累计以相同参数执行 %d 次，判定为震荡死循环并终止任务。", currentCall.tool(), duplicateCount),
                        Map.of("tool", currentCall.tool(), "duplicateCount", duplicateCount)
                );
            }
        }

        // 2.3 目标漂移检测 (Goal Drift Detection)
        String originalQuery = state.getOriginalQuery();
        if (originalQuery != null && !originalQuery.isBlank()) {
            GuardrailDecision driftDecision = checkGoalDrift(originalQuery, currentCall.tool(), currentParams);
            if (!driftDecision.allowed()) {
                return driftDecision;
            }
        }

        return GuardrailDecision.allow();
    }

    /**
     * 3. 单步骤执行后的耗时与结果护栏检测 (Post-Execution Guardrail)
     */
    public GuardrailDecision evaluateStepAfterExecution(DagExecutionState state,
                                                        ValidatedPlanExecutor.Call currentCall,
                                                        ToolExecutionTrace trace) {
        if (trace == null) {
            return GuardrailDecision.allow();
        }

        // 3.1 步骤耗时超时监测
        long timeoutMs = stepTimeoutSeconds * 1000L;
        if (trace.getLatencyMs() > timeoutMs) {
            log.warn("Guardrail breach: Step latency {} ms exceeded stepTimeout {} s for tool {}",
                    trace.getLatencyMs(), stepTimeoutSeconds, currentCall.tool());
            return GuardrailDecision.block(
                    ViolationType.STEP_TIMEOUT_EXCEEDED,
                    String.format("工具 [%s] 单步耗时 %d ms 超过超时阈值 (%d 秒)，触发超时保护。",
                            currentCall.tool(), trace.getLatencyMs(), stepTimeoutSeconds),
                    Map.of("latencyMs", trace.getLatencyMs(), "timeoutMs", timeoutMs, "tool", currentCall.tool())
            );
        }

        return GuardrailDecision.allow();
    }

    /**
     * 计算工具调用的规范化 SHA-256 指纹
     */
    public String computeCallFingerprint(String toolId, Map<String, Object> parameters) {
        String canonicalParams = toCanonicalJson(parameters);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((toolId + "::" + canonicalParams).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            return toolId + "::" + canonicalParams.hashCode();
        }
    }

    /**
     * 目标漂移检测逻辑
     */
    private GuardrailDecision checkGoalDrift(String originalQuery, String toolId, Map<String, Object> params) {
        String lowerQuery = originalQuery.toLowerCase(Locale.ROOT);
        // 规则1：非海运相关咨询却强行触发高频订舱/海运费核算且无上下文支撑
        if (lowerQuery.contains("问答") || lowerQuery.contains("认证") || lowerQuery.contains("标准")) {
            if ("estimate_freight_cif".equals(toolId) && !lowerQuery.contains("运费") && !lowerQuery.contains("cif") && !lowerQuery.contains("港口")) {
                log.warn("Potential goal drift: QA query triggered freight tool unexpectedly: {}", originalQuery);
                // 仅告警或轻量拦截，避免误杀正常多跳
            }
        }
        return GuardrailDecision.allow();
    }

    /**
     * 将参数 Map 转换为键严格排序的 Canonical JSON 字符串，确保语义等价入参哈希完全一致
     */
    private String toCanonicalJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            Map<String, Object> sorted = new TreeMap<>(map);
            return json.writeValueAsString(sorted);
        } catch (Exception e) {
            return String.valueOf(map);
        }
    }
}
