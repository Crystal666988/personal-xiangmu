package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ModelSummaryGeneratorTest {
    @Test
    void sendsIncrementalHistoryAndUsesDedicatedSummaryCredentials() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"合并摘要\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var generator = new ModelSummaryGenerator(json, "test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "summary-test-model");
            assertEquals("合并摘要", generator.generate("已有摘要", List.of(
                    new ConversationSummaryService.SourceMessage("m1", "user", "新增事实", "digest"))));
            var request = json.readTree(body.get());
            assertEquals("Bearer test-key", auth.get());
            assertEquals("summary-test-model", request.path("model").asText());
            assertFalse(request.path("stream").asBoolean());
            var source = json.readTree(request.path("messages").path(1).path("content").asText());
            assertEquals("已有摘要", source.path("previousSummary").asText());
            assertEquals("新增事实", source.path("newMessages").path(0).path("content").asText());
            assertFalse(source.path("newMessages").path(0).has("sourceDigest"));
        } finally { server.stop(0); }
    }

    @Test
    void rejectsTruncatedOutputRatherThanSavingIncompleteSummary() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"截断摘要\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var generator = new ModelSummaryGenerator(new ObjectMapper(), "test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions", "summary-test");
            assertThrows(IllegalStateException.class, () -> generator.generate("", List.of()));
            assertFalse(new ModelSummaryGenerator(new ObjectMapper(), "", "http://localhost", "test").isAvailable());
        } finally { server.stop(0); }
    }
}
