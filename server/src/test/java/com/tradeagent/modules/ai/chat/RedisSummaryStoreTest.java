package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RedisSummaryStoreTest {
    @Test
    @EnabledIfSystemProperty(named = "summary.redis.test", matches = "true")
    void persistsAcrossStoreInstancesAndRejectsStaleWrites() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1",
                Integer.getInteger("summary.redis.port", 6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        String tenant = "summary-test-" + UUID.randomUUID();
        String session = "session-test";
        String key = RedisSummaryStore.key(tenant, session);
        try {
            var first = new RedisSummaryStore(redis, new ObjectMapper());
            var second = new RedisSummaryStore(redis, new ObjectMapper());
            var old = new ConversationSummary(1, "旧摘要", 2, "m2", "hash1");
            var next = new ConversationSummary(1, "新摘要", 4, "m4", "hash2");
            assertNull(first.read(tenant, session));
            assertTrue(first.saveIfUnchanged(tenant, session, null, old));
            assertEquals(old, second.read(tenant, session));
            assertTrue(second.saveIfUnchanged(tenant, session, old, next));
            assertFalse(first.saveIfUnchanged(tenant, session, old, old));
            assertEquals(next, first.read(tenant, session));
            assertTrue(redis.getExpire(key) > 600000);
            assertNull(first.read(tenant + "-other", session));
        } finally {
            redis.delete(key); // 只清理本测试创建的随机键。
            factory.destroy();
        }
    }
}
