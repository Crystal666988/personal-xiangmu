package com.tradeagent.modules.ai.chat;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ConversationSummaryService {
    // 字符限制不是 Token 精算；本步先限制单次摘要请求，模型预算管理另行实现。
    private static final int MAX_INPUT_CHARS = 24000;
    private static final int MAX_SUMMARY_CHARS = 4000;
    private final SummaryStore store;
    private final SummaryGenerator generator;

    public ConversationSummaryService(SummaryStore store, SummaryGenerator generator) {
        this.store = store;
        this.generator = generator;
    }

    public record SourceMessage(String id, String role, String content, String sourceDigest) {}
    public record Result(String text, String status, int coveredCount) {}

    public Result resolve(String tenantId, String sessionId, List<SourceMessage> older) {
        if (older.isEmpty()) return new Result(null, "not_needed", 0);
        ConversationSummary expected;
        try {
            expected = store.read(tenantId, sessionId);
        } catch (RuntimeException e) {
            return new Result(null, "storage_unavailable", 0);
        }
        // 删除、修改、重排或旧版本都会令指纹不匹配。失效摘要绝不能用于当前请求。
        ConversationSummary valid = matches(expected, older) ? expected : null;
        if (valid != null && valid.coveredCount() == older.size()) {
            return new Result(valid.text(), "ready", valid.coveredCount());
        }
        if (!generator.isAvailable()) return fallback(valid, "model_unconfigured");
        int covered = valid == null ? 0 : valid.coveredCount();
        List<SourceMessage> additional = older.subList(covered, older.size());
        String previous = valid == null ? "" : valid.text();
        long inputChars = previous.length() + additional.stream().mapToLong(m -> m.content().length()).sum();
        if (inputChars > MAX_INPUT_CHARS) return fallback(valid, "input_too_large");
        String text;
        try {
            text = generator.generate(previous, additional);
            if (text == null || text.isBlank() || text.length() > MAX_SUMMARY_CHARS) {
                return fallback(valid, "generation_failed");
            }
        } catch (RuntimeException e) {
            return fallback(valid, "generation_failed");
        }
        ConversationSummary updated = new ConversationSummary(ConversationSummary.VERSION, text,
                older.size(), older.get(older.size() - 1).id(), fingerprint(older));
        try {
            // CAS失败说明其他请求已更新Redis；本次生成仍基于本次快照，可用于本轮但不覆盖它。
            boolean saved = store.saveIfUnchanged(tenantId, sessionId, expected, updated);
            return new Result(text, saved ? "updated" : "concurrent_update", older.size());
        } catch (RuntimeException e) {
            return new Result(text, "save_failed", older.size());
        }
    }

    private boolean matches(ConversationSummary summary, List<SourceMessage> older) {
        if (summary == null || summary.version() != ConversationSummary.VERSION
                || summary.text() == null || summary.text().isBlank() || summary.text().length() > MAX_SUMMARY_CHARS
                || summary.coveredCount() < 1 || summary.coveredCount() > older.size()) return false;
        List<SourceMessage> prefix = older.subList(0, summary.coveredCount());
        return Objects.equals(summary.lastMessageId(), prefix.get(prefix.size() - 1).id())
                && Objects.equals(summary.sourceHash(), fingerprint(prefix));
    }

    private Result fallback(ConversationSummary valid, String status) {
        return new Result(valid == null ? null : valid.text(), status, valid == null ? 0 : valid.coveredCount());
    }

    static String fingerprint(List<SourceMessage> messages) {
        StringBuilder source = new StringBuilder();
        for (SourceMessage m : messages) {
            for (String part : List.of(m.id(), m.role(), m.content(), m.sourceDigest())) {
                source.append(part.length()).append(':').append(part);
            }
        }
        return RedisSummaryStore.hash(source.toString());
    }
}
