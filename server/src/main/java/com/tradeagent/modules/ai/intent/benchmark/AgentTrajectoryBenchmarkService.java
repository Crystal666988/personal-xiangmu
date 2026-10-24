package com.tradeagent.modules.ai.intent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.EvidenceRoutingPlan;
import com.tradeagent.modules.ai.chat.AgentMessage;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import com.tradeagent.modules.ai.intent.RoutingWorkflowService;
import com.tradeagent.modules.ai.tools.ToolExecutionTrace;
import com.tradeagent.modules.ai.tools.TurnTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 任务八：Agent 轨迹全息自动化评测引擎（对齐 llm-master Agent 评估体系）。
 *
 * 评测走真实 RoutingWorkflowService 管线（计划校验 → 整单预检 → 只读工具执行闸门），
 * 工具均为只读演示实现；LLM 规划输出以脚本 JSON 注入，保证评测确定性、零网络依赖。
 * 接入真实大模型时，由 Controller 注入租户 RoutingModelConfig，同一套指标即可量化线上规划质量。
 */
@Slf4j
@Service
public class AgentTrajectoryBenchmarkService {

    /** 评测租户（与 RoutingModelConfig.tenantId 保持一致） */
    public static final String TENANT = "7";

    private final RoutingWorkflowService workflow;
    private volatile AgentTrajectoryReport latestReport;

    public AgentTrajectoryBenchmarkService(RoutingWorkflowService workflow) {
        this.workflow = workflow;
    }

