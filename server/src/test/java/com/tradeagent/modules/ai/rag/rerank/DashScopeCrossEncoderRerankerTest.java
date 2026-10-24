package com.tradeagent.modules.ai.rag.rerank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务七单元测试：DashScope Cross-Encoder 重排器的可用性判定、请求契约与响应解析。
 * 全部离线执行（postJson 被覆写），不产生任何真实网络调用。
 */
class DashScopeCrossEncoderRerankerTest {

    private DashScopeCrossEncoderReranker reranker(String apiKey) {
        DashScopeCrossEncoderReranker reranker = new DashScopeCrossEncoderReranker();
        reranker.apiKey = apiKey;
        reranker.enabled = true;
        reranker.model = "gte-rerank-v2";
        return reranker;
    }

    @Test
    @DisplayName("未配置 Key 时 isAvailable=false，主链路应直接跳过精排")
    void unavailableWithoutKey() {
        assertFalse(reranker("").isAvailable());
        assertFalse(reranker("   ").isAvailable());
        assertTrue(reranker("sk-test-key").isAvailable());

        DashScopeCrossEncoderReranker disabled = reranker("sk-test-key");
        disabled.enabled = false;
        assertFalse(disabled.isAvailable());
    }

    @Test
    @DisplayName("解析 gte-rerank-v2 响应：index 映射回切片 ID 并按相关度降序")
    void parseResponseMapsIndexAndSortsByRelevance() throws Exception {
        List<RerankCandidate> candidates = List.of(
                new RerankCandidate("c0", "切片零", 0.9),
                new RerankCandidate("c1", "切片一", 0.5),
                new RerankCandidate("c2", "切片二", 0.7));

        String body = """
                {"output":{"results":[
                    {"index":2,"relevance_score":0.95},
                    {"index":0,"relevance_score":0.10},
                    {"index":1,"relevance_score":0.80}]},
                 "usage":{"total_tokens":128}}
                """;

        List<RerankHit> hits = DashScopeCrossEncoderReranker.parseResponse(body, candidates);

        assertEquals(List.of("c2", "c1", "c0"), hits.stream().map(RerankHit::chunkId).toList());
        assertEquals(0.95, hits.get(0).relevanceScore(), 1e-9);
        assertEquals(0.80, hits.get(1).relevanceScore(), 1e-9);
        assertEquals(0.10, hits.get(2).relevanceScore(), 1e-9);
    }

    @Test
    @DisplayName("越界 index 被丢弃，绝不映射到错误切片")
    void parseResponseDropsOutOfRangeIndex() throws Exception {
        List<RerankCandidate> candidates = List.of(new RerankCandidate("c0", "切片零", 0.9));
        String body = """
                {"output":{"results":[
                    {"index":0,"relevance_score":0.9},
                    {"index":5,"relevance_score":0.99}]}}
                """;
        List<RerankHit> hits = DashScopeCrossEncoderReranker.parseResponse(body, candidates);
        assertEquals(1, hits.size());
        assertEquals("c0", hits.get(0).chunkId());
    }

    @Test
    @DisplayName("请求契约：model/input.query/documents/top_n 按 DashScope text-rerank 规范组装")
    void rerankBuildsDashScopeContractPayload() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        DashScopeCrossEncoderReranker reranker = new DashScopeCrossEncoderReranker() {
            @Override
            protected String postJson(String jsonBody) {
                captured.set(jsonBody);
                return """
                        {"output":{"results":[{"index":1,"relevance_score":0.99},{"index":0,"relevance_score":0.42}]}}
                        """;
            }
        };
        reranker.apiKey = "sk-test-key";
        reranker.model = "gte-rerank-v2";

        List<RerankCandidate> candidates = List.of(
                new RerankCandidate("c0", "TOPCon 首年衰减率不超过 1%", 0.61),
                new RerankCandidate("c1", "SASO 认证清单", 0.58));
        List<RerankHit> hits = reranker.rerank("TOPCon 580W 首年衰减率是多少？", candidates, 1);

        String payload = captured.get();
        assertTrue(payload.contains("\"model\":\"gte-rerank-v2\""));
        assertTrue(payload.contains("\"query\":\"TOPCon 580W 首年衰减率是多少？\""));
        assertTrue(payload.contains("\"return_documents\":false"));
        assertTrue(payload.contains("\"top_n\":1"));
        assertTrue(payload.contains("TOPCon 首年衰减率不超过 1%"));

        assertEquals(2, hits.size());
        assertEquals("c1", hits.get(0).chunkId());
        assertEquals(0.99, hits.get(0).relevanceScore(), 1e-9);
    }
}
