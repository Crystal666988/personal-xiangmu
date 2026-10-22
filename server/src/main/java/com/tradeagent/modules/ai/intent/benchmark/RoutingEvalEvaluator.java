package com.tradeagent.modules.ai.intent.benchmark;

import com.tradeagent.core.dag.RoutingPlan;
import com.tradeagent.core.dag.RoutingPlanner;
import com.tradeagent.core.dag.ToolCallPlan;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 路由评测器：只消费 RoutingPlan，不执行任何真实工具，因此不会产生外部副作用。
 *
 * 指标分两层：
 * 1. 领域层：expectedDomain 与 plan.domain 是否一致；
 * 2. 工具层：expectedTools 与 plan.toolCalls 的工具集合是否一致，
 *    并统计 micro Precision / Recall / F1、多调、漏调、关键参数命中率。
 */
public class RoutingEvalEvaluator {

    /** 当前 ToolRegistry 中真正有执行器的工具；其余工具只存在于路由元数据中。 */
    private static final Set<String> IMPLEMENTED_TOOLS = Set.of(
            "calculate_tier_price",
            "estimate_freight_cif",
            "currency_exchange_rate",
            "customs_data_lookup",
            "rag_vector_search"
    );

    private final RoutingPlanner planner;

    public RoutingEvalEvaluator(RoutingPlanner planner) {
        this.planner = planner;
    }

