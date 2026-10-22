package com.tradeagent.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.intent.OpenAiRoutingPlanGenerator;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicModelFactoryTest {
    private static ModelConfig config() {
        return ModelConfig.builder().tenantId(42L).usageType("routing").provider("deepseek")
                .baseUrl("https://example.test/v1").apiKey("first-secret").modelName("chosen-model")
                .temperature(0.0).maxTokens(4096).build();
    }

    @Test
    void fingerprintsEveryConfigurationFieldWithoutExposingKeyAndRejectsAnonymousIdentity() {
        var base = config();
        String fingerprint = base.getCacheKey();
        assertEquals(fingerprint, base.toBuilder().build().getCacheKey());
        assertTrue(fingerprint.matches("42:routing:[0-9a-f]{64}"));
        assertFalse(fingerprint.contains("first-secret"));
        assertFalse(base.toString().contains("first-secret"));
        for (var changed : List.of(base.toBuilder().apiKey("second-secret").build(),
                base.toBuilder().baseUrl("https://different.test/v1").build(),
                base.toBuilder().temperature(0.1).build(), base.toBuilder().maxTokens(1024).build(),
                base.toBuilder().tenantId(43L).build(), base.toBuilder().usageType("chat").build(),
                base.toBuilder().provider("openai").build(), base.toBuilder().modelName("another-model").build())) {
            assertNotEquals(fingerprint, changed.getCacheKey());
        }
        assertThrows(IllegalArgumentException.class, () -> base.toBuilder().tenantId(null).build().getCacheKey());
        assertThrows(IllegalArgumentException.class, () -> base.toBuilder().usageType("routing:extra").build().getCacheKey());
    }

    @Test
    void factoryReusesSnapshotsAndSeparatesKeyUrlModelTenantProviderAndOptions() {
        try (var context = context()) {
            var factory = new CountingFactory(); factory.setApplicationContext(context);
            var base = config();
            var first = factory.getOrCreateChatModel(base);
            assertSame(first, factory.getOrCreateChatModel(base.toBuilder().build()));
            assertSame(first, factory.getOrCreateChatModel(base.toBuilder()
                    .baseUrl("https://example.test/v1/chat/completions/").build()));
            for (var changed : List.of(base.toBuilder().apiKey("second-secret").build(),
                    base.toBuilder().baseUrl("https://other.test/v1").build(),
                    base.toBuilder().tenantId(43L).build(), base.toBuilder().provider("openai").build(),
                    base.toBuilder().modelName("other-model").build(), base.toBuilder().temperature(0.2).build(),
                    base.toBuilder().maxTokens(2048).build())) {
                assertNotSame(first, factory.getOrCreateChatModel(changed));
            }
            assertEquals(8, factory.created.size());
            base.setApiKey("mutated-key");
            assertEquals("first-secret", factory.created.get(0).getApiKey());
            for (String name : context.getBeanFactory().getSingletonNames()) {
                if (name.startsWith("dynamicChatModel_")) assertTrue(name.matches("dynamicChatModel_[0-9a-f]{64}"));
            }
        }
    }

    @Test
    void invalidationRemovesSpringBeansAndUsesExactTenantAndUsageBoundaries() {
        try (var context = context()) {
            var factory = new CountingFactory(); factory.setApplicationContext(context);
            var routing = factory.getOrCreateChatModel(config());
            var extended = factory.getOrCreateChatModel(config().toBuilder().usageType("routing-extra").build());
            var otherTenant = factory.getOrCreateChatModel(config().toBuilder().tenantId(420L).build());
            assertEquals(3, dynamicBeans(context));
            factory.evictTenantModel(42L, "routing");
            assertEquals(2, dynamicBeans(context));
            assertSame(extended, factory.getOrCreateChatModel(config().toBuilder().usageType("routing-extra").build()));
            assertSame(otherTenant, factory.getOrCreateChatModel(config().toBuilder().tenantId(420L).build()));
            assertNotSame(routing, factory.getOrCreateChatModel(config()));
            factory.evictTenantModel(42L);
            assertEquals(1, dynamicBeans(context));
        }
    }

    @Test
    void actualSpringAiRequestUsesCorrectPathsHeadersAndJsonModeAndReusesClientModel() throws Exception {
        try (var context = context()) {
            var wire = new InMemoryRequests();
            var factory = new WireFactory(wire); factory.setApplicationContext(context);
            var generator = new OpenAiRoutingPlanGenerator(factory);
            assertEquals("{}", generator.generate("Return JSON", "{\"originalQuery\":\"hello\"}", request("https://example.test/v1/")));
            assertEquals("{}", generator.generate("Return JSON", "{}", request("https://example.test/v1/chat/completions")));
            assertEquals(1, factory.constructions);
            assertEquals(2, wire.sent.size());
            for (var sent : wire.sent) {
                assertEquals("https://example.test/v1/chat/completions", sent.uri.toString());
                assertEquals("Bearer user-secret", sent.headers.getFirst("Authorization"));
                var body = new ObjectMapper().readTree(sent.body.toByteArray());
                assertEquals("chosen-model", body.path("model").textValue());
                assertEquals(0, body.path("temperature").intValue());
                assertEquals(4096, body.path("max_tokens").intValue());
                assertEquals("json_object", body.path("response_format").path("type").textValue());
                assertFalse(body.path("stream").asBoolean());
                assertFalse(body.has("tools")); assertFalse(body.has("tool_choice"));
                assertEquals("system", body.path("messages").path(0).path("role").textValue());
                assertEquals("user", body.path("messages").path(1).path("role").textValue());
            }
            generator.generate("JSON", "{}", request("https://example.test/compatible-mode/v1"));
            assertEquals("https://example.test/compatible-mode/v1/chat/completions", wire.sent.get(2).uri.toString());
        }
    }

    @Test
    void actualSpringAiErrorsAndUnsolicitedToolsNeverRetryOrExecuteCallbacks() {
        for (boolean toolCall : List.of(false, true)) try (var context = context()) {
            var wire = new InMemoryRequests();
            if (toolCall) wire.response = """
                    {"id":"test","object":"chat.completion","created":1,"model":"chosen-model",
                     "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
                     "content":"{}","tool_calls":[{"id":"c","type":"function",
                     "function":{"name":"never_execute","arguments":"{}"}}]}}]}
                    """;
            else { wire.status = HttpStatus.INTERNAL_SERVER_ERROR; wire.response = "provider-secret-error"; }
            var factory = new WireFactory(wire); factory.setApplicationContext(context);
            var error = assertThrows(IllegalStateException.class,
                    () -> new OpenAiRoutingPlanGenerator(factory).generate("JSON", "{}", request("https://example.test/v1")));
            assertEquals(1, wire.sent.size());
            assertEquals("Routing model is unavailable", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void routingTransportUsesThreeSecondConnectionAndTwelveSecondReadTimeouts() throws Exception {
        var requests = new DynamicModelFactory().routingRequestFactory();
        for (var entry : java.util.Map.of("connectTimeout", 3000, "readTimeout", 12000).entrySet()) {
            var field = requests.getClass().getDeclaredField(entry.getKey()); field.setAccessible(true);
            assertEquals(entry.getValue(), field.get(requests));
        }
        assertEquals("https://example.test/v1", DynamicModelFactory.normalizeBaseUrl("https://example.test"));
        assertEquals("https://example.test", DynamicModelFactory.normalizeBaseUrl("https://example.test/chat/completions"));
        assertThrows(IllegalArgumentException.class,
                () -> DynamicModelFactory.normalizeBaseUrl("https://secret@example.test/v1"));
    }

    private static RoutingModelConfig request(String base) {
        return new RoutingModelConfig("user-secret", base, "chosen-model", 42L, "deepseek");
    }
    private static GenericApplicationContext context() {
        var context = new GenericApplicationContext(); context.refresh(); return context;
    }
    private static long dynamicBeans(GenericApplicationContext context) {
        return java.util.Arrays.stream(context.getBeanFactory().getSingletonNames())
                .filter(name -> name.startsWith("dynamicChatModel_")).count();
    }
    private static final class CountingFactory extends DynamicModelFactory {
        final List<ModelConfig> created = new ArrayList<>();
        @Override protected ChatModel createChatModel(ModelConfig config) {
            created.add(config);
            return new ChatModel() {
                public ChatResponse call(Prompt prompt) { return new ChatResponse(List.of(new Generation("{}"))); }
                public ChatOptions getDefaultOptions() { return null; }
            };
        }
    }
    private static final class WireFactory extends DynamicModelFactory {
        final InMemoryRequests wire; int constructions;
        WireFactory(InMemoryRequests wire) { this.wire = wire; }
        @Override protected ClientHttpRequestFactory routingRequestFactory() { constructions++; return wire; }
    }
    private static final class InMemoryRequests implements ClientHttpRequestFactory {
        final List<Sent> sent = new ArrayList<>();
        HttpStatus status = HttpStatus.OK;
        String response = """
                {"id":"test","object":"chat.completion","created":1,"model":"chosen-model",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{}"}}]}
                """;
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            var request = new Sent(uri);
            return new ClientHttpRequest() {
                public HttpMethod getMethod() { return method; }
                public URI getURI() { return uri; }
                public HttpHeaders getHeaders() { return request.headers; }
                public OutputStream getBody() { return request.body; }
                public ClientHttpResponse execute() {
                    sent.add(request);
                    return new ClientHttpResponse() {
                        public HttpStatusCode getStatusCode() { return status; }
                        public String getStatusText() { return status.getReasonPhrase(); }
                        public HttpHeaders getHeaders() {
                            var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); return headers;
                        }
                        public InputStream getBody() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
                        public void close() {}
                    };
                }
            };
        }
    }
    private static final class Sent {
        final URI uri; final HttpHeaders headers = new HttpHeaders(); final ByteArrayOutputStream body = new ByteArrayOutputStream();
        Sent(URI uri) { this.uri = uri; }
    }
}
