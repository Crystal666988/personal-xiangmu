package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.conversation.ConversationContextManager;
import com.tradeagent.core.dag.IntentRouter;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentContextServiceTest {
    private AgentSession storedSession;
    private final AgentSessionMapper sessions = (AgentSessionMapper) java.lang.reflect.Proxy.newProxyInstance(
            AgentSessionMapper.class.getClassLoader(), new Class<?>[]{AgentSessionMapper.class},
            (proxy, method, args) -> {
                if (method.getName().equals("selectById")) return storedSession;
                throw new UnsupportedOperationException(method.getName());
            });
    private static class HistoryStub extends AgentSessionService {
        List<AgentMessage> messages = List.of();
        int reads;
        @Override public List<AgentMessage> listMessages(String sessionId) { reads++; return messages; }
    }
    private final HistoryStub history = new HistoryStub();
    private ConversationSummary checkpoint;
    private List<ConversationSummaryService.SourceMessage> summarizedSources;
    private final ConversationSummaryService summaries = new ConversationSummaryService(new SummaryStore() {
        @Override public ConversationSummary read(String tenant, String session) { return checkpoint; }
        @Override public boolean saveIfUnchanged(String tenant, String session,
                ConversationSummary expected, ConversationSummary replacement) { checkpoint = replacement; return true; }
    }, new SummaryGenerator() {
        @Override public boolean isAvailable() { return true; }
        @Override public String generate(String previous, List<ConversationSummaryService.SourceMessage> messages) {
            summarizedSources = List.copyOf(messages);
            return "已确认事实与待办";
        }
    });
    private final IntentRouter router = new IntentRouter();
    private final com.tradeagent.modules.ai.tools.ToolRegistry toolRegistry =
            new com.tradeagent.modules.ai.tools.ToolRegistry(List.of(
                    new com.tradeagent.modules.ai.tools.TierPriceCalculatorTool(),
                    new com.tradeagent.modules.ai.tools.FreightCifEstimatorTool(),
                    new com.tradeagent.modules.ai.tools.CurrencyExchangeRateTool()
            ));
    private final com.tradeagent.modules.ai.tools.ToolDispatchService dispatchService =
            new com.tradeagent.modules.ai.tools.ToolDispatchService(toolRegistry, router);
    private final com.tradeagent.core.dag.ReActExecutor reactExecutor =
            new com.tradeagent.core.dag.ReActExecutor(toolRegistry, router);
    private final AgentContextService service = new AgentContextService(sessions, history,
            new ConversationContextManager(), router, new ObjectMapper(), summaries, dispatchService, reactExecutor);

    private void session() {
        storedSession = AgentSession.builder().id("s1").tenantId("t1").build();
    }

    private AgentMessage message(String id, String role, String content) {
        return AgentMessage.builder().id(id).sessionId("s1").role(role).content(content).build();
    }

    @Test
    void rejectsMismatchedTenantBeforeReadingHistory() {
        session();
        assertThrows(ResponseStatusException.class, () -> service.prepare("other", "s1", "current"));
        assertEquals(0, history.reads);
    }

    @Test
    void excludesCurrentAndLaterMessagesAndUsesAttachmentForRouting() {
        session();
        AgentMessage previous = message("old", "user", "旧问题");
        previous.setAttachment("{\"extractedText\":\"历史附件内容\"}");
        AgentMessage current = message("current", "user", "分析并报价");
        current.setAttachment("{\"name\":\"image.png\"}");
        history.messages = List.of(previous,
                message("answer", "assistant", "旧回答"), current,
                message("later", "assistant", "并发后续消息"));
        var result = service.prepare("t1", "s1", "current");
        assertEquals("COMPOSITE", result.routing().domain());
        assertEquals(2, result.historyMessageCount());
        assertEquals(1, result.messages().stream().filter(m -> m.content().equals("分析并报价")).count());
        assertTrue(result.messages().stream().anyMatch(m -> m.content().contains("历史附件内容")));
        assertFalse(result.messages().stream().anyMatch(m -> m.content().contains("并发后续消息")));
    }

    @Test
    void capsDatabaseHistoryAtTwentyAndHandlesMalformedAttachments() {
        session();
        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            var user = message("u" + i, "user", "问题" + i);
            user.setAttachment("malformed");
            messages.add(user);
            messages.add(message("a" + i, "assistant", "回复" + i));
        }
        messages.add(message("current", "user", "你好"));
        history.messages = messages;
        var result = service.prepare("t1", "s1", "current");
        assertEquals(20, result.historyMessageCount());
        assertTrue(result.messages().get(1).content().contains("已确认事实与待办"));
        assertEquals("问题5", result.messages().get(2).content());
        assertEquals("updated", result.summaryStatus());
        assertEquals(10, result.summaryMessageCount());
        assertEquals(0, result.unsummarizedMessageCount());
        assertEquals("a4", checkpoint.lastMessageId());
        assertThrows(ResponseStatusException.class, () -> service.prepare("t1", "s1", "missing"));
        assertThrows(ResponseStatusException.class, () -> service.prepare("t1", "s1", "a1"));
    }

    @Test
    void summaryReceivesFullOldAnswersAndAttachmentsRatherThanWindowExcerpts() {
        session();
        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            var user = message("u" + i, "user", "问题" + i);
            if (i == 0) user.setAttachment("{\"extractedText\":\"" + "文".repeat(900) + "附件末尾关键事实\"}");
            messages.add(user);
            messages.add(message("a" + i, "assistant", i == 0 ? "答".repeat(3000) : "回复" + i));
        }
        messages.add(message("current", "user", "你好"));
        history.messages = messages;
        var result = service.prepare("t1", "s1", "current");
        assertEquals(20, result.historyMessageCount());
        assertTrue(summarizedSources.get(0).content().contains("附件末尾关键事实"));
        assertEquals(3000, summarizedSources.get(1).content().length());
    }
}