    public AgentTrajectoryReport runBenchmark(List<AgentTrajectoryCase> cases, RoutingModelConfig config) {
        String benchmarkId = "TRAJ_BENCH_" + System.currentTimeMillis();
        long benchStart = System.currentTimeMillis();

        List<AgentTrajectoryReport.CaseResult> results = new ArrayList<>();
        int generalCases = 0;
        int executedCases = 0;
        int clarifiedCases = 0;
        int unexpectedCases = 0;
        int truePositives = 0;
        int predictedTotal = 0;
        int expectedTotal = 0;
        int paramChecks = 0;
        int paramHits = 0;
        int selfCorrectionTotal = 0;
        int selfCorrectionSuccesses = 0;
        int safetyClarifyTotal = 0;
        int safetyClarifySuccesses = 0;
        double stepRatioSum = 0.0;
        long latencySum = 0;
        int plannerModelCalls = 0;
        long plannerOutputChars = 0;

        for (AgentTrajectoryCase testCase : cases) {
            long caseStart = System.currentTimeMillis();
            AgentMessage first = user(testCase.getId(), "u1", testCase.getQuery());
            RoutingWorkflowService.Result round1 = workflow.run(TENANT, testCase.getId(), first, List.of(first), config, "continue");
            long round1Ms = Math.max(1, System.currentTimeMillis() - caseStart);

            String status1 = round1.routingStatus();
            boolean round1AsExpected = status1.equalsIgnoreCase(testCase.getExpectedRound1Status());
            plannerModelCalls++;
            plannerOutputChars += testCase.getRound1PlanJson() != null ? testCase.getRound1PlanJson().length() : 0;

            boolean isSelfCorrection = "SELF_CORRECTION".equals(testCase.getCategory());
            boolean isSafetyClarify = "SAFETY_CLARIFY".equals(testCase.getCategory());
            TurnTelemetry telemetry = round1.telemetry();
            String finalStatus = status1;
            boolean selfCorrected = false;
            long latency = round1Ms;

            // 自愈轮：首轮必须"追问且零执行"，补充后 1 轮内正确执行（总预算 2 轮）
            if (isSelfCorrection && round1AsExpected
                    && "NEEDS_CLARIFICATION".equalsIgnoreCase(status1)
                    && telemetry.getToolsCalledCount() == 0
                    && testCase.getSupplementQuery() != null
                    && testCase.getRound2PlanJson() != null) {
                long round2Start = System.currentTimeMillis();
                AgentMessage supplement = user(testCase.getId(), "u2", testCase.getSupplementQuery());
                RoutingWorkflowService.Result round2 = workflow.run(TENANT, testCase.getId(), supplement,
                        List.of(first, supplement), config, "continue");
                latency += Math.max(1, System.currentTimeMillis() - round2Start);
                finalStatus = round2.routingStatus();
                telemetry = round2.telemetry();
                selfCorrected = "EXECUTED".equalsIgnoreCase(finalStatus);
                plannerModelCalls++;
                plannerOutputChars += testCase.getRound2PlanJson().length();
            }

            // 实际执行工具集合与合并参数
            LinkedHashSet<String> predictedTools = new LinkedHashSet<>();
            Map<String, Object> resolvedParams = new LinkedHashMap<>();
            if (telemetry.getTraces() != null) {
                for (ToolExecutionTrace trace : telemetry.getTraces()) {
                    predictedTools.add(trace.getToolId());
                    if (trace.getQueryInput() != null) {
                        resolvedParams.putAll(trace.getQueryInput());
                    }
                }
            }
            Set<String> goldenTools = new LinkedHashSet<>(
                    testCase.getGoldenTools() != null ? testCase.getGoldenTools() : List.of());
            Set<String> intersection = new LinkedHashSet<>(predictedTools);
            intersection.retainAll(goldenTools);

            int caseParamChecks = testCase.getGoldenParams() != null ? testCase.getGoldenParams().size() : 0;
            int caseParamHits = 0;
            if (caseParamChecks > 0) {
                for (Map.Entry<String, Object> entry : testCase.getGoldenParams().entrySet()) {
                    if (Objects.equals(entry.getValue(), resolvedParams.get(entry.getKey()))) {
                        caseParamHits++;
                    }
                }
            }

            // 步数最优比：空对空 = 1.0；漏执行 = 0.0；多执行按 最简/实际 惩罚
            double stepRatio;
            if (goldenTools.isEmpty() && predictedTools.isEmpty()) {
                stepRatio = 1.0;
            } else if (predictedTools.isEmpty() || !predictedTools.containsAll(goldenTools)) {
                stepRatio = 0.0;
            } else {
                stepRatio = (double) goldenTools.size() / predictedTools.size();
            }

            // 终态归类
            if ("GENERAL".equalsIgnoreCase(finalStatus)) generalCases++;
            else if ("EXECUTED".equalsIgnoreCase(finalStatus)) executedCases++;
            else if ("NEEDS_CLARIFICATION".equalsIgnoreCase(finalStatus)) clarifiedCases++;
            else unexpectedCases++;

            if (!isSelfCorrection) {
                truePositives += intersection.size();
                predictedTotal += predictedTools.size();
                expectedTotal += goldenTools.size();
            }
            paramChecks += caseParamChecks;
            paramHits += caseParamHits;
            stepRatioSum += stepRatio;
            latencySum += latency;

            if (isSelfCorrection) {
                selfCorrectionTotal++;
                boolean toolsExact = predictedTools.equals(goldenTools);
                boolean paramsExact = caseParamChecks == 0 || caseParamHits == caseParamChecks;
                if (selfCorrected && toolsExact && paramsExact) {
                    selfCorrectionSuccesses++;
                }
            }
            if (isSafetyClarify) {
                safetyClarifyTotal++;
                if ("NEEDS_CLARIFICATION".equalsIgnoreCase(finalStatus)
                        && predictedTools.isEmpty()
                        && round1AsExpected) {
                    safetyClarifySuccesses++;
                }
            }

            results.add(AgentTrajectoryReport.CaseResult.builder()
                    .id(testCase.getId())
                    .category(testCase.getCategory())
                    .query(testCase.getQuery())
                    .round1Status(status1)
                    .finalStatus(finalStatus)
                    .round1AsExpected(round1AsExpected)
                    .goldenTools(new ArrayList<>(goldenTools))
                    .executedTools(new ArrayList<>(predictedTools))
                    .stepEfficiencyRatio(Math.round(stepRatio * 1000.0) / 1000.0)
                    .paramChecks(caseParamChecks)
                    .paramHits(caseParamHits)
                    .selfCorrected(selfCorrected)
                    .latencyMs(latency)
                    .build());
        }

        AgentTrajectoryReport report = AgentTrajectoryReport.builder()
                .benchmarkId(benchmarkId)
                .totalCases(cases.size())
                .generalCases(generalCases)
                .executedCases(executedCases)
                .clarifiedCases(clarifiedCases)
                .unexpectedCases(unexpectedCases)
                .truePositives(truePositives)
                .predictedTotal(predictedTotal)
                .expectedTotal(expectedTotal)
                .toolPrecision(ratio(truePositives, predictedTotal))
                .toolRecall(ratio(truePositives, expectedTotal))
                .toolF1(f1(truePositives, predictedTotal, expectedTotal))
                .avgStepEfficiencyRatio(cases.isEmpty() ? 0.0 : Math.round(stepRatioSum / cases.size() * 1000.0) / 1000.0)
                .paramChecks(paramChecks)
                .paramHits(paramHits)
                .paramAccuracy(percentage(paramHits, paramChecks))
                .selfCorrectionTotal(selfCorrectionTotal)
                .selfCorrectionSuccesses(selfCorrectionSuccesses)
                .selfCorrectionRate(percentage(selfCorrectionSuccesses, selfCorrectionTotal))
                .safetyClarifyTotal(safetyClarifyTotal)
                .safetyClarifySuccesses(safetyClarifySuccesses)
                .avgLatencyMs(cases.isEmpty() ? 0.0 : Math.round((double) latencySum / cases.size() * 100.0) / 100.0)
                .plannerModelCalls(plannerModelCalls)
                .plannerOutputChars(plannerOutputChars)
                .cases(results)
                .build();

        log.info("【Agent 轨迹评测】{}, 用例 {} 条, 工具F1={}, 步数最优比={}, 参数准确率={}, 自愈率={}, 总耗时={} ms",
                benchmarkId, report.getTotalCases(), report.getToolF1(), report.getAvgStepEfficiencyRatio(),
                report.getParamAccuracy(), report.getSelfCorrectionRate(), System.currentTimeMillis() - benchStart);

        latestReport = report;
        return report;
    }

