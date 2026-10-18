package com.tradeagent.modules.ai.chat;

import java.util.List;

public interface SummaryGenerator {
    boolean isAvailable();
    String generate(String previousSummary, List<ConversationSummaryService.SourceMessage> messages);
}
