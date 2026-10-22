package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.model.DynamicModelFactory;
import com.tradeagent.core.model.ModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.ResponseFormat;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiRoutingPlanGeneratorTest {
    private static final RoutingModelConfig CONFIG = new RoutingModelConfig(
            "user-secret", "https://example.test/v1", "chosen-model", 42L, "deepseek");

    @Test
    void getsFactoryModelAndSendsSystemAndUserPromptWithJsonOptionsExactlyOnce() {
        var factory = new FakeFactory(response("STOP", new AssistantMessage("{}")));
        var generator = new OpenAiRoutingPlanGenerator(factory);
        assertEquals("{}", generator.generate("Return JSON", "{\"originalQuery\":\"hello\"}", CONFIG));
        assertEquals(1, factory.calls);
        assertEquals(42L, factory.config.getTenantId());
        assertEquals("deepseek", factory.config.getProvider());
        assertEquals("routing", factory.config.getUsageType());
        assertEquals("chosen-model", factory.config.getModelName());
        assertEquals("user-secret", factory.config.getApiKey());
        assertEquals(0.0, factory.config.getTemperature());
        assertEquals(4096, factory.config.getMaxTokens());
        assertInstanceOf(SystemMessage.class, factory.prompt.getInstructions().get(0));
        assertInstanceOf(UserMessage.class, factory.prompt.getInstructions().get(1));
        assertEquals("Return JSON", factory.prompt.getInstructions().get(0).getContent());
        assertEquals("{\"originalQuery\":\"hello\"}", factory.prompt.getInstructions().get(1).getContent());
        var options = (OpenAiChatOptions) factory.prompt.getOptions();
        assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().type());
        assertEquals(0.0f, options.getTemperature());
        assertEquals(4096, options.getMaxTokens());
        assertTrue(options.getTools() == null || options.getTools().isEmpty());
    }

    @Test
    void rejectsMissingTruncatedMultipleOrToolOutputWithoutRetryOrLeakingDetails() {
        for (ChatResponse output : java.util.Arrays.asList(null, new ChatResponse(List.of()),
                response("LENGTH", new AssistantMessage("partial-secret")),
                response("", new AssistantMessage("{}")), response("STOP", new AssistantMessage(" ")),
                response("STOP", new AssistantMessage("{}", Map.of(), List.of(
                        new AssistantMessage.ToolCall("c", "function", "execute", "{}")))),
                response("STOP", new AssistantMessage("{}", Map.of("function_call", "secret"))),
                new ChatResponse(List.of(new Generation("{}"), new Generation("{}"))))) {
            var factory = new FakeFactory(output);
            var error = assertThrows(IllegalStateException.class,
                    () -> new OpenAiRoutingPlanGenerator(factory).generate("JSON", "{}", CONFIG));
            assertEquals(1, factory.calls);
            assertFalse(error.getMessage().contains("secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    void missingTenantOrProviderCannotShareAnAnonymousFactoryEntry() {
        var factory = new FakeFactory(response("STOP", new AssistantMessage("{}")));
        var generator = new OpenAiRoutingPlanGenerator(factory);
        for (var config : java.util.Arrays.asList(null,
                new RoutingModelConfig("key", "https://example.test", "model"),
                new RoutingModelConfig("key", "https://example.test", "model", null, "deepseek"),
                new RoutingModelConfig("key", "https://example.test", "model", 0L, "deepseek"),
                new RoutingModelConfig("key", "https://example.test", "model", 42L, ""))) {
            assertFalse(generator.isAvailable(config));
            assertThrows(IllegalStateException.class, () -> generator.generate("JSON", "{}", config));
        }
        assertEquals(0, factory.calls);
    }

    @Test
    void providerFailureIsSanitizedAndNoRuleOrMockFallbackIsCalled() {
        var factory = new FakeFactory(response("STOP", new AssistantMessage("{}")));
        factory.fail = true;
        var error = assertThrows(IllegalStateException.class,
                () -> new OpenAiRoutingPlanGenerator(factory).generate("JSON", "{}", CONFIG));
        assertEquals(1, factory.calls);
        assertFalse(error.getMessage().contains("user-secret"));
        assertNull(error.getCause());
    }

    private static ChatResponse response(String reason, AssistantMessage output) {
        return new ChatResponse(List.of(new Generation(output, ChatGenerationMetadata.from(reason, null))));
    }

    private static final class FakeFactory extends DynamicModelFactory {
        final ChatResponse output;
        int calls;
        boolean fail;
        ModelConfig config;
        Prompt prompt;
        FakeFactory(ChatResponse output) { this.output = output; }
        @Override public ChatModel getOrCreateChatModel(ModelConfig config) {
            this.config = config;
            return new ChatModel() {
                public ChatResponse call(Prompt input) {
                    calls++; prompt = input;
                    if (fail) throw new IllegalStateException("provider user-secret response");
                    return output;
                }
                public ChatOptions getDefaultOptions() { return null; }
            };
        }
        @Override public ChatModel getModel(Long tenantId) { throw new AssertionError("Never use demo model"); }
    }
}
