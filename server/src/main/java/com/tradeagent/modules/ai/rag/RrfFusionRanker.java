package com.tradeagent.modules.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion - 倒数排名融合算法) 处理器
 * 
 * 核心技术考点：
 * 解决混合检索（Hybrid Search）中，BM25 词频打分（0~几十）与 向量余弦相似度（0~1）
 * 尺度完全不同、无法直接相加归一化的工业级痛点。
 * 纯数学实现，零额外 Token 成本，执行耗时 < 1ms。
 */
@Slf4j
@Component
public class RrfFusionRanker {

    /**
     * RRF 平滑常数 k (学术界与 Elasticsearch / Milvus 默认最佳经验值 60)
     */
    private static final int DEFAULT_K = 60;

    /**
     * 对多路召回列表进行倒数排名融合
     *
     * @param bm25DocIds 关键词搜索排序列表 (名次按序 1, 2, 3...)
     * @param vectorDocIds 向量相似度搜索排序列表 (名次按序 1, 2, 3...)
     * @param topK 最终融合截取的文档数
     * @return 经过 RRF 融合打分排序后的最终文档 ID 列表
     */
    public List<String> fuseRrf(List<String> bm25DocIds, List<String> vectorDocIds, int topK) {
        if ((bm25DocIds == null || bm25DocIds.isEmpty()) && (vectorDocIds == null || vectorDocIds.isEmpty())) {
            return Collections.emptyList();
        }

        // 记录各文档累计的 RRF 倒数得分: docId -> score
        Map<String, Double> scoreMap = new HashMap<>();

        // 1. 计算 BM25 列表得分: score += 1.0 / (60 + rank)
        if (bm25DocIds != null) {
            for (int rank = 0; rank < bm25DocIds.size(); rank++) {
                String docId = bm25DocIds.get(rank);
                double score = 1.0 / (DEFAULT_K + (rank + 1));
                scoreMap.put(docId, scoreMap.getOrDefault(docId, 0.0) + score);
            }
        }

        // 2. 累加向量检索列表得分: score += 1.0 / (60 + rank)
        if (vectorDocIds != null) {
            for (int rank = 0; rank < vectorDocIds.size(); rank++) {
                String docId = vectorDocIds.get(rank);
                double score = 1.0 / (DEFAULT_K + (rank + 1));
                scoreMap.put(docId, scoreMap.getOrDefault(docId, 0.0) + score);
            }
        }

        // 3. 按最终融合得分从高到低排序，截取 Top-K
        List<String> sortedDocIds = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        log.info("【RRF 排名融合】BM25 候选数: {}, 向量候选数: {}, 最终融合输出 Top-{}: {}",
                bm25DocIds != null ? bm25DocIds.size() : 0,
                vectorDocIds != null ? vectorDocIds.size() : 0,
                topK, sortedDocIds);

        return sortedDocIds;
    }
}
