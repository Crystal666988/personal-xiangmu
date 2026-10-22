package com.tradeagent.core.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.model.*;
import com.tradeagent.modules.ai.intent.RedisRoutingWorkflowStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** Redis Hash snapshots with optimistic fencing; all keys in an execution share a Redis Cluster slot. */
@Service
public class ExecutionCheckpointService {
    private static final long TTL = 86400;
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[4]) == 1 then return 0 end
        if redis.call('EXISTS', KEYS[3]) == 1 then return -1 end
        redis.call('HMSET', KEYS[1], 'snapshot', ARGV[1], 'version', '0', 'status', 'READY')
        redis.call('EXPIRE', KEYS[1], ARGV[3])
        redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
        redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[5]) ~= ARGV[7] then return 0 end
        if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end
        redis.call('HMSET', KEYS[1], 'snapshot', ARGV[2], 'version', ARGV[3], 'status', ARGV[4])
        redis.call('EXPIRE', KEYS[1], ARGV[6])
        redis.call('SET', KEYS[2], ARGV[5], 'EX', ARGV[6])
        redis.call('SET', KEYS[3], ARGV[5], 'EX', ARGV[6])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[5]) ~= ARGV[5] then return 0 end
        if redis.call('EXISTS', KEYS[1]) == 0 then
            local receipt = redis.call('GET', KEYS[4])
            if receipt then
                local ok, decoded = pcall(cjson.decode, receipt)
                if ok and decoded['checkpointId'] == ARGV[6] then return 1 end
            end
            return 0
        end
        if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end
        redis.call('SET', KEYS[4], ARGV[2], 'EX', ARGV[4])
        redis.call('DEL', KEYS[1])
        redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
        if redis.call('GET', KEYS[3]) == ARGV[3] then redis.call('DEL', KEYS[3]) end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        return redis.call('EXPIRE', KEYS[1], 60)
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        return redis.call('DEL', KEYS[1])
        """, Long.class);
    private final ThreadLocal<String> lease = new ThreadLocal<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "checkpoint-lease"); thread.setDaemon(true); return thread;
    });
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private ObjectProvider<CheckpointedDagExecutor> resumeExecutor;
    public ExecutionCheckpointService(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }
    @Autowired void setResumeExecutor(ObjectProvider<CheckpointedDagExecutor> executor) { this.resumeExecutor = executor; }
    @PreDestroy public void close() { heartbeat.shutdownNow(); }

    public CheckpointedDagExecutor.View resumeExecution(String tenant, String session, String checkpointId, Map<String, Object> injectedSlots) {
        return resumeExecution(tenant, session, checkpointId, injectedSlots, null);
    }
    public CheckpointedDagExecutor.View resumeExecution(String tenant, String session, String checkpointId, Map<String, Object> injectedSlots,
                                                       com.tradeagent.modules.ai.intent.RoutingModelConfig model) {
        if (resumeExecutor == null) throw new IllegalStateException("执行协调器未初始化");
        return resumeExecutor.getObject().resumeExecution(tenant, session, checkpointId, injectedSlots, model);
    }

    public <T> T locked(String tenant, String session, String task, Supplier<T> action) {
        String key = root(tenant, session) + ":" + id(task) + ":lease", token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(60))))
            throw conflict("该执行正在处理中，请稍后刷新");
        ScheduledFuture<?> renewal = heartbeat.scheduleAtFixedRate(() -> {
            try { redis.execute(RENEW, List.of(key), token); } catch (RuntimeException ignored) { /* writes are fenced */ }
        }, 15, 15, TimeUnit.SECONDS);
        lease.set(token);
        try { return action.get(); }
        finally {
            lease.remove(); renewal.cancel(false);
            try { redis.execute(RELEASE, List.of(key), token); } catch (RuntimeException ignored) { /* TTL releases */ }
        }
    }

    /** Tenant is mandatory; the task ID is the persisted user message ID for chat executions. */
    public AgentExecutionCheckpoint saveCheckpoint(String tenant, String session, String task, DagExecutionState state) {
        var existing = getCheckpoint(tenant, session, task);
        if (existing.isPresent()) return existing.get();
        AgentExecutionCheckpoint cp = new AgentExecutionCheckpoint();
        cp.setCheckpointId("cp_" + UUID.randomUUID()); cp.setTenantId(tenant); cp.setSessionId(session); cp.setTaskId(task);
        cp.setCreatedAt(System.currentTimeMillis()); cp.setUpdatedAt(cp.getCreatedAt()); cp.setState(state);
        Long result = redis.execute(CREATE, keys(cp), encode(cp), task, Long.toString(TTL));
        if (Long.valueOf(-1).equals(result)) throw conflict("当前会话仍有未结束的执行，请先处理其审批或恢复。");
        if (!Long.valueOf(1).equals(result)) return getCheckpoint(tenant, session, task).orElseThrow(() -> conflict("存档创建冲突"));
        return cp;
    }
    public Optional<AgentExecutionCheckpoint> getCheckpoint(String tenant, String session, String task) {
        String root = root(tenant, session), key = root + ":" + id(task);
        Object raw = redis.opsForHash().get(key, "snapshot");
        if (raw == null) raw = redis.opsForValue().get(key + ":receipt");
        return Optional.ofNullable(raw == null ? null : decode(raw.toString()));
    }
    public Optional<AgentExecutionCheckpoint> active(String tenant, String session) {
        String task = redis.opsForValue().get(root(tenant, session) + ":active");
        return task == null ? Optional.empty() : getCheckpoint(tenant, session, task);
    }
    public AgentExecutionCheckpoint byId(String tenant, String session, String checkpointId) {
        String task = redis.opsForValue().get(root(tenant, session) + ":id:" + id(checkpointId));
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在或已过期");
        AgentExecutionCheckpoint cp = getCheckpoint(tenant, session, task)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在或已过期"));
        if (!cp.getCheckpointId().equals(checkpointId)) throw conflict("存档标识不匹配");
        return cp;
    }
    /** Every transition is CAS. A stale request can neither approve nor advance another worker's snapshot. */
    public void update(AgentExecutionCheckpoint cp) {
        String token = Objects.requireNonNull(lease.get(), "Execution lease required");
        long expected = cp.getVersion(), previousUpdatedAt = cp.getUpdatedAt();
        cp.setVersion(expected + 1); cp.setUpdatedAt(System.currentTimeMillis());
        try {
            Long result = redis.execute(SAVE, keys(cp), Long.toString(expected), encode(cp), Long.toString(cp.getVersion()),
                    cp.getState().getStatus().name(), cp.getTaskId(), Long.toString(TTL), token);
            if (!Long.valueOf(1).equals(result)) throw conflict("执行状态已改变，请刷新后重试");
        } catch (RuntimeException e) {
            // A rejected stale object must not acquire the winning worker's version in memory.
            cp.setVersion(expected); cp.setUpdatedAt(previousUpdatedAt); throw e;
        }
    }
    /** Purge only proven terminal snapshots; keep a separate receipt to prevent re-execution. */
    public void purgeCheckpoint(AgentExecutionCheckpoint cp) {
        if (!Set.of(DagExecutionState.Status.COMPLETED, DagExecutionState.Status.ROLLED_BACK).contains(cp.getState().getStatus()))
            throw conflict("未完成的执行不能清除存档");
        if (!Long.valueOf(1).equals(redis.execute(FINISH, keys(cp), Long.toString(cp.getVersion()), encode(cp),
                cp.getTaskId(), Long.toString(TTL), Objects.requireNonNull(lease.get(), "Execution lease required"), cp.getCheckpointId())))
            throw conflict("存档清理发生并发冲突");
    }
    private List<String> keys(AgentExecutionCheckpoint cp) {
        String root = root(cp.getTenantId(), cp.getSessionId()), key = root + ":" + id(cp.getTaskId());
        return List.of(key, root + ":id:" + id(cp.getCheckpointId()), root + ":active", key + ":receipt", key + ":lease");
    }
    private static String root(String tenant, String session) {
        id(tenant); id(session);
        return "agent:checkpoint:{" + RedisRoutingWorkflowStore.hash(tenant.length() + ":" + tenant + session) + "}";
    }
    private static String id(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,160}")) throw new IllegalArgumentException("非法执行标识");
        return value;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法序列化执行存档", e); }
    }
    private AgentExecutionCheckpoint decode(String value) {
        try { return json.readValue(value, AgentExecutionCheckpoint.class); }
        catch (Exception e) { throw new IllegalStateException("执行存档损坏，已停止恢复", e); }
    }
    public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
