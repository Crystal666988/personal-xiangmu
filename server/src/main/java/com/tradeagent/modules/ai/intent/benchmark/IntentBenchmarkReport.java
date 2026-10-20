package com.tradeagent.modules.ai.intent.benchmark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 意图路由 200 题黄金基准评测报告
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentBenchmarkReport {
    private String benchmarkId;
    private int totalCases;
    private int hitCount;
    private double overallAccuracy;

    // 分类指标
    private double chitchatAccuracy;
    private int chitchatTotal;
    private int chitchatHits;

    private double singleDomainAccuracy;
    private int singleDomainTotal;
    private int singleDomainHits;

    private double compositeAccuracy;
    private int compositeTotal;
    private int compositeHits;

    // 性能指标
    private double avgPruningRate; // 平均工具剪枝率 %
    private double avgLatencyMs;   // 平均路由耗时 ms

    // 混淆矩阵: 预期领域 -> (预测领域 -> 出现次数)
    private Map<String, Map<String, Integer>> confusionMatrix;

    // 各领域详细统计
    private Map<String, DomainMetric> domainMetrics;

    // 典型错题分析
    private List<CaseResult> badCases;

    // 200 题完整跑测明细
    private List<CaseResult> caseResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DomainMetric {
        private String domain;
        private String domainName;
        private int total;
        private int hits;
        private double accuracy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseResult {
        private String id;
        private String category;
        private String query;
        private String expectedDomain;
        private String predictedDomain;
        private double topSimilarity;
        private String matchedSeed;
        private List<String> expectedTools;
        private List<String> activeTools;
        private double pruningRate;
        private boolean hit;
        private long latencyMs;
        private String failureReason;
    }
}
