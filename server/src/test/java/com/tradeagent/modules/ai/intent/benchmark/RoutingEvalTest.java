package com.tradeagent.modules.ai.intent.benchmark;

import com.tradeagent.core.dag.IntentRouter;
import com.tradeagent.core.dag.RoutingPlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 路由评测基线。
 *
 * 当前只采集现状，不设置准确率阈值；等路由与工具规划修正后，
 * 再像 RagAutomatedBenchmarkTest 一样加入断言阈值，防止指标回退。
 */
class RoutingEvalTest {

    @Test
    @DisplayName("采集当前意图路由的领域与工具调用基线指标")
    void evaluateCurrentRoutingBaseline() {
        RoutingEvalEvaluator evaluator = new RoutingEvalEvaluator(new RoutingPlanner(new IntentRouter()));
        RoutingEvalEvaluator.EvalResult result = evaluator.evaluate(RoutingEvalDataset.getCommonCases());

        System.out.println(result.formatReport());

        assertEquals(28, result.getTotal(), "路由评测用例总数应为 28");
    }
}
