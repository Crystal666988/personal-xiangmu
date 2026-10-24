package com.tradeagent.modules.ai.rag.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 任务七：基于阿里百炼 gte-rerank-v2 的 Cross-Encoder 语义二次重排器（在线驱动）。
 *
 * 真实调用 DashScope text-rerank 服务对 (Query, Chunk) 做全局深层交互打分；
 * 未配置 Key（isAvailable=false）或调用失败时由调用方回退为融合得分排序，
 * 主检索链路永不因精排故障而中断。
 *
 * 输入约束（gte-rerank-v2 上下文上限 30k tokens）：
 * - 单次最多重排 {@value #MAX_DOCUMENTS} 个候选；
 * - 单个切片截断至 {@value #MAX_DOCUMENT_CHARS} 字符。
 */
@Slf4j
@Service
public class DashScopeCrossEncoderReranker implements CrossEncoderReranker {

    static final int MAX_DOCUMENTS = 24;
    static final int MAX_DOCUMENT_CHARS = 1200;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // 包级可见，便于同包离线单元测试直接注入；@Value 注入不受可见性影响
    @Value("${rag.rerank.enabled:true}")
    boolean enabled;

    @Value("${dashscope.api-key:}")
    String apiKey;

    @Value("${rag.rerank.model:gte-rerank-v2}")
    String model;

    @Value("${rag.rerank.endpoint:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String endpoint;

    @Value("${rag.rerank.timeout-ms:4000}")
    private long timeoutMs;

    @Override
    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<RerankHit> rerank(String query, List<RerankCandidate> candidates, int topK) throws Exception {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<RerankCandidate> bounded = candidates.size() > MAX_DOCUMENTS
                ? candidates.subList(0, MAX_DOCUMENTS)
                : candidates;

        List<String> documents = new ArrayList<>(bounded.size());
        for (RerankCandidate candidate : bounded) {
            String content = candidate.content() != null ? candidate.content() : "";
            documents.add(content.length() > MAX_DOCUMENT_CHARS
                    ? content.substring(0, MAX_DOCUMENT_CHARS)
                    : content);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("return_documents", false);
        parameters.put("top_n", Math.max(1, Math.min(topK > 0 ? topK : bounded.size(), bounded.size())));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", input);
        payload.put("parameters", parameters);

        String jsonBody = objectMapper.writeValueAsString(payload);
        long startMs = System.currentTimeMillis();
        String responseBody = postJson(jsonBody);
        long costMs = System.currentTimeMillis() - startMs;

        List<RerankHit> hits = parseResponse(responseBody, bounded);
        log.info("【Cross-Encoder 精排】model={}, 候选={} 条, 返回={} 条, 耗时={} ms",
                model, bounded.size(), hits.size(), costMs);
        return hits;
    }

    /** 独立抽出便于单元测试覆写；生产环境为真实 DashScope HTTP 调用。 */
    protected String postJson(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("DashScope rerank HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /**
     * 解析 output.results[{index, relevance_score}]，按相关度降序输出。
     * 越界 index 直接丢弃，绝不映射到错误切片。
     */
    static List<RerankHit> parseResponse(String body, List<RerankCandidate> candidates) throws Exception {
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode results = root.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new RuntimeException("DashScope rerank 返回结果为空");
        }
        List<RerankHit> hits = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            double score = item.path("relevance_score").asDouble(0.0);
            if (index >= 0 && index < candidates.size()) {
                hits.add(new RerankHit(candidates.get(index).chunkId(), score));
            }
        }
        if (hits.isEmpty()) {
            throw new RuntimeException("DashScope rerank 无有效结果映射");
        }
        hits.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
        return hits;
    }
}