    public EvalResult evaluate(List<RoutingEvalCase> cases) {
        int total = 0;
        int domainHits = 0;
        int exactMatches = 0;
        int truePositives = 0;
        int predictedTotal = 0;
        int expectedTotal = 0;
        int overCallTotal = 0;
        int underCallTotal = 0;
        int paramChecks = 0;
        int paramHits = 0;
        long latencyTotal = 0;

        List<CaseResult> results = new ArrayList<>();
        Set<String> unimplementedExpectedTools = new TreeSet<>();
        Map<String, CategoryStats> categoryStats = new TreeMap<>();

        for (RoutingEvalCase testCase : cases) {
            long start = System.nanoTime();
            RoutingPlan plan = planner.plan(testCase.getQuery(), testCase.getAttachmentName());
            long latencyMs = Math.max(0, (System.nanoTime() - start) / 1_000_000);
            latencyTotal += latencyMs;

            Set<String> expectedTools = new LinkedHashSet<>(
                    testCase.getExpectedTools() != null ? testCase.getExpectedTools() : List.of());
            Set<String> predictedTools = new LinkedHashSet<>();
            Map<String, Object> plannedParams = new LinkedHashMap<>();
            for (ToolCallPlan call : plan.toolCalls()) {
                predictedTools.add(call.toolId());
                plannedParams.putAll(call.params());
            }

            boolean domainHit = Objects.equals(testCase.getExpectedDomain(), plan.domain());
            Set<String> intersection = new LinkedHashSet<>(predictedTools);
            intersection.retainAll(expectedTools);

            Set<String> overCalled = new LinkedHashSet<>(predictedTools);
            overCalled.removeAll(expectedTools);

            Set<String> underCalled = new LinkedHashSet<>(expectedTools);
            underCalled.removeAll(predictedTools);

            boolean exactToolMatch = predictedTools.equals(expectedTools);

            int caseParamChecks = testCase.getExpectedParams() != null ? testCase.getExpectedParams().size() : 0;
            int caseParamHits = 0;
            if (caseParamChecks > 0) {
                for (Map.Entry<String, Object> entry : testCase.getExpectedParams().entrySet()) {
                    if (Objects.equals(entry.getValue(), plannedParams.get(entry.getKey()))) {
                        caseParamHits++;
                    }
                }
            }

            for (String expectedTool : expectedTools) {
                if (!IMPLEMENTED_TOOLS.contains(expectedTool)) {
                    unimplementedExpectedTools.add(expectedTool);
                }
            }

            total++;
            if (domainHit) domainHits++;
            if (exactToolMatch) exactMatches++;
            truePositives += intersection.size();
            predictedTotal += predictedTools.size();
            expectedTotal += expectedTools.size();
            overCallTotal += overCalled.size();
            underCallTotal += underCalled.size();
            paramChecks += caseParamChecks;
            paramHits += caseParamHits;

            CategoryStats category = categoryStats.computeIfAbsent(testCase.getCategory(), k -> new CategoryStats());
            category.total++;
            if (domainHit) category.domainHits++;
            if (exactToolMatch) category.exactMatches++;
            category.truePositives += intersection.size();
            category.predictedTotal += predictedTools.size();
            category.expectedTotal += expectedTools.size();
            category.paramChecks += caseParamChecks;
            category.paramHits += caseParamHits;

            results.add(CaseResult.builder()
                    .id(testCase.getId())
                    .category(testCase.getCategory())
                    .query(testCase.getQuery())
                    .attachmentName(testCase.getAttachmentName())
                    .expectedDomain(testCase.getExpectedDomain())
                    .predictedDomain(plan.domain())
                    .domainHit(domainHit)
                    .candidateTools(plan.candidateTools())
                    .expectedTools(new ArrayList<>(expectedTools))
                    .predictedTools(new ArrayList<>(predictedTools))
                    .exactToolMatch(exactToolMatch)
                    .overCalled(new ArrayList<>(overCalled))
                    .underCalled(new ArrayList<>(underCalled))
                    .paramChecks(caseParamChecks)
                    .paramHits(caseParamHits)
                    .latencyMs(latencyMs)
                    .build());
        }

        return EvalResult.builder()
                .total(total)
                .domainHits(domainHits)
                .domainAccuracy(pct(domainHits, total))
                .exactMatches(exactMatches)
                .exactToolMatchRate(pct(exactMatches, total))
                .truePositives(truePositives)
                .predictedTotal(predictedTotal)
                .expectedTotal(expectedTotal)
                .toolPrecision(ratio(truePositives, predictedTotal))
                .toolRecall(ratio(truePositives, expectedTotal))
                .toolF1(f1(truePositives, predictedTotal, expectedTotal))
                .overCallTotal(overCallTotal)
                .underCallTotal(underCallTotal)
                .paramChecks(paramChecks)
                .paramHits(paramHits)
                .paramAccuracy(pct(paramHits, paramChecks))
                .avgLatencyMs(total == 0 ? 0.0 : Math.round((double) latencyTotal / total * 100.0) / 100.0)
                .unimplementedExpectedTools(new ArrayList<>(unimplementedExpectedTools))
                .cases(results)
                .categoryStats(categoryStats)
                .build();
    }

    private static double pct(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : Math.round((double) numerator / denominator * 1000.0) / 10.0;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : Math.round((double) numerator / denominator * 1000.0) / 1000.0;
    }

    private static double f1(int truePositives, int predictedTotal, int expectedTotal) {
        double precision = ratio(truePositives, predictedTotal);
        double recall = ratio(truePositives, expectedTotal);
        return precision + recall == 0 ? 0.0 : Math.round(2 * precision * recall / (precision + recall) * 1000.0) / 1000.0;
    }

    @Data
    @Builder
    public static class CaseResult {
        private String id;
        private String category;
        private String query;
        private String attachmentName;
        private String expectedDomain;
        private String predictedDomain;
        private boolean domainHit;
        private List<String> candidateTools;
        private List<String> expectedTools;
        private List<String> predictedTools;
        private boolean exactToolMatch;
        private List<String> overCalled;
        private List<String> underCalled;
        private int paramChecks;
        private int paramHits;
        private long latencyMs;
    }

    @Data
    public static class CategoryStats {
        private int total;
        private int domainHits;
        private int exactMatches;
        private int truePositives;
        private int predictedTotal;
        private int expectedTotal;
        private int paramChecks;
        private int paramHits;
    }

