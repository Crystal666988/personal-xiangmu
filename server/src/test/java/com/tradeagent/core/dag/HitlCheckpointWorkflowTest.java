package com.tradeagent.core.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.model.*;
import com.tradeagent.modules.ai.intent.ValidatedPlanExecutor;
import com.tradeagent.modules.ai.intent.*;
import com.tradeagent.modules.ai.chat.AgentMessage;
import com.tradeagent.modules.ai.tools.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static com.tradeagent.core.dag.model.DagExecutionState.Status.*;

/** JSON-persisted state is shared across fresh service instances; no network or model credentials. */
class HitlCheckpointWorkflowTest {
    final ObjectMapper json = new ObjectMapper();
    final Persisted database = new Persisted();
    final MemoryCheckpoints checkpoints = new MemoryCheckpoints(database);
    final AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
    final ToolRegistry registry = new ToolRegistry(List.of(handler("read", true, p -> { reads.incrementAndGet(); return Map.of("value", 42); }),
            handler("send_email", false, p -> { writes.incrementAndGet(); return Map.of("deliveryId", "delivery-1"); }),
            new TierPriceCalculatorTool(), new FreightCifEstimatorTool()));
    final CheckpointedDagExecutor executor = new CheckpointedDagExecutor(checkpoints, registry, json);
    final HitlApprovalService approvals = new HitlApprovalService(checkpoints, executor, registry);
    @AfterEach void close() { checkpoints.close(); }
    IToolHandler handler(String id, boolean readOnly, java.util.function.Function<Map<String, Object>, Object> run) {
        return new IToolHandler() {
            public ToolDefinition getDefinition() { return ToolDefinition.builder().id(id).name(id).domain("TEST").readOnly(readOnly)
                    .parameterSchema(id.equals("read") ? Map.of() : Map.of("recipient", Map.of("type", "string", "required", true))).build(); }
            public Object execute(Map<String, Object> params) { return run.apply(params); }
        };
    }
    ValidatedPlanExecutor.Call call(int index, String tool, Map<String, Object> params, Integer dependency) {
        return new ValidatedPlanExecutor.Call(index, tool, params, Map.of(), dependency);
    }
    CheckpointedDagExecutor.View start(ValidatedPlanExecutor.Call... calls) {
        return executor.start("tenant", "session", "task", "original user task", Map.of("tasks", "validated"),
                new ValidatedPlanExecutor.Prepared(List.of(calls), List.of()));
    }
    CheckpointedDagExecutor.View pause() {
        return start(call(0, "read", Map.of(), null), call(1, "send_email", Map.of("recipient", "buyer@example.test"), null));
    }
    CheckpointedDagExecutor.View decide(CheckpointedDagExecutor.View view, HitlApprovalService.Action action, Map<String, Object> params) {
        return approvals.decide("tenant", "session", "task", view.approvalId(), action, params);
    }
    @Test void dangerousToolNeverExecutesBeforeApprovalAndDirectRegistryEntryIsBlocked() {
        var waiting = pause();
        assertEquals("WAITING_APPROVAL", waiting.status()); assertEquals(1, reads.get()); assertEquals(0, writes.get());
        assertEquals(List.of("step_0"), waiting.completedStepIds());
        assertThrows(IllegalArgumentException.class, () -> registry.executeTool("send_email", waiting.parameters()));
        assertEquals(0, writes.get());
        assertEquals("WAITING_APPROVAL", executor.resumeExecution("tenant", "session", waiting.checkpointId(), Map.of()).status());
        assertEquals(0, writes.get());
    }
    @Test void approvalAfterServiceRecreationSkipsCompletedStepAndTerminalReplayNeverRepeatsWrites() {
        var waiting = pause();
        try (var fresh = new MemoryCheckpoints(database)) {
            var newExecutor = new CheckpointedDagExecutor(fresh, registry, new ObjectMapper());
            var service = new HitlApprovalService(fresh, newExecutor, registry);
            var done = service.decide("tenant", "session", "task", waiting.approvalId(), HitlApprovalService.Action.APPROVE, null);
            assertEquals("COMPLETED", done.status()); assertEquals(1, reads.get()); assertEquals(1, writes.get());
            assertTrue(fresh.active("tenant", "session").isEmpty());
            assertTrue(database.snapshots.isEmpty()); assertEquals(1, database.receipts.size());
            assertEquals("COMPLETED", newExecutor.resumeExecution("tenant", "session", done.checkpointId(), Map.of()).status());
            assertEquals(1, writes.get());
            assertThrows(ResponseStatusException.class, () -> service.decide("tenant", "session", "task", waiting.approvalId(), HitlApprovalService.Action.APPROVE, null));
        }
    }
    @Test void overrideRevalidatesAndFeedsRecomputedUpstreamValueToDependentStep() {
        var waiting = start(call(0, "read", Map.of(), null), call(1, "calculate_tier_price", Map.of("productModel", "TOPCon-580W",
                "quantity", 2000, "wattage", 580, "basePricePerWatt", 0.10), null),
                call(2, "estimate_freight_cif", Map.of("quantity", 2000, "originPort", "Ningbo", "destPort", "Hamburg"), 1));
        assertEquals("WAITING_APPROVAL", waiting.status());
        var edit = new LinkedHashMap<>(waiting.parameters()); edit.put("basePricePerWatt", 0.105);
        var done = decide(waiting, HitlApprovalService.Action.OVERRIDE, edit);
        assertEquals("COMPLETED", done.status()); assertEquals(1, reads.get());
        var state = checkpoints.getCheckpoint("tenant", "session", "task").orElseThrow().getState();
        assertEquals(state.getFobResults().get(1), state.getTraces().get(2).getQueryInput().get("fobTotalUsd"));
        assertEquals("OVERRIDE", state.getApprovalDecisions().get(0).action());
        assertEquals(0.10, state.getApprovalDecisions().get(0).originalParameters().get("basePricePerWatt"));
        assertTrue(state.getTraces().get(1).getOutputResult().toString().contains("HUMAN_OVERRIDE"));
    }
    @Test void overridesCannotInjectFieldsNullValuesOrInvalidTypes() {
        var waiting = pause();
        assertThrows(IllegalArgumentException.class, () -> decide(waiting, HitlApprovalService.Action.OVERRIDE, Map.of("approved", true)));
        assertThrows(IllegalArgumentException.class, () -> decide(waiting, HitlApprovalService.Action.OVERRIDE, Map.of("recipient", 7)));
        var nullValue = new HashMap<String, Object>(); nullValue.put("recipient", null);
        assertThrows(IllegalArgumentException.class, () -> decide(waiting, HitlApprovalService.Action.OVERRIDE, nullValue));
        assertEquals("WAITING_APPROVAL", checkpoints.getCheckpoint("tenant", "session", "task").orElseThrow().getState().getStatus().name());
        assertEquals(0, writes.get());
    }
    @Test void rejectStopsAllLaterStepsAndPurgesWithoutClaimingAnyExternalWriteWasCompensated() {
        var waiting = pause(); var result = decide(waiting, HitlApprovalService.Action.REJECT, null);
        assertEquals("ROLLED_BACK", result.status()); assertEquals(0, writes.get());
        assertEquals(ROLLED_BACK, checkpoints.getCheckpoint("tenant", "session", "task").orElseThrow().getState().getStatus());
        assertTrue(checkpoints.active("tenant", "session").isEmpty());
    }
    @Test void rejectingSecondWritePreservesUnsupportedCompensationAndDoesNotPretendToRollback() {
        var first = start(call(0, "send_email", Map.of("recipient", "one@example.test"), null),
                call(1, "send_email", Map.of("recipient", "two@example.test"), null));
        var second = decide(first, HitlApprovalService.Action.APPROVE, null);
        assertEquals("WAITING_APPROVAL", second.status()); assertEquals(1, writes.get());
        assertThrows(ResponseStatusException.class, () -> decide(first, HitlApprovalService.Action.APPROVE, null));
        assertEquals("COMPENSATION_FAILED", decide(second, HitlApprovalService.Action.REJECT, null).status());
        assertTrue(checkpoints.active("tenant", "session").isPresent()); assertEquals(1, writes.get());
    }
    @Test void persistedInFlightWriteAfterCrashBecomesUncertainAndIsNeverRetried() {
        var waiting = pause();
        checkpoints.locked("tenant", "session", "task", () -> {
            var cp = checkpoints.getCheckpoint("tenant", "session", "task").orElseThrow();
            cp.getState().setStatus(RUNNING); cp.getState().setInFlightStep(1); checkpoints.update(cp); return null;
        });
        var result = executor.resumeExecution("tenant", "session", waiting.checkpointId(), Map.of());
        assertEquals("EXECUTION_UNCERTAIN", result.status()); assertEquals(0, writes.get()); assertEquals(1, reads.get());
        executor.resumeExecution("tenant", "session", waiting.checkpointId(), Map.of()); assertEquals(0, writes.get());
    }
    @Test void failedReadCanResumeAndRetainsEarlierCompletedResults() {
        AtomicInteger attempts = new AtomicInteger();
        var failingTools = new ToolRegistry(List.of(handler("read", true, p -> { if (attempts.getAndIncrement() == 0) throw new IllegalStateException("temporary"); return 42; })));
        var runner = new CheckpointedDagExecutor(checkpoints, failingTools, json);
        var failed = runner.start("tenant", "session", "task", "query", "plan",
                new ValidatedPlanExecutor.Prepared(List.of(call(0, "read", Map.of(), null)), List.of()));
        assertEquals("FAILED", failed.status());
        assertEquals("COMPLETED", runner.resumeExecution("tenant", "session", failed.checkpointId(), Map.of()).status());
        assertEquals(2, attempts.get());
    }
    @Test void tenantMismatchStaleApprovalAndGenericSlotInjectionAreRejected() {
        var waiting = pause();
        assertTrue(checkpoints.getCheckpoint("other", "session", "task").isEmpty());
        assertThrows(ResponseStatusException.class, () -> executor.resumeExecution("other", "session", waiting.checkpointId(), Map.of()));
        assertThrows(ResponseStatusException.class, () -> approvals.decide("tenant", "session", "task", "wrong", HitlApprovalService.Action.APPROVE, null));
        assertThrows(IllegalArgumentException.class, () -> executor.resumeExecution("tenant", "session", waiting.checkpointId(), Map.of("recipient", "attacker")));
        assertThrows(IllegalArgumentException.class, () -> decide(waiting, HitlApprovalService.Action.APPROVE, Map.of("recipient", "changed")));
        assertEquals(0, writes.get());
    }
    @Test void concurrentDecisionsOnlyOneHandlerInvocation() throws Exception {
        var waiting = pause(); var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        try {
            Callable<String> submit = () -> { barrier.await(); try { return decide(waiting, HitlApprovalService.Action.APPROVE, null).status(); }
                catch (ResponseStatusException e) { return "CONFLICT"; } };
            var results = pool.invokeAll(List.of(submit, submit));
            assertEquals(Set.of("COMPLETED", "CONFLICT"), Set.of(results.get(0).get(), results.get(1).get()));
            assertEquals(1, writes.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void serverRiskPolicyFailsClosedAndHonorsDiscountBoundary() {
        var safe = registry.getDefinition("read");
        assertTrue(HitlRiskEvaluator.risk(safe, Map.of("discountRate", 0.099)).isEmpty());
        assertTrue(HitlRiskEvaluator.risk(safe, Map.of("discountRate", 0.10)).isPresent());
        assertThrows(IllegalArgumentException.class, () -> HitlRiskEvaluator.risk(safe, Map.of("discountRate", Double.NaN)));
        assertTrue(HitlRiskEvaluator.risk(ToolDefinition.builder().id("unknown").build(), Map.of()).isPresent());
        var price = registry.getDefinition("calculate_tier_price");
        assertTrue(HitlRiskEvaluator.risk(price, Map.of("basePricePerWatt", 0.118, "quantity", 2000)).isEmpty());
        assertTrue(HitlRiskEvaluator.risk(price, Map.of("basePricePerWatt", 0.11, "quantity", 2000)).isPresent());
        assertTrue(HitlRiskEvaluator.risk(price, Map.of("basePricePerWatt", 0.11, "quantity", 1)).isEmpty());
    }

    @Test void realChatWorkflowSurfacesApprovalRefreshesReceiptAndAllowsNextTopicAfterCompletion() throws Exception {
        var outputs = new ArrayDeque<String>();
        var plan = new com.tradeagent.core.dag.EvidenceRoutingPlan(
                List.of(new com.tradeagent.core.dag.EvidenceRoutingPlan.Task("calculate_tier_price", Map.of("productModel", "m", "quantity", "q", "basePricePerWatt", "p"))),
                List.of(new com.tradeagent.core.dag.EvidenceRoutingPlan.SlotCandidate("m", com.tradeagent.core.dag.EvidenceRoutingPlan.SlotType.MODEL, "TOPCon580W", null, null, "task"),
                        new com.tradeagent.core.dag.EvidenceRoutingPlan.SlotCandidate("q", com.tradeagent.core.dag.EvidenceRoutingPlan.SlotType.QUANTITY, "2000片", null, null, "task"),
                        new com.tradeagent.core.dag.EvidenceRoutingPlan.SlotCandidate("p", com.tradeagent.core.dag.EvidenceRoutingPlan.SlotType.MONEY, "USD 0.10/W", null, null, "task")));
        outputs.add(json.writeValueAsString(plan)); outputs.add("{\"tasks\":[],\"slotCandidates\":[]}");
        var planner = new RoutingPreviewService(new RoutingPlanGenerator() {
            public boolean isAvailable(RoutingModelConfig config) { return true; }
            public String generate(String instructions, String source, RoutingModelConfig config) { return outputs.removeFirst(); }
        }, new EvidenceToolCatalog(registry), new StrictEvidencePlanReader(), json);
        var workflow = new RoutingWorkflowService(planner, new ValidatedPlanExecutor(registry), new MemoryWorkflow(), executor, checkpoints);
        var message = AgentMessage.builder().id("task").sessionId("session").role("user").content("TOPCon580W 2000片，基准价 USD 0.10/W，请报价").build();
        var waiting = workflow.run("tenant", "session", message, List.of(message), null, "continue");
        assertEquals("WAITING_APPROVAL", waiting.routingStatus()); assertNotNull(waiting.execution());
        assertEquals("COMPLETED", decide(waiting.execution(), HitlApprovalService.Action.APPROVE, null).status());
        var replay = workflow.run("tenant", "session", message, List.of(message), null, "continue");
        assertEquals("EXECUTED", replay.routingStatus()); assertEquals(1, replay.telemetry().getToolsCalledCount());
        var next = AgentMessage.builder().id("next").sessionId("session").role("user").content("你好").build();
        assertEquals("GENERAL", workflow.run("tenant", "session", next, List.of(message, next), null, "continue").routingStatus());
        assertTrue(outputs.isEmpty());
    }
    @Test void springCanWireCheckpointResumeCoordinatorWithoutCircularBeanCreation() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(ExecutionCheckpointService.class, () -> checkpoints);
            context.registerBean(ToolRegistry.class, () -> registry);
            context.registerBean(ObjectMapper.class, () -> json);
            context.registerBean(SagaCompensationManager.class, () -> new SagaCompensationManager(checkpoints, registry, (cp, step, event) -> {}));
            context.registerBean(ReflectionCorrectionHandler.class, () -> new ReflectionCorrectionHandler(checkpoints, registry, null, json));
            context.registerBean(CheckpointedDagExecutor.class);
            context.registerBean(HitlApprovalService.class);
            context.refresh();
            var waiting = pause();
            assertEquals("WAITING_APPROVAL", context.getBean(ExecutionCheckpointService.class)
                    .resumeExecution("tenant", "session", waiting.checkpointId(), Map.of()).status());
            var done = context.getBean(HitlApprovalService.class).decide("tenant", "session", "task", waiting.approvalId(),
                    HitlApprovalService.Action.APPROVE, null);
            assertEquals("COMPLETED", done.status()); assertEquals(1, writes.get());
        }
    }
    static class MemoryWorkflow implements RoutingWorkflowStore {
        State state; final Map<String, RoutingWorkflowService.Result> results = new HashMap<>();
        public String acquire(String tenant, String session) { return "lock"; }
        public void release(String tenant, String session, String token) {}
        public State read(String tenant, String session) { return state; }
        public RoutingWorkflowService.Result receipt(String tenant, String session, String message) { return results.get(message); }
        public boolean save(String tenant, String session, String token, State state) { this.state = state; return true; }
        public boolean complete(String tenant, String session, String token, String message, RoutingWorkflowService.Result result, State state) {
            this.state = state; results.put(message, result); return true;
        }
    }

    static class Persisted {
        final Map<String, String> snapshots = new ConcurrentHashMap<>(), receipts = new ConcurrentHashMap<>();
        final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    }
    /** Test adapter for storage only; coordinator, schema rules, real tools, risk gate and JSON codec are real. */
    static class MemoryCheckpoints extends ExecutionCheckpointService implements AutoCloseable {
        final Persisted db; final ObjectMapper codec = new ObjectMapper();
        MemoryCheckpoints(Persisted db) { super(null, new ObjectMapper()); this.db = db; }
        String key(String t, String s, String task) { return t + ":" + s + ":" + task; }
        String key(AgentExecutionCheckpoint cp) { return key(cp.getTenantId(), cp.getSessionId(), cp.getTaskId()); }
        String encode(AgentExecutionCheckpoint cp) { try { return codec.writeValueAsString(cp); } catch (Exception e) { throw new AssertionError(e); } }
        AgentExecutionCheckpoint decode(String raw) { try { return codec.readValue(raw, AgentExecutionCheckpoint.class); } catch (Exception e) { throw new AssertionError(e); } }
        @Override public synchronized AgentExecutionCheckpoint saveCheckpoint(String t, String s, String task, DagExecutionState state) {
            var existing = getCheckpoint(t, s, task); if (existing.isPresent()) return existing.get();
            if (active(t, s).isPresent()) throw conflict("active");
            var cp = new AgentExecutionCheckpoint(); cp.setCheckpointId("cp_" + UUID.randomUUID());
            cp.setTenantId(t); cp.setSessionId(s); cp.setTaskId(task); cp.setCreatedAt(System.currentTimeMillis());
            cp.setUpdatedAt(cp.getCreatedAt()); cp.setState(state); db.snapshots.put(key(cp), encode(cp)); return decode(encode(cp));
        }
        @Override public Optional<AgentExecutionCheckpoint> getCheckpoint(String t, String s, String task) {
            String value = db.snapshots.getOrDefault(key(t,s,task), db.receipts.get(key(t,s,task)));
            return Optional.ofNullable(value == null ? null : decode(value));
        }
        @Override public Optional<AgentExecutionCheckpoint> active(String t, String s) {
            return db.snapshots.values().stream().map(this::decode).filter(cp -> cp.getTenantId().equals(t) && cp.getSessionId().equals(s)).findFirst();
        }
        @Override public AgentExecutionCheckpoint byId(String t, String s, String id) {
            return java.util.stream.Stream.concat(db.snapshots.values().stream(), db.receipts.values().stream()).map(this::decode)
                    .filter(cp -> cp.getTenantId().equals(t) && cp.getSessionId().equals(s) && cp.getCheckpointId().equals(id))
                    .findFirst().orElseThrow(() -> conflict("missing"));
        }
        @Override public void update(AgentExecutionCheckpoint cp) {
            var old = db.snapshots.get(key(cp));
            if (old == null || decode(old).getVersion() != cp.getVersion()) throw conflict("stale");
            cp.setVersion(cp.getVersion() + 1); cp.setUpdatedAt(System.currentTimeMillis()); db.snapshots.put(key(cp), encode(cp));
        }
        @Override public void purgeCheckpoint(AgentExecutionCheckpoint cp) {
            if (!Set.of(COMPLETED, ROLLED_BACK).contains(cp.getState().getStatus())) throw conflict("not terminal");
            db.snapshots.remove(key(cp)); db.receipts.put(key(cp), encode(cp));
        }
        @Override public <T> T locked(String t, String s, String task, Supplier<T> action) {
            ReentrantLock lock = db.locks.computeIfAbsent(key(t,s,task), ignored -> new ReentrantLock());
            if (!lock.tryLock()) throw conflict("busy");
            try { return action.get(); } finally { lock.unlock(); }
        }
    }
}
