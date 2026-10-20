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
    /** 评测使用的大模型 */
    private String modelUsed;
    /** 真实切片索引数量 */
    private int totalChunksIndexed;
    /** 切片预览列表 */
    private List<ChunkPreview> chunkPreviews;
    /** 总测试用例数 (如 3 或 200) */
    private int totalQuestions;
    /** 正向检索测试题数 (排除对抗题) */
    private int positiveQuestions;
    /** 成功命中召回用例数 (如 2 或 178) */
    private int hitCount;
    /** 全局综合准确率 (命中数+对抗防御通过数 / 总题数) % */
    private double overallAccuracy;
    /** 上下文召回率 Hit@4 (如 100.0% 或 89.0%) */
    private double recallRate;
    /** 缺陷样本数 (如 0 或 5) */
    private int badCaseCount;
    /** 缺陷率 Bad-Case Rate (如 0.0% 或 2.5%) */
    private double badCaseRate;
    /** 对抗诱导题总数 */
    private int adversarialQuestions;
    /** 对抗防御成功数 */
    private int adversarialPassed;
    /** 对抗与拒答测试通过率 (如 100.0% 或 98.0%) */
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

        @com.fasterxml.jackson.annotation.JsonProperty("isHit")
        private boolean isHit;

        @com.fasterxml.jackson.annotation.JsonProperty("isAdversarial")
        private boolean isAdversarial;

        @com.fasterxml.jackson.annotation.JsonProperty("isHit")
        public boolean isHit() {
            return isHit;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("isHit")
        public void setHit(boolean hit) {
            this.isHit = hit;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("isAdversarial")
        public boolean isAdversarial() {
            return isAdversarial;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("isAdversarial")
        public void setAdversarial(boolean adversarial) {
            this.isAdversarial = adversarial;
        }

        private String retrievedSnippet;
        private String badCaseReason;
    }
}