    @Data
    @Builder
    public static class EvalResult {
        private int total;
        private int domainHits;
        private double domainAccuracy;
        private int exactMatches;
        private double exactToolMatchRate;
        private int truePositives;
        private int predictedTotal;
        private int expectedTotal;
        private double toolPrecision;
        private double toolRecall;
        private double toolF1;
        private int overCallTotal;
        private int underCallTotal;
        private int paramChecks;
        private int paramHits;
        private double paramAccuracy;
        private double avgLatencyMs;
        private List<String> unimplementedExpectedTools;
        private List<CaseResult> cases;
        private Map<String, CategoryStats> categoryStats;

        public String formatReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("============================================================\n");
            sb.append("Routing Evaluation Baseline\n");
            sb.append("============================================================\n");
            sb.append(String.format("Cases                    : %d%n", total));
            sb.append(String.format("Domain accuracy          : %.1f%% (%d/%d)%n", domainAccuracy, domainHits, total));
            sb.append(String.format("Tool exact match         : %.1f%% (%d/%d)%n", exactToolMatchRate, exactMatches, total));
            sb.append(String.format("Tool micro P / R / F1    : %.1f%% / %.1f%% / %.1f%%%n",
                    toolPrecision * 100, toolRecall * 100, toolF1 * 100));
            sb.append(String.format("Tool calls predicted     : %d (expected %d, TP %d)%n",
                    predictedTotal, expectedTotal, truePositives));
            sb.append(String.format("Over-call / Under-call   : %d / %d%n", overCallTotal, underCallTotal));
            sb.append(String.format("Param accuracy           : %.1f%% (%d/%d)%n", paramAccuracy, paramHits, paramChecks));
            sb.append(String.format("Avg routing latency      : %.2f ms%n", avgLatencyMs));
            sb.append(String.format("Unimplemented expected   : %s%n", unimplementedExpectedTools));

            sb.append("\n-- By category --\n");
            categoryStats.forEach((category, stats) -> sb.append(String.format(
                    "%-14s total=%2d domain=%2d/%2d exact=%2d/%2d P=%.1f%% R=%.1f%% params=%d/%d%n",
                    category,
                    stats.total,
                    stats.domainHits,
                    stats.total,
                    stats.exactMatches,
                    stats.total,
                    stats.predictedTotal == 0 && stats.expectedTotal == 0
                            ? 100.0 : ratio(stats.truePositives, stats.predictedTotal) * 100,
                    stats.predictedTotal == 0 && stats.expectedTotal == 0
                            ? 100.0 : ratio(stats.truePositives, stats.expectedTotal) * 100,
                    stats.paramHits,
                    stats.paramChecks)));

            sb.append("\n-- Cases needing attention --\n");
            for (CaseResult result : cases) {
                if (result.isDomainHit() && result.isExactToolMatch()
                        && result.getParamHits() >= result.getParamChecks()) {
                    continue;
                }
                sb.append(String.format("[%s] %s%n", result.getId(), result.getQuery()));
                sb.append(String.format("  domain : %s -> %s%n", result.getExpectedDomain(), result.getPredictedDomain()));
                sb.append(String.format("  tools  : expected=%s predicted=%s%n",
                        result.getExpectedTools(), result.getPredictedTools()));
                sb.append(String.format("  routing: candidateTools=%s%n", result.getCandidateTools()));
                if (!result.getOverCalled().isEmpty()) {
                    sb.append(String.format("  over   : %s%n", result.getOverCalled()));
                }
                if (!result.getUnderCalled().isEmpty()) {
                    sb.append(String.format("  under  : %s%n", result.getUnderCalled()));
                }
                sb.append(String.format("  params : %d/%d%n", result.getParamHits(), result.getParamChecks()));
            }
            sb.append("============================================================\n");
            return sb.toString();
        }
    }
}
