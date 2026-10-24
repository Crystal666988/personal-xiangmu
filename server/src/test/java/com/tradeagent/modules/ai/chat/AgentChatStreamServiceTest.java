package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.conversation.ConversationContextManager;
import com.tradeagent.core.model.ModelConfig;
import com.tradeagent.modules.ai.intent.EvidenceToolCatalog;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import com.tradeagent.modules.ai.modelconfig.TenantModelConfigService;
import com.tradeagent.modules.ai.intent.RoutingPlanGenerator;
import com.tradeagent.modules.ai.intent.RoutingPreviewService;
import com.tradeagent.modules.ai.intent.RoutingWorkflowService;
import com.tradeagent.modules.ai.intent.RoutingWorkflowStore;
import com.tradeagent.modules.ai.intent.StrictEvidencePlanReader;
import com.tradeagent.modules.ai.intent.ValidatedPlanExecutor;
import com.tradeagent.modules.ai.tools.CurrencyExchangeRateTool;
import com.tradeagent.modules.ai.tools.FreightCifEstimatorTool;
import com.tradeagent.modules.ai.tools.TierPriceCalculatorTool;
import com.tradeagent.modules.ai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后端 SSE 自动回复闭环离线测试：真实 prepare 管线（路由/预检/只读工具执行）+ 假流式模型，
 * 验证 meta/delta/done 事件协议、追问直答、错误终态与助手回复幂等落库。零网络依赖。
 */
class AgentChatStreamServiceTest {

    private String modelOutput;
    private AgentSession storedSession;

    private final AgentSessionMapper sessions = (AgentSessionMapper) java.lang.reflect.Proxy.newProxyInstance(
            AgentSessionMapper.class.getClassLoader(), new Class<?>[]{AgentSessionMapper.class},
            (proxy, method, args) -> {
                if (method.getName().equals("selectById")) return storedSession;
                throw new UnsupportedOperationException(method.getName());
            });

    static class SessionServiceStub extends AgentSessionService {
        List<AgentMessage> messages = List.of();
        final List<AgentMessage> saved = new ArrayList<>();

        @Override
        public List<AgentMessage> listMessages(String sessionId) {
            return messages;
        }

        @Override
        public AgentMessage saveMessage(AgentMessage message) {
            if (message.getId() == null || message.getId().isBlank()) {
                message.setId("assistant_" + (saved.size() + 1));
            }
            saved.add(message);
            return message;
        }
    }

    private final SessionServiceStub history = new SessionServiceStub();

    private final ConversationSummaryService summaries = new ConversationSummaryService(new SummaryStore() {
        @Override public ConversationSummary read(String tenant, String session) { return null; }
        @Override public boolean saveIfUnchanged(String tenant, String session,
                                                 ConversationSummary expected, ConversationSummary replacement) {
            return true;
        }
    }, new SummaryGenerator() {
        @Override public boolean isAvailable() { return false; }
        @Override public String generate(String previous, List<ConversationSummaryService.SourceMessage> messages) {
            return "";
        }
    });

    private final ToolRegistry toolRegistry = new ToolRegistry(List.of(
            new TierPriceCalculatorTool(), new FreightCifEstimatorTool(), new CurrencyExchangeRateTool()));

    private final RoutingWorkflowStore workflowStore = new RoutingWorkflowStore() {
        public String acquire(String t, String s) { return "token"; }
        public void release(String t, String s, String token) { }
        public RoutingWorkflowStore.State read(String t, String s) { return null; }
        public RoutingWorkflowService.Result receipt(String t, String s, String id) { return null; }
        public boolean save(String t, String s, String token, State replacement) { return true; }
        public boolean complete(String t, String s, String token, String id,
                                RoutingWorkflowService.Result result, State replacement) { return true; }
    };

    private final RoutingWorkflowService workflow = new RoutingWorkflowService(
            new RoutingPreviewService(new RoutingPlanGenerator() {
                @Override public boolean isAvailable(RoutingModelConfig config) { return modelOutput != null; }
                @Override public String generate(String instructions, String source, RoutingModelConfig config) {
                    return modelOutput;
                }
            }, new EvidenceToolCatalog(toolRegistry), new StrictEvidencePlanReader(), new ObjectMapper()),
            new ValidatedPlanExecutor(toolRegistry), workflowStore);

    private final AgentContextService contextService = new AgentContextService(sessions, history,
            new ConversationContextManager(), new ObjectMapper(), summaries, workflow, toolRegistry);

    // 租户配置服务桩：默认未配置任何生效模型（离线路径）
    private final TenantModelConfigService modelConfigs = new TenantModelConfigService(null) {
        @Override public java.util.Optional<RoutingModelConfig> resolveActive(String tenantId) {
            return java.util.Optional.empty();
        }
    };

    static class FakeStreamer implements ChatStreamer {
        final List<List<Message>> prompts = new ArrayList<>();
        ModelConfig lastConfig;
        final Deque<Flux<String>> scripted = new ArrayDeque<>();

        @Override
        public Flux<String> stream(List<Message> messages, ModelConfig config) {
            prompts.add(List.copyOf(messages));
            lastConfig = config;
            return scripted.removeFirst();
        }
    }

    private AgentMessage user(String id, String content) {
        return AgentMessage.builder().id(id).sessionId("s1").role("user").content(content).build();
    }

    private AgentChatStreamService service(FakeStreamer streamer) {
        return new AgentChatStreamService(contextService, history, streamer, modelConfigs, toolRegistry, new ObjectMapper());
    }

    private List<ServerSentEvent<String>> collect(AgentChatStreamService service, RoutingModelConfig config) {
        return service.stream("7", "s1", "u1", config).collectList().block(Duration.ofSeconds(10));
    }

