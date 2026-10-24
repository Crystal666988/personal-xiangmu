package com.tradeagent.modules.ai.rag.rerank;

/**
 * Cross-Encoder 重排结果：切片与 Query 的深层交互相关度。
 *
 * @param chunkId        切片唯一 ID
 * @param relevanceScore 语义相关度（rerank 模型输出，范围约 [0,1]，仅用于排序）
 */
public record RerankHit(String chunkId, double relevanceScore) {
}
