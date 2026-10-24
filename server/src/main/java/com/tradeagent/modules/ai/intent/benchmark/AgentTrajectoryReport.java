package com.tradeagent.modules.ai.intent.benchmark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务八：Agent 轨迹全息评测报告。
 *
 * 四大核心量化维度（对齐 llm-master Agent 评估体系）：
 * 1. Step Efficiency Ratio = 理论最简步数(黄金工具数) / 实际执行步数；
 * 2. Tool Precision / Recall / F1 (micro) = 实际执行工具集合 vs 黄金工具集合；
 * 3. Self-Correction Rate = 首轮缺参被追问、补充后 2 轮预算内正确执行的比例；
 * 4. Latency & Planner Budget = 单案例墙钟耗时 + 规划器调用次数与输出字符预算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTrajectoryReport {

    private String benchmarkId;
    private int totalCases;
    private int generalCases;
    private int executedCases;
    private int clarifiedCases;
    private int unexpectedCases;

    private int truePositives;
    private int predictedTotal;
    private int expectedTotal;
    private double toolPrecision;
    private double toolRecall;
    private double toolF1;

    private double avgStepEfficiencyRatio;
    private int paramChecks;
    private int paramHits;
    private double paramAccuracy;

    private int selfCorrectionTotal;
    private int selfCorrectionSuccesses;
    private double selfCorrectionRate;

    private int safetyClarifyTotal;
    private int safetyClarifySuccesses;

    private double avgLatencyMs;
    private int plannerModelCalls;
    private long plannerOutputChars;
    /** 规划器输出 Token 估算（CJK≈1/字，其余 4:1），工程口径非计费口径 */
    private long plannerTokensEstimate;

    private List<CaseResult> cases;

    public String formatReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("Agent Trajectory Benchmark (llm-master aligned)\n");
        sb.append("============================================================\n");
        sb.append(String.format("Benchmark ID             : %s%n", benchmarkId));
        sb.append(String.format("Cases                    : %d (general=%d executed=%d clarified=%d unexpected=%d)%n",
                totalCases, generalCases, executedCases, clarifiedCases, unexpectedCases));
        sb.append(String.format("Tool micro P / R / F1    : %.1f%% / %.1f%% / %.1f%%%n",
                toolPrecision * 100, toolRecall * 100, toolF1 * 100));
        sb.append(String.format("Tool calls executed      : %d (golden %d, TP %d)%n", predictedTotal, expectedTotal, truePositives));
        sb.append(String.format("Step Efficiency Ratio    : %.3f (1.0 = 最简轨迹)%n", avgStepEfficiencyRatio));
        sb.append(String.format("Param accuracy           : %.1f%% (%d/%d)%n", paramAccuracy * 100, paramHits, paramChecks));
        sb.append(String.format("Self-Correction Rate     : %.1f%% (%d/%d, 预算 2 轮)%n",
                selfCorrectionRate * 100, selfCorrectionSuccesses, selfCorrectionTotal));
        sb.append(String.format("Safety clarify success   : %d/%d (高危参数禁止执行)%n", safetyClarifySuccesses, safetyClarifyTotal));
        sb.append(String.format("Avg case latency         : %.2f ms%n", avgLatencyMs));
        sb.append(String.format("Planner budget           : %d 次调用, %d 字符输出 (≈%d tokens est.)%n",
                plannerModelCalls, plannerOutputChars, plannerTokensEstimate));

        sb.append("\n-- Case details --\n");
        for (CaseResult result : cases) {
            sb.append(String.format("[%s] %-16s round1=%s final=%s%s%n",
                    result.getId(), result.getCategory(), result.getRound1Status(), result.getFinalStatus(),
                    result.isRound1AsExpected() ? "" : "  ⚠️ 与预期不符"));
            sb.append(String.format("    tools: golden=%s executed=%s (stepRatio=%.2f, params=%d/%d)%n",
                    result.getGoldenTools(), result.getExecutedTools(), result.getStepEfficiencyRatio(),
                    result.getParamHits(), result.getParamChecks()));
        }
        sb.append("============================================================\n");
        return sb.toString();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseResult {
        private String id;
        private String category;
        private String query;
        private String round1Status;
        private String finalStatus;
        private boolean round1AsExpected;
        private List<String> goldenTools;
        private List<String> executedTools;
        private double stepEfficiencyRatio;
        private int paramChecks;
        private int paramHits;
        private boolean selfCorrected;
        private long latencyMs;
    }
}
