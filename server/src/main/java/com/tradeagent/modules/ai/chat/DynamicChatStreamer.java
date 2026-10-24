package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.model.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 真实 OpenAI 兼容流式客户端（自研 SSE 解析）。
 *
 * 选择自研解析而非 Spring AI 1.0.0-M2 的原因：M2 的 ChatCompletionMessage DTO
 * 不含 delta.reasoning_content 字段，推理模型（deepseek-reasoner / qwen3 等）的
 * 思考链会在 DTO 层被静默丢弃；自研解析让思考链与正文分双路流出。
 *
 * 断连止损：客户端 SSE 断开时 Flux 取消，工作线程感知后中断上游 HTTP 流，停止消耗 Token。
 */
@Slf4j
@Component
public class DynamicChatStreamer implements ChatStreamer {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public Flux<ChatStreamPiece> stream(List<Message> messages, ModelConfig config) {
        String endpoint = config.getBaseUrl().replaceAll("/$", "") + "/chat/completions";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModelName());
        List<Map<String, String>> bodyMessages = new ArrayList<>();
        for (Message message : messages) {
            bodyMessages.add(Map.of(
                    "role", roleOf(message),
                    "content", Objects.toString(message.getContent(), "")));
        }
        payload.put("messages", bodyMessages);
        payload.put("stream", true);
        if (config.getTemperature() != null) payload.put("temperature", config.getTemperature());
        if (config.getMaxTokens() != null) payload.put("max_tokens", config.getMaxTokens());

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return Flux.error(new IllegalStateException("请求体序列化失败: " + e.getMessage()));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey().trim())
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        return Flux.create(sink -> {
            Thread worker = new Thread(() -> {
                try {
                    HttpResponse<java.util.stream.Stream<String>> response =
                            httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
                    if (response.statusCode() != 200) {
                        String bodyText = response.body() == null ? "" : response.body()
                                .limit(40).reduce("", (a, b) -> a + b);
                        sink.error(new RuntimeException("模型端点 HTTP " + response.statusCode() + ": " + bodyText));
                        return;
                    }
                    response.body().forEach(line -> {
                        if (sink.isCancelled()) return;
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith(":")) return;
                        if (!trimmed.startsWith("data:")) return;
                        String data = trimmed.substring(5).trim();
                        if ("[DONE]".equals(data)) {
                            sink.complete();
                            return;
                        }
                        try {
                            JsonNode delta = objectMapper.readTree(data)
                                    .path("choices").path(0).path("delta");
                            String reasoning = delta.path("reasoning_content").asText("");
                            if (!reasoning.isEmpty()) sink.next(ChatStreamPiece.thought(reasoning));
                            String content = delta.path("content").asText("");
                            if (!content.isEmpty()) sink.next(ChatStreamPiece.content(content));
                        } catch (Exception ignored) {
                            // 单个坏块直接忽略，不中断整轮流
                        }
                    });
                    sink.complete();
                } catch (Exception e) {
                    if (!sink.isCancelled()) sink.error(e);
                }
            }, "sse-model-stream");
            worker.setDaemon(true);
            worker.start();
            sink.onCancel(worker::interrupt);
        });
    }

    private static String roleOf(Message message) {
        if (message instanceof org.springframework.ai.chat.messages.SystemMessage) return "system";
        if (message instanceof org.springframework.ai.chat.messages.UserMessage) return "user";
        return "assistant";
    }
}
