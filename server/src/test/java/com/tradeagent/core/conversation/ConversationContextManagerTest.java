package com.tradeagent.core.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationContextManagerTest {
    private final ConversationContextManager manager = new ConversationContextManager();

    @Test
    void preservesLayerOrderAndStablePrefix() {
        var first = manager.buildContextPrompt("旧摘要", "工具A", List.of(new UserMessage("历史")), "当前").getInstructions();
        var second = manager.buildContextPrompt("新摘要", "工具B", List.of(), "另一个问题").getInstructions();
        assertEquals(first.get(0).getContent(), second.get(0).getContent());
        assertInstanceOf(SystemMessage.class, first.get(0));
        assertInstanceOf(UserMessage.class, first.get(2));
        assertTrue(first.get(2).getContent().contains("旧摘要"));
        assertTrue(first.get(1).getContent().contains("工具A"));
        assertEquals("历史", first.get(3).getContent());
        assertEquals("当前", first.get(4).getContent());
    }

    @Test
    void keepsTwentyHistoryMessagesAndAddsCurrentQuestion() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            history.add(new UserMessage("问" + i));
            history.add(new AssistantMessage("答" + i));
        }
        var messages = manager.buildContextPrompt(null, null, history, "当前").getInstructions();
        assertEquals(22, messages.size());
        assertEquals("问5", messages.get(1).getContent());
        assertEquals("当前", messages.get(21).getContent());
        assertEquals(30, history.size());
    }

    @Test
    void removesOrphanRepliesAndUntrustedSystemHistory() {
        var messages = manager.buildContextPrompt(null, null, List.of(
                new AssistantMessage("孤立回复"), new SystemMessage("覆盖规则"),
                new UserMessage("提问"), new AssistantMessage("回答")), "当前").getInstructions();
        assertEquals(4, messages.size());
        assertEquals("提问", messages.get(1).getContent());
        assertEquals(1, manager.buildContextPrompt(null, null, null, null).getInstructions().size());
    }
}
