package com.tradeagent.core.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.model.*;
import com.tradeagent.modules.ai.intent.*;
import com.tradeagent.modules.ai.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real Redis/Lua tests. Only unique test session keys are ever removed; never FLUSHDB. */
@EnabledIfEnvironmentVariable(named = "TRADEAGENT_REDIS_TESTS", matches = "true")
class RedisExecutionCheckpointIntegrationTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    ExecutionCheckpointService checkpoints;
    final String tenant = "checkpoint_test", session = "test_" + UUID.randomUUID(), task = "message";
    final ObjectMapper json = new ObjectMapper();
    final AtomicInteger calls = new AtomicInteger();
    CheckpointedDagExecutor executor;
    HitlApprovalService approvals;
    String root() { return "agent:checkpoint:{" + RedisRoutingWorkflowStore.hash(tenant.length() + ":" + tenant + session) + "}"; }
    @BeforeEach void setup() {
        var config = new RedisStandaloneConfiguration(System.getenv().getOrDefault("TRADEAGENT_TEST_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("TRADEAGENT_TEST_REDIS_PORT", "6379")));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("TRADEAGENT_TEST_REDIS_DB", "0")));
        String password = System.getenv("TRADEAGENT_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(password);
        factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        checkpoints = new ExecutionCheckpointService(redis, json);
        var real = new TierPriceCalculatorTool();
        var tools = new ToolRegistry(List.of(new IToolHandler() {
            public ToolDefinition getDefinition() { return real.getDefinition(); }
            public Object execute(Map<String, Object> params) { calls.incrementAndGet(); return real.execute(params); }
        }));
        executor = new CheckpointedDagExecutor(checkpoints, tools, json);
        approvals = new HitlApprovalService(checkpoints, executor, tools);
    }
    @AfterEach void cleanup() {
        if (checkpoints != null) checkpoints.close();
        try {
            if (redis != null) try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(root() + ":*").count(100).build())) {
                List<String> owned = new ArrayList<>(); keys.forEachRemaining(owned::add);
                if (!owned.isEmpty()) redis.delete(owned);
            }
        } finally { if (factory != null) factory.destroy(); }
    }
    ValidatedPlanExecutor.Prepared prepared() {
        return new ValidatedPlanExecutor.Prepared(List.of(new ValidatedPlanExecutor.Call(0, "calculate_tier_price",
                Map.of("productModel", "TOPCon-580W", "quantity", 2000, "basePricePerWatt", 0.10), Map.of(), null)), List.of());
    }
    @Test void realHashHas24HourTtlAndFreshServicesCanRecoverTheEntirePlan() {
        var waiting = executor.start(tenant, session, task, "original", Map.of("topology", "validated"), prepared());
        assertEquals("WAITING_APPROVAL", waiting.status()); assertEquals(0, calls.get());
        assertEquals(DataType.HASH, redis.type(root() + ":" + task));
        Long ttl = redis.getExpire(root() + ":" + task); assertNotNull(ttl); assertTrue(ttl > 86300 && ttl <= 86400);
        try (FreshService fresh = new FreshService(redis, new ObjectMapper())) {
            var restored = fresh.byId(tenant, session, waiting.checkpointId());
            assertEquals(waiting.approvalId(), restored.getState().getApprovalId());
            assertEquals(prepared().calls().get(0).tool(), restored.getState().getPrepared().calls().get(0).tool());
            assertTrue(restored.getState().getDagPlanJson().contains("topology"));
        }
    }
    @Test void luaFencesConcurrentApprovalAndAtomicallyPurgesTerminalHashButKeepsIdempotentReceipt() throws Exception {
        var waiting = executor.start(tenant, session, task, "original", "plan", prepared());
        var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        try {
            Callable<String> decide = () -> { barrier.await(); try {
                return approvals.decide(tenant, session, task, waiting.approvalId(), HitlApprovalService.Action.APPROVE, null).status();
            } catch (ResponseStatusException e) { return "CONFLICT"; } };
            var results = pool.invokeAll(List.of(decide, decide));
            assertEquals(Set.of("COMPLETED", "CONFLICT"), Set.of(results.get(0).get(), results.get(1).get()));
        } finally { pool.shutdownNow(); }
        assertEquals(1, calls.get()); assertFalse(Boolean.TRUE.equals(redis.hasKey(root() + ":" + task)));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(root() + ":" + task + ":receipt")));
        assertTrue(checkpoints.active(tenant, session).isEmpty());
        assertEquals("COMPLETED", executor.resumeExecution(tenant, session, waiting.checkpointId(), Map.of()).status());
        assertEquals(1, calls.get());
    }
    @Test void staleVersionsAndLostLeaseCannotCommitAndTenantsCannotReadEachOthersState() {
        var state = new DagExecutionState(); state.setPrepared(prepared()); state.setDagPlanJson("{}");
        checkpoints.saveCheckpoint(tenant, session, task, state);
        assertTrue(checkpoints.getCheckpoint("other_tenant", session, task).isEmpty());
        checkpoints.locked(tenant, session, task, () -> {
            var first = checkpoints.getCheckpoint(tenant, session, task).orElseThrow();
            var stale = checkpoints.getCheckpoint(tenant, session, task).orElseThrow();
            first.getState().setOriginalQuery("updated"); checkpoints.update(first);
            assertThrows(ResponseStatusException.class, () -> checkpoints.update(stale));
            var current = checkpoints.getCheckpoint(tenant, session, task).orElseThrow();
            redis.opsForValue().set(root() + ":" + task + ":lease", "other-owner", Duration.ofSeconds(60));
            assertThrows(ResponseStatusException.class, () -> checkpoints.update(current));
            assertThrows(ResponseStatusException.class, () -> checkpoints.purgeCheckpoint(current));
            return null;
        });
    }
    static class FreshService extends ExecutionCheckpointService implements AutoCloseable {
        FreshService(StringRedisTemplate redis, ObjectMapper json) { super(redis, json); }
    }
}
