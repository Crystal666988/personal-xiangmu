package com.tradeagent.modules.ai.rag.rerank;

import com.tradeagent.modules.ai.rag.kb.KnowledgeBaseService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务七：将 Cross-Encoder 相关度分数回填到融合候选并重排。
 *
 * 铁律：只改变顺序与 rerankScore，绝不修改融合得分 score ——
 * 0.40 置信度安全门禁已在重排之前以融合分执行完毕，精排不可能"救活"超纲对抗题。
 */
public final class RerankReorderer {

    private RerankReorderer() {
    }

    /**
     * @param citations 融合候选（已按融合分降序、已过门禁与地板过滤）
     * @param hits      Cross-Encoder 相关度结果（可为 null/空，此时保持原序）
     * @return 按 rerankScore 降序的候选（稳定排序，同分保持原融合序）
     */
    public static List<KnowledgeBaseService.RetrievalCitation> apply(
            List<KnowledgeBaseService.RetrievalCitation> citations, List<RerankHit> hits) {
        Map<String, Double> relevance = new HashMap<>();
        if (hits != null) {
            for (RerankHit hit : hits) {
                relevance.put(hit.chunkId(), hit.relevanceScore());
            }
        }
        for (KnowledgeBaseService.RetrievalCitation citation : citations) {
            double score = relevance.getOrDefault(citation.getChunkId(), 0.0);
            citation.setRerankScore(Math.round(score * 1000.0) / 1000.0);
        }
        List<KnowledgeBaseService.RetrievalCitation> reranked = new ArrayList<>(citations);
        // TimSort 稳定排序：rerank 分相同（含缺失命中的 0 分）的候选保持原融合序，降级行为可预期
        reranked.sort((a, b) -> Double.compare(b.getRerankScore(), a.getRerankScore()));
        return reranked;
    }
}
