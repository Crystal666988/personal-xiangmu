package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Component
public class RedisSummaryStore implements SummaryStore {
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if (not current and ARGV[1] == '') or current == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'EX', 604800)
                return 1
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisSummaryStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public ConversationSummary read(String tenantId, String sessionId) {
        String value = redis.opsForValue().get(key(tenantId, sessionId));
        if (value == null) return null;
        try {
            return json.readValue(value, ConversationSummary.class);
        } catch (Exception e) {
            throw new IllegalStateException("摘要检查点读取失败", e);
        }
    }

    @Override
    public boolean saveIfUnchanged(String tenantId, String sessionId,
                                   ConversationSummary expected, ConversationSummary replacement) {
        try {
            Long updated = redis.execute(SAVE, List.of(key(tenantId, sessionId)),
                    expected == null ? "" : json.writeValueAsString(expected),
                    json.writeValueAsString(replacement));
            return Long.valueOf(1L).equals(updated);
        } catch (Exception e) {
            throw new IllegalStateException("摘要检查点保存失败", e);
        }
    }

    static String key(String tenantId, String sessionId) {
        // 长度前缀避免 tenant="a:b",session="c" 与 tenant="a",session="b:c" 碰撞。
        return "agent:summary:v1:" + hash(tenantId.length() + ":" + tenantId + sessionId.length() + ":" + sessionId);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
