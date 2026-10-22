package com.tradeagent.core.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.exception.*;
import com.tradeagent.core.dag.model.*;
import com.tradeagent.modules.ai.intent.*;
import com.tradeagent.modules.ai.tools.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Fault-injection fixtures, not production write tools. Gemini should run this suite with the full regression. */
class SagaReflectionWorkflowTest {
    static final RoutingModelConfig MODEL = new RoutingModelConfig("test-secret", "https://unused.test", "model", 1001L, "openai");
    static class Tool implements IToolHandler {
        final String id; final boolean write;
        boolean supports = true, retrySafe;
        int calls, undos;
        final List<String> keys = new ArrayList<>();
        Function<Map<String, Object>, Object> run = params -> Map.of("resourceId", UUID.randomUUID().toString());
        Function<String, Object> undo = key -> Map.of("undone", true);
        List<ToolCorrectionCandidate> candidates = List.of();
        Tool(String id, boolean write) { this.id = id; this.write = write; }
        public ToolDefinition getDefinition() { return ToolDefinition.builder().id(id).name(id).domain("TEST").readOnly(!write)
                .parameterSchema(Map.of("port", Map.of("type", "string", "required", false), "ref", Map.of("type", "string", "required", false))).build(); }
        public Object execute(Map<String, Object> params) { calls++; return run.apply(params); }
        public boolean supportsCompensation() { return supports; }
        public boolean compensationRetrySafe() { return retrySafe; }
        public Object compensate(Map<String, Object> params, Object result, String key) {
            assertNotNull(result); undos++; keys.add(key); return undo.apply(key);
        }
        public List<ToolCorrectionCandidate> correctionCandidates(Map<String, Object> params, Map<String, String> sources) { return candidates; }
    }
    static class Generator implements RoutingPlanGenerator {
        final Deque<String> responses = new ArrayDeque<>(); int calls;
        public boolean isAvailable(RoutingModelConfig config) { return config != null; }
        public String generate(String instructions, String source, RoutingModelConfig config) {
            calls++; return responses.isEmpty() ? "{\"candidateIds\":[]}" : responses.removeFirst();
        }
    }
    static class Harness implements AutoCloseable {
        final HitlCheckpointWorkflowTest.Persisted db;
        final HitlCheckpointWorkflowTest.MemoryCheckpoints cp;
        final ToolRegistry tools;
        final Generator generator;
        final CheckpointedDagExecutor runner;
        final HitlApprovalService approvals;
        Harness(List<Tool> handlers, SagaAuditStore audit, Generator generator) { this(new HitlCheckpointWorkflowTest.Persisted(), handlers, audit, generator); }
        Harness(HitlCheckpointWorkflowTest.Persisted db, List<Tool> handlers, SagaAuditStore audit, Generator generator) {
            this.db = db; this.generator = generator; cp = new HitlCheckpointWorkflowTest.MemoryCheckpoints(db);
            tools = new ToolRegistry(new ArrayList<IToolHandler>(handlers)); var json = new ObjectMapper();
            var saga = new SagaCompensationManager(cp, tools, audit);
            runner = new CheckpointedDagExecutor(cp, tools, json, saga, new ReflectionCorrectionHandler(cp, tools, generator, json));
            approvals = new HitlApprovalService(cp, runner, tools);
        }
        CheckpointedDagExecutor.View start(ValidatedPlanExecutor.Call... calls) {
            return runner.start("1001", "session", "task", "发往鹿特丹，走40高柜", "validated-plan",
                    new ValidatedPlanExecutor.Prepared(List.of(calls), List.of()), List.of(), MODEL);
        }
        CheckpointedDagExecutor.View decide(CheckpointedDagExecutor.View view, HitlApprovalService.Action action) {
            return approvals.decide("1001", "session", "task", view.approvalId(), action, null, MODEL);
        }
        CheckpointedDagExecutor.View resume(String id) { return runner.resumeExecution("1001", "session", id, Map.of(), MODEL); }
        public void close() { cp.close(); }
    }
    static ValidatedPlanExecutor.Call call(int index, String tool) { return call(index, tool, Map.of("ref", tool)); }
    static ValidatedPlanExecutor.Call call(int index, String tool, Map<String, Object> params) {
        return new ValidatedPlanExecutor.Call(index, tool, params, Map.of(), null);
    }
    static Tool fatalRead() {
        Tool fail = new Tool("fatal_read", false);
        fail.run = p -> { throw new ToolExecutionException("REMOTE_UNAVAILABLE", "远端不可用", ToolExecutionException.Category.FATAL, ToolExecutionException.EffectOutcome.NOT_APPLIED); };
        return fail;
    }
    static Tool normalizer(boolean write) {
        Tool tool = new Tool("normalize", write);
        tool.run = p -> {
            if (!"NLRTM".equals(p.get("port"))) throw new RecoverableToolException("PORT_FORMAT", "需要登记的港口代码");
            return Map.of("port", p.get("port"));
        };
        tool.candidates = List.of(new ToolCorrectionCandidate("port", "NLRTM", "$original", "鹿特丹")); return tool;
    }
    @Test void fatalDownstreamFailureUndoesConfirmedWritesInReverseOrderAndPurgesOnlyAfterSuccess() {
        var order = new ArrayList<String>(); var events = new ArrayList<String>();
        Tool stock = new Tool("stock", true), draft = new Tool("draft", true), fail = fatalRead();
        stock.undo = key -> { order.add("stock"); return "released"; }; draft.undo = key -> { order.add("draft"); return "cancelled"; };
        try (var h = new Harness(List.of(stock, draft, fail), (cp,s,e) -> events.add(s.getToolId() + ":" + e), new Generator())) {
            var first = h.start(call(0,"stock"), call(1,"draft"), call(2,"fatal_read"));
            var second = h.decide(first, HitlApprovalService.Action.APPROVE);
            var done = h.decide(second, HitlApprovalService.Action.APPROVE);
            assertEquals("ROLLED_BACK", done.status()); assertEquals(List.of("draft", "stock"), order);
            assertEquals(List.of("draft:STARTED", "draft:SUCCEEDED", "stock:STARTED", "stock:SUCCEEDED"), events);
            assertTrue(h.db.snapshots.isEmpty()); assertEquals(2, done.compensations().size());
            assertEquals(2, done.reactSteps().stream().filter(s -> s.getType() == ReActStep.StepType.COMPENSATION).count());
            assertEquals("ROLLED_BACK", h.resume(done.checkpointId()).status()); assertEquals(1, stock.undos); assertEquals(1, draft.undos);
        }
    }
    @Test void rejectionInvokesSagaWithoutExecutingPendingWrite() {
        Tool stock = new Tool("stock", true), draft = new Tool("draft", true);
        try (var h = new Harness(List.of(stock,draft), (cp,s,e) -> {}, new Generator())) {
            var pending = h.decide(h.start(call(0,"stock"),call(1,"draft")), HitlApprovalService.Action.APPROVE);
            assertEquals("ROLLED_BACK", h.decide(pending, HitlApprovalService.Action.REJECT).status());
            assertEquals(1,stock.undos); assertEquals(0,draft.calls);
        }
    }
    @Test void uncertainForwardFailureCompensatesKnownSuccessesButNeverClaimsFullRollbackOrRetriesWrite() {
        Tool stock = new Tool("stock",true), timeout = new Tool("booking",true);
        timeout.run = p -> { throw new IllegalStateException("timeout after outbound request"); };
        try (var h = new Harness(List.of(stock,timeout), (cp,s,e) -> {}, new Generator())) {
            var pending = h.decide(h.start(call(0,"stock"),call(1,"booking")), HitlApprovalService.Action.APPROVE);
            var result = h.decide(pending, HitlApprovalService.Action.APPROVE);
            assertEquals("EXECUTION_UNCERTAIN",result.status()); assertEquals(1,stock.undos);
            assertEquals("EXECUTION_UNCERTAIN",h.resume(result.checkpointId()).status()); assertEquals(1,timeout.calls); assertFalse(h.db.snapshots.isEmpty());
        }
    }
    @Test void auditFailureAfterSuccessfulUndoOnlyReplaysAuditAfterRestart() {
        Tool stock = new Tool("stock",true), fail = fatalRead(); AtomicInteger succeeded = new AtomicInteger();
        SagaAuditStore audit = (cp,s,e) -> { if (e.equals("SUCCEEDED") && succeeded.getAndIncrement() == 0) throw new IllegalStateException("db offline"); };
        try (var h = new Harness(List.of(stock,fail),audit,new Generator())) {
            var failed = h.decide(h.start(call(0,"stock"),call(1,"fatal_read")),HitlApprovalService.Action.APPROVE);
            assertEquals("COMPENSATION_FAILED",failed.status()); assertEquals(1,stock.undos);
            try (var fresh = new Harness(h.db,List.of(stock,fail),audit,new Generator())) {
                assertEquals("ROLLED_BACK",fresh.resume(failed.checkpointId()).status()); assertEquals(1,stock.undos);
            }
        }
    }
    @Test void auditFailureBeforeUndoStopsExternalCompensationUntilAuditIsAvailable() {
        Tool stock = new Tool("stock",true), fail = fatalRead(); AtomicInteger started = new AtomicInteger();
        SagaAuditStore audit = (cp,s,e) -> { if (e.equals("STARTED") && started.getAndIncrement() == 0) throw new IllegalStateException("db offline"); };
        try (var h = new Harness(List.of(stock,fail),audit,new Generator())) {
            var failed = h.decide(h.start(call(0,"stock"),call(1,"fatal_read")),HitlApprovalService.Action.APPROVE);
            assertEquals("COMPENSATION_FAILED",failed.status()); assertEquals(0,stock.undos);
            assertEquals("ROLLED_BACK",h.resume(failed.checkpointId()).status()); assertEquals(1,stock.undos);
        }
    }
    @Test void nonIdempotentCompensationInFlightAtCrashRequiresManualReconciliation() {
        Tool stock = new Tool("stock",true), fail = fatalRead(); stock.undo = key -> { throw new AssertionError("process died"); };
        try (var h = new Harness(List.of(stock,fail),(cp,s,e)->{},new Generator())) {
            var first = h.start(call(0,"stock"),call(1,"fatal_read"));
            assertThrows(AssertionError.class,()->h.decide(first,HitlApprovalService.Action.APPROVE));
            assertEquals("COMPENSATION_UNCERTAIN",h.resume(first.checkpointId()).status()); assertEquals(1,stock.undos);
        }
    }
    @Test void idempotentCompensationAfterCrashUsesSameKeyAndExternalDeduplication() {
        Tool stock = new Tool("stock",true), fail = fatalRead(); stock.retrySafe = true;
        Set<String> externalEffects = new HashSet<>();
        stock.undo = key -> { externalEffects.add(key); if (stock.undos == 1) throw new AssertionError("process died after undo"); return "deduplicated"; };
        try (var h = new Harness(List.of(stock,fail),(cp,s,e)->{},new Generator())) {
            var first = h.start(call(0,"stock"),call(1,"fatal_read"));
            assertThrows(AssertionError.class,()->h.decide(first,HitlApprovalService.Action.APPROVE));
            assertEquals("ROLLED_BACK",h.resume(first.checkpointId()).status()); assertEquals(2,stock.undos);
            assertEquals(stock.keys.get(0),stock.keys.get(1)); assertEquals(1,externalEffects.size());
        }
    }
    @Test void unsupportedCompensationRetainsCheckpointAndNeverInventsUndo() {
        Tool stock = new Tool("irreversible",true), fail = fatalRead(); stock.supports = false;
        try (var h = new Harness(List.of(stock,fail),(cp,s,e)->{},new Generator())) {
            var result = h.decide(h.start(call(0,"irreversible"),call(1,"fatal_read")),HitlApprovalService.Action.APPROVE);
            assertEquals("COMPENSATION_FAILED",result.status()); assertEquals(0,stock.undos);
            assertEquals("COMPENSATION_UNSUPPORTED",result.compensations().get(0).errorCode()); assertFalse(h.db.snapshots.isEmpty());
        }
    }
    @Test void reflectionSelectsOnlyOriginalEvidenceBoundCanonicalCandidateAndRetriesRead() {
        Tool tool = normalizer(false); Generator model = new Generator(); model.responses.add("{\"candidateIds\":[\"c0\"]}");
        try (var h = new Harness(List.of(tool),(cp,s,e)->{},model)) {
            var result = h.start(call(0,"normalize",Map.of("port","Rotterdam")));
            assertEquals("COMPLETED",result.status()); assertEquals(2,tool.calls); assertEquals(1,model.calls);
            assertEquals(1,result.reflectionAttempts().get(0)); assertTrue(result.timings().reflectionModelMs()>0);
            assertFalse(h.db.receipts.values().stream().anyMatch(raw -> raw.contains("test-secret")));
            assertTrue(result.reactSteps().stream().anyMatch(s -> s.getType()==ReActStep.StepType.REFLECTION));
        }
    }
    @Test void malformedOrInventedModelCorrectionsStopAfterTwoAttemptsAcrossServiceRestart() {
        Tool tool = normalizer(false); Generator model = new Generator();
        model.responses.add("{\"parameters\":{\"port\":\"INVENTED\"}}"); model.responses.add("{\"candidateIds\":[\"c999\"]}");
        try (var h = new Harness(List.of(tool),(cp,s,e)->{},model)) {
            var result = h.start(call(0,"normalize",Map.of("port","Rotterdam")));
            assertEquals("WAITING_APPROVAL",result.status()); assertEquals(2,model.calls); assertEquals(1,tool.calls);
            try (var fresh = new Harness(h.db,List.of(tool),(cp,s,e)->{},model)) {
                var retried = fresh.decide(result,HitlApprovalService.Action.APPROVE);
                assertEquals("WAITING_APPROVAL",retried.status()); assertEquals(2,model.calls); assertEquals(2,tool.calls);
            }
        }
    }
    @Test void unboundEvidenceCannotReachTheModelOrChangeParameters() {
        Tool tool=normalizer(false); tool.candidates=List.of(new ToolCorrectionCandidate("port","NLRTM","$original","纽约")); Generator model=new Generator();
        try(var h=new Harness(List.of(tool),(cp,s,e)->{},model)) {
            assertEquals("WAITING_APPROVAL",h.start(call(0,"normalize",Map.of("port","Rotterdam"))).status());
            assertEquals(0,model.calls); assertEquals(1,tool.calls);
        }
    }
    @Test void correctedHighRiskWriteNeedsNewApprovalBeforeItCanHaveEffects() {
        Tool tool=normalizer(true); Generator model=new Generator(); model.responses.add("{\"candidateIds\":[\"c0\"]}");
        try(var h=new Harness(List.of(tool),(cp,s,e)->{},model)) {
            var first=h.start(call(0,"normalize",Map.of("port","Rotterdam")));
            var corrected=h.decide(first,HitlApprovalService.Action.APPROVE);
            assertEquals("WAITING_APPROVAL",corrected.status()); assertNotEquals(first.approvalId(),corrected.approvalId());
            assertEquals("NLRTM",corrected.parameters().get("port")); assertEquals(1,tool.calls);
            assertEquals("COMPLETED",h.decide(corrected,HitlApprovalService.Action.APPROVE).status()); assertEquals(2,tool.calls);
        }
    }
    @Test void unknownWriteOutcomeCannotEnterReflectionEvenIfMarkedRecoverable() {
        Tool tool=normalizer(true); Generator model=new Generator();
        tool.run=p->{ throw new ToolExecutionException("OUTCOME_UNKNOWN","结果不确定",ToolExecutionException.Category.RECOVERABLE,ToolExecutionException.EffectOutcome.UNKNOWN); };
        try(var h=new Harness(List.of(tool),(cp,s,e)->{},model)) {
            var result=h.decide(h.start(call(0,"normalize",Map.of("port","Rotterdam"))),HitlApprovalService.Action.APPROVE);
            assertEquals("EXECUTION_UNCERTAIN",result.status()); assertEquals(0,model.calls); assertEquals(1,tool.calls);
        }
    }
    @Test void humanOverrideCannotBeSilentlyReplacedByReflection() {
        Tool tool=normalizer(true); Generator model=new Generator();
        try(var h=new Harness(List.of(tool),(cp,s,e)->{},model)) {
            var waiting=h.start(call(0,"normalize",Map.of("port","wrong")));
            var result=h.approvals.decide("1001","session","task",waiting.approvalId(),HitlApprovalService.Action.OVERRIDE,Map.of("port","Rotterdam"),MODEL);
            assertEquals("WAITING_APPROVAL",result.status()); assertEquals("Rotterdam",result.parameters().get("port")); assertEquals(0,model.calls);
        }
    }
}
