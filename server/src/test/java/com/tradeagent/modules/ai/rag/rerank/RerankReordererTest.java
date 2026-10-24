package com.tradeagent.modules.ai.rag.rerank;

import com.tradeagent.modules.ai.rag.kb.KnowledgeBaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务七单元测试：重排只改顺序与 rerankScore，融合分与门禁语义保持不变。
 */
class RerankReordererTest {

    private KnowledgeBaseService.RetrievalCitation citation(String chunkId, double fusedScore) {
        return KnowledgeBaseService.RetrievalCitation.builder()
                .chunkId(chunkId)
                .score(fusedScore)
                .content("内容-" + chunkId)
                .build();
    }

    @Test
    @DisplayName("按 rerank 相关度降序重排，融合分保持原值")
    void reordersByRelevanceAndKeepsFusedScore() {
        List<KnowledgeBaseService.RetrievalCitation> citations =
                List.of(citation("cA", 0.90), citation("cB", 0.50), citation("cC", 0.70));
        List<RerankHit> hits = List.of(
                new RerankHit("cC", 0.98),
                new RerankHit("cB", 0.91),
                new RerankHit("cA", 0.10));

        List<KnowledgeBaseService.RetrievalCitation> reranked = RerankReorderer.apply(citations, hits);

        assertEquals(List.of("cC", "cB", "cA"), reranked.stream().map(KnowledgeBaseService.RetrievalCitation::getChunkId).toList());
        assertEquals(0.90, reranked.get(2).getScore(), 1e-9);
        assertEquals(0.98, reranked.get(0).getRerankScore(), 1e-9);
        assertEquals(0.10, reranked.get(2).getRerankScore(), 1e-9);
    }

    @Test
    @DisplayName("未命中 rerank 的候选保持 0 分并稳定排在原融合序之后")
    void missingHitFallsBackStably() {
        List<KnowledgeBaseService.RetrievalCitation> citations =
                List.of(citation("cA", 0.90), citation("cB", 0.50), citation("cC", 0.70));
        List<RerankHit> hits = List.of(new RerankHit("cC", 0.98));

        List<KnowledgeBaseService.RetrievalCitation> reranked = RerankReorderer.apply(citations, hits);

        assertEquals(List.of("cC", "cA", "cB"), reranked.stream().map(KnowledgeBaseService.RetrievalCitation::getChunkId).toList());
        assertEquals(0.0, reranked.get(1).getRerankScore(), 1e-9);
        assertEquals(0.0, reranked.get(2).getRerankScore(), 1e-9);
    }

    @Test
    @DisplayName("空 rerank 结果时保持原融合序，等价于精排降级")
    void emptyHitsKeepFusedOrder() {
        List<KnowledgeBaseService.RetrievalCitation> citations =
                List.of(citation("cA", 0.90), citation("cB", 0.50));
        List<KnowledgeBaseService.RetrievalCitation> reranked = RerankReorderer.apply(citations, List.of());
        assertEquals(List.of("cA", "cB"), reranked.stream().map(KnowledgeBaseService.RetrievalCitation::getChunkId).toList());
        assertTrue(reranked.stream().allMatch(c -> c.getRerankScore() == 0.0));
    }
}
