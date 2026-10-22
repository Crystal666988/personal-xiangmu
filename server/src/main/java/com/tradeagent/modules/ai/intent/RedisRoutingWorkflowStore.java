package com.tradeagent.modules.ai.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public final class RedisRoutingWorkflowStore implements RoutingWorkflowStore {
    static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'EX', 604800)
            return 1
            """, Long.class);
    static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    static final DefaultRedisScript<Long> COMMIT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'EX', 604800)
            redis.call('SET', KEYS[3], ARGV[3], 'EX', 604800)
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public RedisRoutingWorkflowStore(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }
    static String key(String tenant, String session) { return "routing:workflow:{" + hash(tenant.length() + ":" + tenant + session.length() + ":" + session) + "}"; }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Digest unavailable"); }
    }
    @Override public String acquire(String tenant, String session) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(tenant, session) + ":lock", token,
                Duration.ofMinutes(2))) ? token : null;
    }
    @Override public void release(String tenant, String session, String token) {
        redis.execute(RELEASE, List.of(key(tenant, session) + ":lock"), token);
    }
    @Override public State read(String tenant, String session) { return readValue(key(tenant, session) + ":state", State.class); }
    @Override public RoutingWorkflowService.Result receipt(String tenant, String session, String messageId) {
        return readValue(key(tenant, session) + ":receipt:" + hash(messageId), RoutingWorkflowService.Result.class);
    }
    @Override public boolean save(String tenant, String session, String token, State state) {
        return write(key(tenant, session), ":state", token, state);
    }
    @Override public boolean complete(String tenant, String session, String token, String messageId,
                                      RoutingWorkflowService.Result result, State state) {
        String root = key(tenant, session);
        try {
            return Long.valueOf(1).equals(redis.execute(COMMIT,
                    List.of(root + ":lock", root + ":state", root + ":receipt:" + hash(messageId)),
                    token, json.writeValueAsString(state), json.writeValueAsString(result)));
        } catch (Exception e) { throw new IllegalStateException("Routing state unavailable"); }
    }
    private boolean write(String root, String suffix, String token, Object value) {
        try { return Long.valueOf(1).equals(redis.execute(WRITE, List.of(root + ":lock", root + suffix), token, json.writeValueAsString(value))); }
        catch (Exception e) { throw new IllegalStateException("Routing state unavailable"); }
    }
    private <T> T readValue(String key, Class<T> type) {
        String value = redis.opsForValue().get(key);
        if (value == null) return null;
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Routing state is invalid"); }
    }
}
