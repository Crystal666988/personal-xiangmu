package com.tradeagent.modules.ai.chat;

/** 可从 MySQL 原始消息重建的摘要检查点，不替代原始消息。 */
public record ConversationSummary(int version, String text, int coveredCount,
                                  String lastMessageId, String sourceHash) {
    public static final int VERSION = 1;
}
