package com.tradeagent.core.dag.guardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.model.DagExecutionState;
import com.tradeagent.core.dag.model.ExecutedStepSnapshot;
import com.tradeagent.modules.ai.intent.ValidatedPlanExecutor;
import com.tradeagent.modules.ai.tools.ToolExecutionTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("任务五：AgentGuardrailEngine 防翻车工程护栏引擎单元测试")
class AgentGuardrailEngineTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AgentGuardrailEngine guardrail = new AgentGuardrailEngine(json, 6, 30L);

    @Test
    @DisplayName("计划级步数检测：步骤数 <= 6 时允许放行")
    void testPlanStepsAllowed() {
        List<ValidatedPlanExecutor.Call> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(new ValidatedPlanExecutor.Call(i, "tool_" + i, Map.of("key", "val"), Map.of(), null));
        }
        var prepared = new ValidatedPlanExecutor.Prepared(calls, List.of());
        var decision = guardrail.evaluatePlan(prepared);
        assertTrue(decision.allowed());
        assertEquals(AgentGuardrailEngine.ViolationType.NONE, decision.violationType());
    }

    @Test
    @DisplayName("计划级步数检测：步骤数 > 6 时触发 MAX_STEPS_EXCEEDED 硬熔断")
    void testPlanStepsExceeded() {
        List<ValidatedPlanExecutor.Call> calls = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            calls.add(new ValidatedPlanExecutor.Call(i, "tool_" + i, Map.of("key", "val"), Map.of(), null));
        }
        var prepared = new ValidatedPlanExecutor.Prepared(calls, List.of());
        var decision = guardrail.evaluatePlan(prepared);
        assertFalse(decision.allowed());
        assertEquals(AgentGuardrailEngine.ViolationType.MAX_STEPS_EXCEEDED, decision.violationType());
        assertTrue(decision.reason().contains("超过单轮允许最大步数上限"));
    }

    @Test
    @DisplayName("死循环检测：连续两步使用完全相同入参调用相同工具触发 LOOP_DETECTED")
    void testConsecutiveIdenticalCallsBlocked() {
        DagExecutionState state = new DagExecutionState();
        state.setCurrentStepIndex(1);

        ExecutedStepSnapshot step0 = new ExecutedStepSnapshot();
        step0.setStepId("step_0");
        step0.setToolId("calculate_tier_price");
        step0.setOriginalParams(Map.of("quantity", 1000, "productId", "P-101"));
        state.getExecutedSteps().add(step0);

        var nextCall = new ValidatedPlanExecutor.Call(1, "calculate_tier_price",
                Map.of("productId", "P-101", "quantity", 1000), Map.of(), null);

        var decision = guardrail.evaluateStepBeforeExecution(state, nextCall, nextCall.parameters());
        assertFalse(decision.allowed());
        assertEquals(AgentGuardrailEngine.ViolationType.LOOP_DETECTED, decision.violationType());
        assertTrue(decision.reason().contains("死循环震荡熔断保护"));
    }

    @Test
    @DisplayName("参数变化放行：相同工具但参数不同时不误杀")
    void testDifferentParametersAllowed() {
        DagExecutionState state = new DagExecutionState();
        state.setCurrentStepIndex(1);

        ExecutedStepSnapshot step0 = new ExecutedStepSnapshot();
        step0.setStepId("step_0");
        step0.setToolId("calculate_tier_price");
        step0.setOriginalParams(Map.of("quantity", 1000, "productId", "P-101"));
        state.getExecutedSteps().add(step0);

        var nextCall = new ValidatedPlanExecutor.Call(1, "calculate_tier_price",
                Map.of("productId", "P-101", "quantity", 2000), Map.of(), null);

        var decision = guardrail.evaluateStepBeforeExecution(state, nextCall, nextCall.parameters());
        assertTrue(decision.allowed());
        assertEquals(AgentGuardrailEngine.ViolationType.NONE, decision.violationType());
    }

    @Test
    @DisplayName("指纹规范化：不同键序入参 Map 生成完全相同的哈希指纹")
    void testCanonicalFingerprintConsistency() {
        Map<String, Object> map1 = new LinkedHashMap<>();
        map1.put("zebra", "Z");
        map1.put("alpha", "A");
        map1.put("beta", "B");

        Map<String, Object> map2 = new LinkedHashMap<>();
        map2.put("alpha", "A");
        map2.put("beta", "B");
        map2.put("zebra", "Z");

        String fp1 = guardrail.computeCallFingerprint("test_tool", map1);
        String fp2 = guardrail.computeCallFingerprint("test_tool", map2);
        assertEquals(fp1, fp2, "不同插入顺序的等价 Map 必须产出严格一致的指纹");
    }

    @Test
    @DisplayName("超时检测：单步耗时超过 30 秒触发 STEP_TIMEOUT_EXCEEDED")
    void testStepTimeoutExceeded() {
        DagExecutionState state = new DagExecutionState();
        var call = new ValidatedPlanExecutor.Call(0, "customs_data_lookup", Map.of(), Map.of(), null);

        ToolExecutionTrace trace = ToolExecutionTrace.builder()
                .toolId("customs_data_lookup")
                .latencyMs(35000L) // 35 seconds > 30s
                .status("SUCCESS")
                .build();

        var decision = guardrail.evaluateStepAfterExecution(state, call, trace);
        assertFalse(decision.allowed());
        assertEquals(AgentGuardrailEngine.ViolationType.STEP_TIMEOUT_EXCEEDED, decision.violationType());
        assertTrue(decision.reason().contains("超时阈值"));
    }
}
