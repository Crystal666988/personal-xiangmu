package com.tradeagent.modules.ai.rag.benchmark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * RAG 200 题黄金评测基准输出报告
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagBenchmarkReport {
    /** 评测任务 ID */
    private String benchmarkId;
    /** 真实切片索引数量 */
    private int totalChunksIndexed;
    /** 切片预览列表 */
    private List<ChunkPreview> chunkPreviews;
    /** 总测试用例数 (200) */
    private int totalQuestions;
    /** 成功命中召回用例数 (如 178) */
    private int hitCount;
    /** 上下文召回率 Hit@4 (如 89.0%) */
    private double recallRate;
    /** 缺陷样本数 (如 5) */
    private int badCaseCount;
    /** 缺陷率 Bad-Case Rate (如 2.5%) */
    private double badCaseRate;
    /** 对抗与拒答测试通过率 (如 98.0%) */
    private double adversarialRejectionRate;
    /** 平均检索耗时 (ms) */
    private double avgRetrievalLatencyMs;
    /** 各象限分类指标详情 */
    private Map<String, CategoryMetric> categoryMetrics;
    /** 典型 Bad-Case 抽样分析 */
    private List<BadCaseDetail> sampleBadCases;
    /** AI 真题明细与命中详情 */
    private List<QuestionEvaluationResult> questionResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkPreview {
        private String chunkId;
        private String breadcrumb;
        private String chunkType;
        private int charLength;
        private String snippet;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryMetric {
        private String categoryName;
        private int total;
        private int hits;
        private double hitRate;
        private int badCases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BadCaseDetail {
        private String questionId;
        private String question;
        private String category;
        private String reason;
        private String optimizationAction;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionEvaluationResult {
        private String id;
        private String question;
        private String category;
        private String categoryName;
        private String groundTruthAnswer;
        private String expectedChunkId;
        private String expectedKeyword;
        private List<String> retrievedChunkIds;
        private boolean isHit;
        private boolean isAdversarial;
        private String retrievedSnippet;
        private String badCaseReason;
    }
}
