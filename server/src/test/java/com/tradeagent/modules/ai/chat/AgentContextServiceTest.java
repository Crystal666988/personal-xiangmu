package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.conversation.ConversationContextManager;
import com.tradeagent.core.dag.IntentRouter;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentContextServiceTest {
    private String modelOutput;
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
    private final com.tradeagent.modules.ai.intent.RoutingWorkflowStore workflowStore = new com.tradeagent.modules.ai.intent.RoutingWorkflowStore() {
        State state;
        final Map<String, com.tradeagent.modules.ai.intent.RoutingWorkflowService.Result> receipts = new HashMap<>();
        public String acquire(String t, String s) { return "token"; }
        public void release(String t, String s, String token) {}
        public State read(String t, String s) { return state; }
        public com.tradeagent.modules.ai.intent.RoutingWorkflowService.Result receipt(String t, String s, String id) { return receipts.get(id); }
        public boolean save(String t, String s, String token, State replacement) { state = replacement; return true; }
        public boolean complete(String t, String s, String token, String id, com.tradeagent.modules.ai.intent.RoutingWorkflowService.Result result, State replacement) {
            state = replacement; receipts.put(id, result); return true;
        }
    };
    private final com.tradeagent.modules.ai.intent.RoutingWorkflowService workflow = new com.tradeagent.modules.ai.intent.RoutingWorkflowService(
            new com.tradeagent.modules.ai.intent.RoutingPreviewService(new com.tradeagent.modules.ai.intent.RoutingPlanGenerator() {
                public boolean isAvailable(com.tradeagent.modules.ai.intent.RoutingModelConfig config) { return modelOutput != null; }
                public String generate(String instructions, String source, com.tradeagent.modules.ai.intent.RoutingModelConfig config) { return modelOutput; }
            }, new com.tradeagent.modules.ai.intent.EvidenceToolCatalog(toolRegistry),
                    new com.tradeagent.modules.ai.intent.StrictEvidencePlanReader(), new ObjectMapper()),
            new com.tradeagent.modules.ai.intent.ValidatedPlanExecutor(toolRegistry), workflowStore);
    private final AgentContextService service = new AgentContextService(sessions, history,
            new ConversationContextManager(), new ObjectMapper(), summaries, workflow, toolRegistry);
    private void session() {
        storedSession = AgentSession.builder().id("s1").tenantId("t1").build();
    }

    private AgentMessage message(String id, String role, String content) {
        return AgentMessage.builder().id(id).sessionId("s1").role(role).content(content).build();
    }

    @Test void actualContextEntryPausesThenExecutesOnlyThePlannedTool() {
        storedSession = AgentSession.builder().id("s1").tenantId("7").build();
        var config = new com.tradeagent.modules.ai.intent.RoutingModelConfig("offline-key", "https://unused.test", "model", 7L, "openai");
        var first = message("u1", "user", "TOPCon580W报价");
        history.messages = List.of(first);
        modelOutput = """
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m"}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon580W","sourceMessageId":"u1"}]}
                """;
        var waiting = service.prepare("7", "s1", "u1", config, null);
        assertEquals("NEEDS_CLARIFICATION", waiting.routingStatus());
        assertFalse(waiting.shouldGenerate()); assertTrue(waiting.pending()); assertEquals(0, waiting.telemetry().getToolsCalledCount());
        var supplement = message("u2", "user", "采购2000片"); history.messages = List.of(first, supplement);
        modelOutput = """
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m","quantity":"q"}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon580W","sourceMessageId":"u1"},
                                   {"id":"q","type":"QUANTITY","evidence":"2000片","sourceMessageId":"u2"}]}
                """;
        var done = service.prepare("7", "s1", "u2", config, "continue");
        assertEquals("EXECUTED", done.routingStatus()); assertTrue(done.shouldGenerate()); assertFalse(done.pending());
        assertEquals(1, done.routing().activeTools().size());
        assertEquals("calculate_tier_price", done.telemetry().getTraces().get(0).getToolId());
        assertEquals(1, done.telemetry().getToolsCalledCount()); assertEquals(2, done.sourceMessages().size());
        assertTrue(done.messages().stream().anyMatch(m -> m.content().contains("DEMO/isLive=false")));
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
        assertEquals("GENERAL", result.routing().domain());
        assertEquals("MODEL_UNAVAILABLE", result.routingStatus());
        assertFalse(result.shouldGenerate());
        assertEquals(0, result.telemetry().getToolsCalledCount());
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
        assertTrue(result.messages().get(2).content().contains("已确认事实与待办"));
        assertEquals("问题5", result.messages().get(3).content());
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
