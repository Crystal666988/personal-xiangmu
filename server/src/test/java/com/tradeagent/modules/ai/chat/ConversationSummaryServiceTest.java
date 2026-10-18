package com.tradeagent.modules.ai.chat;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationSummaryServiceTest {
    private static class Store implements SummaryStore {
        Map<String, ConversationSummary> entries = new HashMap<>();
        boolean unavailable, saveFails, conflict;
        public ConversationSummary read(String tenant, String session) {
            if (unavailable) throw new IllegalStateException();
            return entries.get(RedisSummaryStore.key(tenant, session));
        }
        public boolean saveIfUnchanged(String tenant, String session,
                ConversationSummary expected, ConversationSummary replacement) {
            if (saveFails) throw new IllegalStateException();
            if (conflict || !Objects.equals(read(tenant, session), expected)) return false;
            entries.put(RedisSummaryStore.key(tenant, session), replacement);
            return true;
        }
    }
    private static class Generator implements SummaryGenerator {
        boolean available = true, fails;
        int calls;
        String output = "事实摘要";
        String previous;
        List<ConversationSummaryService.SourceMessage> additional;
        public boolean isAvailable() { return available; }
        public String generate(String previous, List<ConversationSummaryService.SourceMessage> messages) {
            calls++; this.previous = previous; additional = List.copyOf(messages);
            if (fails) throw new IllegalStateException();
            return output;
        }
    }
    private final Store store = new Store();
    private final Generator generator = new Generator();
    private final ConversationSummaryService service = new ConversationSummaryService(store, generator);

    private ConversationSummaryService.SourceMessage message(String id, String content) {
        return new ConversationSummaryService.SourceMessage(id, "user", content, "source-" + content);
    }

    @Test
    void savesBoundaryReusesUnchangedPrefixAndOnlySummarizesNewMessages() {
        var first = message("1", "预算1000美元");
        var second = message("2", "需要CE认证");
        assertEquals("updated", service.resolve("t", "s", List.of(first)).status());
        assertEquals("1", store.read("t", "s").lastMessageId());
        assertEquals("ready", service.resolve("t", "s", List.of(first)).status());
        assertEquals(1, generator.calls);
        assertEquals(2, service.resolve("t", "s", List.of(first, second)).coveredCount());
        assertEquals("事实摘要", generator.previous);
        assertEquals(List.of(second), generator.additional);
        assertEquals("2", store.read("t", "s").lastMessageId());
    }

    @Test
    void deletedOrEditedHistoryInvalidatesSummaryInsteadOfReusingOldFacts() {
        var first = message("1", "旧事实");
        var second = message("2", "后续事实");
        service.resolve("t", "s", List.of(first, second));
        generator.fails = true;
        assertNull(service.resolve("t", "s", List.of(second)).text());
        assertNull(service.resolve("t", "s", List.of(message("1", "已修改"), second)).text());
        // 原始附件变了，即使上下文节选相同也必须失效。
        assertNull(service.resolve("t", "s", List.of(new ConversationSummaryService.SourceMessage(
                "1", "user", "旧事实", "changed-attachment"), second)).text());
        generator.fails = false;
        service.resolve("t", "s", List.of(second));
        assertEquals("", generator.previous);
        assertEquals(List.of(second), generator.additional);
    }

    @Test
    void isolatesTenantsAndSessionsWithoutAmbiguousRedisKeys() {
        var history = List.of(message("1", "客户事实"));
        service.resolve("t1", "s", history);
        service.resolve("t2", "s", history);
        service.resolve("t1", "other", history);
        assertEquals(3, generator.calls);
        assertNotEquals(RedisSummaryStore.key("a:b", "c"), RedisSummaryStore.key("a", "b:c"));
    }

    @Test
    void failureRetainsOnlyValidatedCoverageAndDoesNotAdvanceCheckpoint() {
        var first = message("1", "已确认");
        var second = message("2", "新事实");
        service.resolve("t", "s", List.of(first));
        generator.fails = true;
        var result = service.resolve("t", "s", List.of(first, second));
        assertEquals("generation_failed", result.status());
        assertEquals(1, result.coveredCount());
        assertEquals(1, store.read("t", "s").coveredCount());
        generator.fails = false;
        generator.output = " ";
        assertEquals("generation_failed", service.resolve("t", "s", List.of(first, second)).status());
        generator.output = "字".repeat(4001);
        assertEquals("generation_failed", service.resolve("t", "s", List.of(first, second)).status());
    }

    @Test
    void reportsMissingConfigurationStorageAndOversizedInput() {
        var history = List.of(message("1", "事实"));
        assertEquals("not_needed", service.resolve("t", "s", List.of()).status());
        generator.available = false;
        assertEquals("model_unconfigured", service.resolve("t", "s", history).status());
        generator.available = true;
        store.unavailable = true;
        assertEquals("storage_unavailable", service.resolve("t", "s", history).status());
        store.unavailable = false;
        assertEquals("input_too_large", service.resolve("t", "s", List.of(message("1", "字".repeat(24001)))).status());
        assertEquals(0, generator.calls);
    }

    @Test
    void concurrentAndSaveFailuresStillReturnValidCurrentSummaryWithoutOverwriting() {
        var history = List.of(message("1", "事实"));
        store.conflict = true;
        assertEquals("concurrent_update", service.resolve("t", "s", history).status());
        assertNull(store.read("t", "s"));
        store.conflict = false;
        store.saveFails = true;
        var result = service.resolve("t", "s", history);
        assertEquals("save_failed", result.status());
        assertEquals(1, result.coveredCount());
        assertEquals("事实摘要", result.text());
    }
}
