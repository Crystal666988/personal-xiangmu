package com.tradeagent.core.dag.guardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.CheckpointedDagExecutor;
import com.tradeagent.core.dag.ExecutionCheckpointService;
import com.tradeagent.core.dag.SagaCompensationManager;
import com.tradeagent.core.dag.model.AgentExecutionCheckpoint;
import com.tradeagent.core.dag.model.DagExecutionState;
import com.tradeagent.modules.ai.intent.ValidatedPlanExecutor;
import com.tradeagent.modules.ai.tools.IToolHandler;
import com.tradeagent.modules.ai.tools.ToolDefinition;
import com.tradeagent.modules.ai.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("任务五：CheckpointedDagExecutor 与 AgentGuardrailEngine 集成测试")
class CheckpointedDagExecutorGuardrailsIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Persisted database = new Persisted();
    private final MemoryCheckpoints checkpoints = new MemoryCheckpoints(database);
    private final AtomicInteger toolExecutions = new AtomicInteger();

    private final ToolRegistry registry = new ToolRegistry(List.of(
            handler("fetch_data", true, p -> {
                toolExecutions.incrementAndGet();
                return Map.of("count", 100);
            }),
            handler("process_data", true, p -> {
                toolExecutions.incrementAndGet();
                return Map.of("processed", true);
            })
    ));

    private final AgentGuardrailEngine guardrail = new AgentGuardrailEngine(json, 4, 30L); // 限制最大 4 步
    private final CheckpointedDagExecutor executor = new CheckpointedDagExecutor(
            checkpoints, registry, json,
            new SagaCompensationManager(checkpoints, registry, (cp, step, event) -> {}),
            null,
            guardrail
    );

    @AfterEach
    void tearDown() {
        checkpoints.close();
    }

    @Test
    @DisplayName("前置拦截：规划步数超过最大限制 (4步) 时 start 抛出 IllegalArgumentException")
    void testPlanExceededThrows() {
        List<ValidatedPlanExecutor.Call> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) { // 5 > 4
            calls.add(new ValidatedPlanExecutor.Call(i, "fetch_data", Map.of("p", i), Map.of(), null));
        }
        var prepared = new ValidatedPlanExecutor.Prepared(calls, List.of());

        var ex = assertThrows(IllegalArgumentException.class, () ->
                executor.start("1001", "session_1", "task_1", "测试超长任务", Map.of(), prepared)
        );
        assertTrue(ex.getMessage().contains("超过单轮允许最大步数上限"));
    }

    @Test
    @DisplayName("正常执行：合规计划 (2步) 顺利通过护栏并完成执行")
    void testCompliantPlanExecutesSuccessfully() {
        List<ValidatedPlanExecutor.Call> calls = List.of(
                new ValidatedPlanExecutor.Call(0, "fetch_data", Map.of("id", 1), Map.of(), null),
                new ValidatedPlanExecutor.Call(1, "process_data", Map.of("id", 2), Map.of(), null)
        );
        var prepared = new ValidatedPlanExecutor.Prepared(calls, List.of());

        var view = executor.start("1001", "session_2", "task_2", "查询并处理数据", Map.of(), prepared);
        assertNotNull(view);
        assertEquals("COMPLETED", view.status());
        assertEquals(2, view.completedStepIds().size());
        assertEquals(2, toolExecutions.get());
    }

    @Test
    @DisplayName("死循环熔断：连续调用同一工具相同参数触发 LOOP_DETECTED 熔断")
    void testLoopDetectionTripsBreaker() {
        List<ValidatedPlanExecutor.Call> calls = List.of(
                new ValidatedPlanExecutor.Call(0, "fetch_data", Map.of("id", 999), Map.of(), null),
                new ValidatedPlanExecutor.Call(1, "fetch_data", Map.of("id", 999), Map.of(), null)
        );
        var prepared = new ValidatedPlanExecutor.Prepared(calls, List.of());

        var view = executor.start("1001", "session_3", "task_3", "重复调用", Map.of(), prepared);
        assertNotNull(view);
        assertEquals("FAILED", view.status());
        assertTrue(view.message().contains("死循环震荡熔断保护") || view.message().contains("LOOP_DETECTED"));
        assertEquals(1, view.completedStepIds().size());
    }

    private IToolHandler handler(String id, boolean readOnly, java.util.function.Function<Map<String, Object>, Object> run) {
        return new IToolHandler() {
            public ToolDefinition getDefinition() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("id", Map.of("type", "integer", "required", false));
                schema.put("p", Map.of("type", "integer", "required", false));
                return ToolDefinition.builder()
                        .id(id)
                        .name(id)
                        .domain("TEST")
                        .readOnly(readOnly)
                        .parameterSchema(schema)
                        .build();
            }
            public Object execute(Map<String, Object> params) {
                return run.apply(params);
            }
        };
    }

    static class Persisted {
        final Map<String, AgentExecutionCheckpoint> map = new ConcurrentHashMap<>();
    }

    static class MemoryCheckpoints extends ExecutionCheckpointService {
        private final Persisted db;
        MemoryCheckpoints(Persisted db) {
            super(null, new ObjectMapper());
            this.db = db;
        }
        @Override
        public AgentExecutionCheckpoint saveCheckpoint(String tenant, String session, String task, DagExecutionState state) {
            String cpId = "cp_" + UUID.randomUUID();
            AgentExecutionCheckpoint cp = new AgentExecutionCheckpoint();
            cp.setCheckpointId(cpId);
            cp.setTenantId(tenant);
            cp.setSessionId(session);
            cp.setTaskId(task);
            cp.setVersion(1);
            cp.setCreatedAt(System.currentTimeMillis());
            cp.setUpdatedAt(System.currentTimeMillis());
            cp.setState(state);
            db.map.put(tenant + ":" + session + ":" + cpId, cp);
            return cp;
        }
        @Override
        public AgentExecutionCheckpoint byId(String tenant, String session, String checkpointId) {
            var cp = db.map.get(tenant + ":" + session + ":" + checkpointId);
            if (cp == null) throw new NoSuchElementException("Not found");
            return cp;
        }
        @Override
        public void update(AgentExecutionCheckpoint checkpoint) {
            checkpoint.setVersion(checkpoint.getVersion() + 1);
            checkpoint.setUpdatedAt(System.currentTimeMillis());
            db.map.put(checkpoint.getTenantId() + ":" + checkpoint.getSessionId() + ":" + checkpoint.getCheckpointId(), checkpoint);
        }
        @Override
        public void purgeCheckpoint(AgentExecutionCheckpoint cp) {
            db.map.remove(cp.getTenantId() + ":" + cp.getSessionId() + ":" + cp.getCheckpointId());
        }
        @Override
        public <T> T locked(String tenant, String session, String task, java.util.function.Supplier<T> action) {
            return action.get();
        }
        public void close() {}
    }
}
