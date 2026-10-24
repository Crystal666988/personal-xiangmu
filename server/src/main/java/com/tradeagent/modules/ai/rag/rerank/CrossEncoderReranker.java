package com.tradeagent.modules.ai.rag.rerank;

import java.util.List;

/**
 * 任务七：Cross-Encoder 语义二次重排器接口（对齐 llm-master RAG 工业精排标准）。
 *
 * 执行位置：pgvector 稠密 + BM25 稀疏双路召回合并候选池之后、Top-K 截断之前。
 * 目标：消除长文本 Lost in the Middle，提升近似干扰切片下的最终 Top-K 区分度。
 *
 * 实现约定：
 * 1. {@link #isAvailable()} 为 false 时调用方直接跳过，保持融合排序；
 * 2. {@link #rerank} 抛出任何异常时调用方捕获并回退融合排序，绝不阻塞主检索链路；
 * 3. 重排分数只决定顺序，不替代 0.40 融合置信度安全门禁（门禁在重排之前已执行）。
 */
public interface CrossEncoderReranker {

    /** 重排器是否就绪（已启用且凭证已配置）。 */
    boolean isAvailable();

    /**
     * 对候选切片执行 (Query, Chunk) 联合编码打分。
     *
     * @param query      用户原始提问
     * @param candidates 双路召回候选（已按融合分降序）
     * @param topK       最终保留条数（实现可据此裁剪 top_n）
     * @return 按相关度降序的重排结果
     */
    List<RerankHit> rerank(String query, List<RerankCandidate> candidates, int topK) throws Exception;
}