    @Test
    void executedPathStreamsDeltasAndPersistsAssistantReply() {
        storedSession = AgentSession.builder().id("s1").tenantId("7").build();
        var first = user("u1", "请报价TOPCon580W，采购2,000片");
        history.messages = List.of(first);
        modelOutput = """
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m","quantity":"q"}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon580W","sourceMessageId":"u1"},
                                   {"id":"q","type":"QUANTITY","evidence":"2,000片","sourceMessageId":"u1"}]}
                """;

        FakeStreamer streamer = new FakeStreamer();
        streamer.scripted.add(Flux.just("该型号", "报价已核算", "完成。"));
        var config = new RoutingModelConfig("offline-key", "https://unused.test", "model", 7L, "openai");

        List<ServerSentEvent<String>> events = collect(service(streamer), config);

        assertEquals("phase", events.get(0).event());
        assertEquals("meta", events.get(1).event());
        assertTrue(events.get(1).data().contains("EXECUTED"));
        assertEquals(List.of("delta", "delta", "delta"),
                events.stream().skip(2).limit(3).map(ServerSentEvent::event).toList());
        assertEquals("done", events.get(events.size() - 1).event());

        // 助手回复已幂等落库且带路由元数据
        assertEquals(1, history.saved.size());
        AgentMessage saved = history.saved.get(0);
        assertEquals("assistant", saved.getRole());
        assertEquals("该型号报价已核算完成。", saved.getContent());
        assertTrue(saved.getRouting() != null && saved.getRouting().contains("EXECUTED"),
                "routing=" + saved.getRouting());
        assertTrue(saved.getThought().contains("calculate_tier_price"));

        // 服务端 Prompt：ZONE1 注入了工具执行事实，末尾是当前用户问题
        List<Message> prompt = streamer.prompts.get(0);
        assertInstanceOf(SystemMessage.class, prompt.get(0));
        assertTrue(((SystemMessage) prompt.get(0)).getContent().contains("Executed tool telemetry"));
        assertTrue(((SystemMessage) prompt.get(0)).getContent().contains("DEMO"));
        assertInstanceOf(UserMessage.class, prompt.get(prompt.size() - 1));

        assertEquals("offline-key", streamer.lastConfig.getApiKey());
        assertEquals(7L, streamer.lastConfig.getTenantId());
    }

    @Test
    void clarificationPathRepliesDirectiveWithoutCallingModel() {
        storedSession = AgentSession.builder().id("s1").tenantId("7").build();
        history.messages = List.of(user("u1", "请报价TOPCon580W"));
        modelOutput = """
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m"}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon580W","sourceMessageId":"u1"}]}
                """;

        FakeStreamer streamer = new FakeStreamer();
        var config = new RoutingModelConfig("offline-key", "https://unused.test", "model", 7L, "openai");

        List<ServerSentEvent<String>> events = collect(service(streamer), config);

        assertEquals("phase", events.get(0).event());
        assertEquals("meta", events.get(1).event());
        assertTrue(events.get(1).data().contains("NEEDS_CLARIFICATION"));
        assertEquals("delta", events.get(2).event());
        assertFalse(events.get(2).data().isBlank());
        assertEquals("done", events.get(events.size() - 1).event());
        assertTrue(events.get(events.size() - 1).data().contains("\"saved\":true"));

        // 不调用大模型；后端裁决话术作为自动回复落库
        assertTrue(streamer.prompts.isEmpty());
        assertEquals(1, history.saved.size());
        assertEquals("assistant", history.saved.get(0).getRole());
        assertFalse(history.saved.get(0).getContent().isBlank());
    }

    @Test
    void modelErrorEmitsErrorThenDoneAndPersistsPartialContent() {
        storedSession = AgentSession.builder().id("s1").tenantId("7").build();
        history.messages = List.of(user("u1", "请报价TOPCon580W，采购2,000片"));
        modelOutput = """
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m","quantity":"q"}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon580W","sourceMessageId":"u1"},
                                   {"id":"q","type":"QUANTITY","evidence":"2,000片","sourceMessageId":"u1"}]}
                """;

        FakeStreamer streamer = new FakeStreamer();
        streamer.scripted.add(Flux.just("部分回答").concatWith(Flux.error(new RuntimeException("boom"))));
        var config = new RoutingModelConfig("offline-key", "https://unused.test", "model", 7L, "openai");

        List<ServerSentEvent<String>> events = collect(service(streamer), config);

        assertEquals("meta", events.get(1).event());
        assertEquals("delta", events.get(2).event());
        assertEquals("error", events.get(3).event());
        assertEquals("done", events.get(events.size() - 1).event());
        assertEquals(1, history.saved.size());
        assertEquals("部分回答", history.saved.get(0).getContent());
    }

    @Test
    void missingModelConfigFailsFastWithTerminalDone() {
        storedSession = AgentSession.builder().id("s1").tenantId("7").build();
        history.messages = List.of(user("u1", "请报价TOPCon580W，采购2,000片"));
        modelOutput = """
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m","quantity":"q"}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon580W","sourceMessageId":"u1"},
                                   {"id":"q","type":"QUANTITY","evidence":"2,000片","sourceMessageId":"u1"}]}
                """;

        FakeStreamer streamer = new FakeStreamer();
        List<ServerSentEvent<String>> events = collect(service(streamer), null);

        assertEquals("phase", events.get(0).event());
        assertEquals("error", events.get(1).event());
        assertEquals("done", events.get(events.size() - 1).event());
        assertTrue(events.get(events.size() - 1).data().contains("\"saved\":false"));
        assertTrue(streamer.prompts.isEmpty());
        assertTrue(history.saved.isEmpty());
    }
}
