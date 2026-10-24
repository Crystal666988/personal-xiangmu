package com.tradeagent.modules.ai.rag.rerank;

/**
 * Cross-Encoder 重排候选：双路召回合并后的切片。
 *
 * @param chunkId    切片唯一 ID
 * @param content    切片全文（供 (Query, Chunk) 联合编码）
 * @param fusedScore 双路融合得分（0.7 向量 + 0.3 BM25，含防击穿惩罚），仅作降级排序依据
 */
public record RerankCandidate(String chunkId, String content, double fusedScore) {
}
