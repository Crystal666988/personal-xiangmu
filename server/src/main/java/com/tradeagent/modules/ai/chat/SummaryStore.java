package com.tradeagent.modules.ai.chat;

public interface SummaryStore {
    ConversationSummary read(String tenantId, String sessionId);

    /** 乐观并发更新，防止较慢的旧请求覆盖另一个请求刚保存的新摘要。 */
    boolean saveIfUnchanged(String tenantId, String sessionId,
                            ConversationSummary expected, ConversationSummary replacement);
}
