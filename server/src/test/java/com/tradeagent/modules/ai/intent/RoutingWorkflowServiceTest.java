package com.tradeagent.modules.ai.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.EvidenceRoutingPlan;
import com.tradeagent.core.dag.EvidenceRoutingPlan.*;
import com.tradeagent.modules.ai.chat.AgentMessage;
import com.tradeagent.modules.ai.tools.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Entire workflow with real parser, handlers and Redis JSON codec; no network/provider/Redis. */
class RoutingWorkflowServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final RedisStub redis = new RedisStub();
    final RedisRoutingWorkflowStore store = new RedisRoutingWorkflowStore(redis, json);
    final Deque<String> outputs = new ArrayDeque<>();
    final List<String> calls = new ArrayList<>();
    final List<Map<String,Object>> inputs = new ArrayList<>();
    final ToolRegistry registry = new ToolRegistry(List.of(wrap(new TierPriceCalculatorTool()),
            wrap(new FreightCifEstimatorTool()), wrap(new CurrencyExchangeRateTool()), wrap(new CustomsDataLookupTool())));
    final RoutingWorkflowService workflow = new RoutingWorkflowService(new RoutingPreviewService(new RoutingPlanGenerator() {
        public boolean isAvailable(RoutingModelConfig config) { return config != null; }
        public String generate(String instructions, String source, RoutingModelConfig config) { return outputs.removeFirst(); }
    }, new EvidenceToolCatalog(registry), new StrictEvidencePlanReader(), json), new ValidatedPlanExecutor(registry), store);
    final RoutingModelConfig config = new RoutingModelConfig("secret", "https://unused.test", "model", 7L, "openai");

    IToolHandler wrap(IToolHandler actual) {
        return new IToolHandler() {
            public ToolDefinition getDefinition() { return actual.getDefinition(); }
            public Object execute(Map<String,Object> p) throws Exception { calls.add(actual.getDefinition().getId()); inputs.add(Map.copyOf(p)); return actual.execute(p); }
        };
    }
    AgentMessage user(String id, String text) { return AgentMessage.builder().id(id).sessionId("s").role("user").content(text).build(); }
    SlotCandidate slot(String id, SlotType type, String text, String source) { return new SlotCandidate(id,type,text,null,null,source); }
    Task price() { return new Task("calculate_tier_price",Map.of("productModel","m","quantity","q")); }
    Task freight() { return new Task("estimate_freight_cif",Map.of("quantity","q","originPort","o","destPort","d")); }
    void enqueue(List<Task> tasks, SlotCandidate... slots) throws Exception { outputs.add(json.writeValueAsString(new EvidenceRoutingPlan(tasks,List.of(slots)))); }
    void pricePlan(String modelSource, String quantitySource, String q) throws Exception {
        enqueue(List.of(price()),slot("m",SlotType.MODEL,"TOPCon580W",modelSource),slot("q",SlotType.QUANTITY,q,quantitySource));
    }
    RoutingWorkflowService.Result run(AgentMessage current,List<AgentMessage> history) { return workflow.run("7","s",current,history,config,"continue"); }

    @Test void missingPauseSupplementExecuteAndReplaySurviveActualRedisSerialization() throws Exception {
        var first=user("u1","请报价TOPCon580W");
        enqueue(List.of(new Task("calculate_tier_price",Map.of("productModel","m"))),slot("m",SlotType.MODEL,"TOPCon580W","u1"));
        var waiting=run(first,List.of(first));
        assertEquals("NEEDS_CLARIFICATION",waiting.routingStatus()); assertTrue(waiting.pending()); assertFalse(waiting.shouldGenerate()); assertTrue(calls.isEmpty());
        assertEquals("WAITING",store.read("7","s").phase());
        var supplement=user("u2","采购2,000片"); pricePlan("u1","u2","2,000片");
        var done=run(supplement,List.of(first,supplement));
        assertEquals("EXECUTED",done.routingStatus()); assertTrue(done.shouldGenerate()); assertFalse(done.pending());
        assertEquals(List.of("calculate_tier_price"),calls); assertEquals(2000,inputs.get(0).get("quantity"));
        assertEquals(2,done.sourceMessages().size());
        assertEquals("u2",done.routingPlan().resolvedSlots().get(1).source().sourceMessageId());
        var replay=run(supplement,List.of(first,supplement));
        assertEquals("EXECUTED",replay.routingStatus()); assertEquals(1,calls.size()); assertEquals(1,replay.telemetry().getToolsCalledCount());
        var tree=json.readTree(json.writeValueAsString(replay));
        assertEquals(2000,tree.path("routingPlan").path("resolvedSlots").get(1).path("value").intValue());
        assertFalse(tree.path("routingPlan").path("executable").asBoolean(true));
        assertTrue(tree.path("telemetry").path("traces").get(0).path("outputResult").path("result").path("isLive").isBoolean());
        assertFalse(redis.values.values().stream().anyMatch(s -> s.contains("secret")));
        first.setContent("changed");
        assertEquals("SOURCE_CHANGED",run(supplement,List.of(first,supplement)).routingStatus()); assertEquals(1,calls.size());
    }

    @Test void wholePlanPreflightPreventsEarlierValidPriceWhenPortUnsupported() throws Exception {
        var current=user("u","TOPCon580W 2000片，从宁波发往阿布扎比，CIF报价");
        enqueue(List.of(price(),freight()),slot("m",SlotType.MODEL,"TOPCon580W","u"),slot("q",SlotType.QUANTITY,"2000片","u"),
                slot("o",SlotType.PORT,"宁波","u"),slot("d",SlotType.PORT,"阿布扎比","u"));
        var result=run(current,List.of(current));
        assertEquals("NEEDS_CLARIFICATION",result.routingStatus()); assertTrue(calls.isEmpty());
    }

    @Test void orderedCifUsesPriceResultAndNoAutomaticFxOrFixedPowerDenominator() throws Exception {
        var current=user("u","TOPCon580W 2000片，从宁波发往汉堡，CIF报价");
        enqueue(List.of(price(),freight()),slot("m",SlotType.MODEL,"TOPCon580W","u"),slot("q",SlotType.QUANTITY,"2000片","u"),
                slot("o",SlotType.PORT,"宁波","u"),slot("d",SlotType.PORT,"汉堡","u"));
        var result=run(current,List.of(current));
        assertEquals("EXECUTED",result.routingStatus()); assertEquals(List.of("calculate_tier_price","estimate_freight_cif"),calls);
        assertTrue(((Number)inputs.get(1).get("fobTotalUsd")).doubleValue()>0);
        var trace=json.valueToTree(result.telemetry().getTraces().get(1));
        assertTrue(trace.path("outputResult").path("parameterSources").path("fobTotalUsd").asText().startsWith("DERIVED_TASK"));
        assertFalse(trace.path("outputResult").path("result").path("isLive").asBoolean(true));
    }

    @Test void chineseAdjacentCifRequiresFobButPureFreightDoesNotInventIt() throws Exception {
        for(String text:List.of("2000片，从宁波发往汉堡，CIF报价","2000片，从宁波发往汉堡，海运费")) {
            var current=user(UUID.randomUUID().toString(),text);
            enqueue(List.of(freight()),slot("q",SlotType.QUANTITY,"2000片",current.getId()),slot("o",SlotType.PORT,"宁波",current.getId()),slot("d",SlotType.PORT,"汉堡",current.getId()));
            var result=workflow.run("7","s",current,List.of(current),config,"new");
            assertEquals(text.contains("CIF")?"NEEDS_CLARIFICATION":"EXECUTED",result.routingStatus());
        }
        assertEquals(1,calls.size()); assertFalse(inputs.get(0).containsKey("fobTotalUsd"));
        var result=(Map<?,?>)new FreightCifEstimatorTool().execute(inputs.get(0));
        assertFalse(result.containsKey("cifTotalUsd"));
    }

    @Test void emptyContinuationCannotErasePendingTaskAndCancellationIsExplicit() throws Exception {
        var first=user("u1","TOPCon580W");
        enqueue(List.of(new Task("calculate_tier_price",Map.of("productModel","m"))),slot("m",SlotType.MODEL,"TOPCon580W","u1"));
        run(first,List.of(first)); var next=user("u2","继续"); enqueue(List.of());
        var result=run(next,List.of(first,next)); assertEquals("REJECTED",result.routingStatus()); assertTrue(result.pending()); assertTrue(calls.isEmpty());
        assertEquals("calculate_tier_price",store.read("7","s").plan().plan().tasks().get(0).tool());
        assertEquals("CANCELLED",workflow.cancel("7","s").routingStatus()); assertEquals("DONE",store.read("7","s").phase());
    }

    @Test void finalWriteFailureLeavesFenceAndCannotRepeatTools() throws Exception {
        var current=user("u","TOPCon580W 2000片"); pricePlan("u","u","2000片"); redis.failCommit=true;
        assertEquals("STATE_UNAVAILABLE",run(current,List.of(current)).routingStatus()); assertEquals(1,calls.size());
        redis.failCommit=false;
        assertEquals("EXECUTION_UNCERTAIN",run(current,List.of(current)).routingStatus()); assertEquals(1,calls.size());
        assertEquals("EXECUTION_UNCERTAIN",workflow.cancel("7","s").routingStatus());
    }

    @Test void invalidPlanUnconfiguredModelOrLostLeaseNeverExecutes() throws Exception {
        var current=user("u","TOPCon580W 2000片"); outputs.add("not json");
        assertEquals("REJECTED",run(current,List.of(current)).routingStatus());
        assertEquals("MODEL_UNAVAILABLE",workflow.run("7","other",current,List.of(current),null,"new").routingStatus());
        pricePlan("assistant","u","2000片");
        assertEquals("REJECTED",workflow.run("7","s",user("u2",current.getContent()),List.of(current),config,"new").routingStatus());
        redis.rejectWrite=true; pricePlan("u","u","2000片");
        assertEquals("STATE_UNAVAILABLE",workflow.run("7","third",current,List.of(current),config,"new").routingStatus()); assertTrue(calls.isEmpty());
    }

    @Test void duplicateTasksRejectBeforeAnyInvocation() throws Exception {
        var current=user("u","TOPCon580W 2000片");
        enqueue(List.of(price(),price()),slot("m",SlotType.MODEL,"TOPCon580W","u"),slot("q",SlotType.QUANTITY,"2000片","u"));
        assertEquals("NEEDS_CLARIFICATION",run(current,List.of(current)).routingStatus()); assertTrue(calls.isEmpty());
    }

    @Test void correctedQuantityMustUseLatestUserSource() throws Exception {
        var first=user("u1","TOPCon580W 2000片或3000片"); pricePlan("u1","u1","2000片");
        assertEquals("NEEDS_CLARIFICATION",run(first,List.of(first)).routingStatus());
        var next=user("u2","更正为2500片"); pricePlan("u1","u1","2000片");
        assertEquals("NEEDS_CLARIFICATION",run(next,List.of(first,next)).routingStatus()); assertTrue(calls.isEmpty());
        var finalTurn=user("u3","更正为2500片"); pricePlan("u1","u3","2500片");
        assertEquals("EXECUTED",run(finalTurn,List.of(first,next,finalTurn)).routingStatus()); assertEquals(2500,inputs.get(0).get("quantity"));
    }

    @Test void redisTokenComparisonIsolationAndAtomicCommit() throws Exception {
        assertNotEquals(RedisRoutingWorkflowStore.key("a:b","c"),RedisRoutingWorkflowStore.key("a","b:c"));
        String token=store.acquire("7","s"); assertNull(store.acquire("7","s"));
        var state=new RoutingWorkflowStore.State("u","WAITING",List.of(),null);
        assertFalse(store.save("7","s","stale",state)); store.release("7","s","stale"); assertNull(store.acquire("7","s"));
        assertTrue(store.save("7","s",token,state)); assertNull(store.read("8","s"));
        store.release("7","s",token); assertNotNull(store.acquire("7","s"));
    }

    @Test void explicitDestinationCorrectionCannotReuseOldDestination() throws Exception {
        var first=user("u1","2000片发往汉堡");
        enqueue(List.of(new Task("estimate_freight_cif",Map.of("quantity","q","destPort","d"))),
                slot("q",SlotType.QUANTITY,"2000片","u1"),slot("d",SlotType.PORT,"汉堡","u1"));
        assertEquals("NEEDS_CLARIFICATION",run(first,List.of(first)).routingStatus());
        var next=user("u2","宁波起运，目的地改成迪拜");
        enqueue(List.of(freight()),slot("q",SlotType.QUANTITY,"2000片","u1"),slot("o",SlotType.PORT,"宁波","u2"),slot("d",SlotType.PORT,"汉堡","u1"));
        var result=run(next,List.of(first,next)); assertEquals("NEEDS_CLARIFICATION",result.routingStatus());
        assertEquals("STALE_USER_SOURCE",result.routingPlan().issues().get(0).code()); assertTrue(calls.isEmpty());
    }

    @Test void explicitCurrencyCorrectionCannotReuseOldCurrency() throws Exception {
        var first=user("u1","人民币兑换，目标币种待定");
        enqueue(List.of(new Task("currency_exchange_rate",Map.of("baseCurrency","b"))),slot("b",SlotType.CURRENCY,"人民币","u1"));
        assertEquals("NEEDS_CLARIFICATION",run(first,List.of(first)).routingStatus());
        var next=user("u2","不是人民币，是欧元，兑换美元");
        enqueue(List.of(new Task("currency_exchange_rate",Map.of("baseCurrency","b","targetCurrency","t"))),
                slot("b",SlotType.CURRENCY,"人民币","u1"),slot("t",SlotType.CURRENCY,"美元","u2"));
        var result=run(next,List.of(first,next)); assertEquals("NEEDS_CLARIFICATION",result.routingStatus());
        assertEquals("STALE_USER_SOURCE",result.routingPlan().issues().get(0).code()); assertTrue(calls.isEmpty());
    }

    @Test void realHandlersRejectGuessedDefaultsAndLegacyAutomaticRoutingIsDisabled() {
        assertThrows(IllegalArgumentException.class,()->new TierPriceCalculatorTool().execute(Map.of("productModel","TOPCon-580W")));
        assertThrows(IllegalArgumentException.class,()->new FreightCifEstimatorTool().execute(Map.of("quantity",2000,"originPort","Ningbo")));
        assertThrows(IllegalArgumentException.class,()->new CurrencyExchangeRateTool().execute(Map.of()));
        assertThrows(IllegalArgumentException.class,()->new CustomsDataLookupTool().execute(Map.of()));
        assertThrows(UnsupportedOperationException.class,()->new ToolDispatchService(registry,new com.tradeagent.core.dag.IntentRouter()).autoDispatch("报价",null));
    }

    static final class RedisStub extends StringRedisTemplate {
        final Map<String,String> values=new HashMap<>(); boolean failCommit,rejectWrite;
        @SuppressWarnings("unchecked") final ValueOperations<String,String> ops=(ValueOperations<String,String>)Proxy.newProxyInstance(
                ValueOperations.class.getClassLoader(),new Class[]{ValueOperations.class},(p,m,a)->switch(m.getName()) {
                    case "get" -> values.get(a[0]);
                    case "setIfAbsent" -> { if(values.containsKey(a[0])) yield false; values.put((String)a[0],(String)a[1]); yield true; }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        @Override public ValueOperations<String,String> opsForValue() { return ops; }
        @SuppressWarnings("unchecked") @Override public <T> T execute(RedisScript<T> script,List<String> keys,Object... args) {
            if(!Objects.equals(values.get(keys.get(0)),args[0])) return (T)Long.valueOf(0);
            if(script==RedisRoutingWorkflowStore.RELEASE) values.remove(keys.get(0));
            else if(script==RedisRoutingWorkflowStore.WRITE) { if(rejectWrite) return (T)Long.valueOf(0); values.put(keys.get(1),(String)args[1]); }
            else if(script==RedisRoutingWorkflowStore.COMMIT) { if(failCommit) throw new IllegalStateException("offline write failure"); values.put(keys.get(1),(String)args[1]);values.put(keys.get(2),(String)args[2]); }
            else throw new AssertionError("Unexpected script");
            return (T)Long.valueOf(1);
        }
    }
}