    public AgentTrajectoryReport getLatestReport() {
        return latestReport;
    }

    private AgentMessage user(String sessionId, String id, String text) {
        return AgentMessage.builder().id(id).sessionId(sessionId).role("user").content(text).build();
    }

    /** 空对空视为满分（无黄金工具且无多余调用） */
    private static double ratio(int numerator, int denominator) {
        if (denominator == 0) return numerator == 0 ? 1.0 : 0.0;
        return Math.round((double) numerator / denominator * 1000.0) / 1000.0;
    }

    private static double percentage(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : Math.round((double) numerator / denominator * 1000.0) / 1000.0;
    }

    private static double f1(int truePositives, int predictedTotal, int expectedTotal) {
        double precision = ratio(truePositives, predictedTotal);
        double recall = ratio(truePositives, expectedTotal);
        if (precision + recall == 0) return 0.0;
        return Math.round(2 * precision * recall / (precision + recall) * 1000.0) / 1000.0;
    }

    /** 供数据集构建 EvidenceRoutingPlan JSON；评测输出与生产规划输出同构 */
    static String planJson(EvidenceRoutingPlan.Task[] tasks, EvidenceRoutingPlan.SlotCandidate[] slots) {
        try {
            return new ObjectMapper().writeValueAsString(new EvidenceRoutingPlan(List.of(tasks), List.of(slots)));
        } catch (Exception e) {
            throw new IllegalStateException("评测计划 JSON 构建失败", e);
        }
    }
}
