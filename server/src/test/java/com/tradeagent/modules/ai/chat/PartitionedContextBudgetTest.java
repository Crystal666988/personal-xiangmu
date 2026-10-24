package com.tradeagent.modules.ai.chat;

import com.tradeagent.core.dag.ReActStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 上下文预算测试：ZONE1/ZONE2 内容上限与 ZONE3 Observation 预算真实生效。
 */
class PartitionedContextBudgetTest {

    private final PartitionedContextBuilder builder = new PartitionedContextBuilder();

    @Test
    @DisplayName("ZONE1 内容超上限时保留头部并附加截断标记，安全红线仍完整在尾部")
    void zone1ContentIsCappedWithHeadPreservation() {
        String hugePrompt = "X".repeat(20000);
        var prompt = builder.build(hugePrompt, "目标", List.of(), null, List.of(), null, Map.of());

        assertTrue(prompt.zone1SystemPrompt().contains("已保留头部并截断"));
        // 安全红线段落固定拼接在内容之后，必须完整保留
        assertTrue(prompt.zone1SystemPrompt().contains("<<<END_ZONE_1>>>"));
        assertTrue(prompt.zone1SystemPrompt().contains("严禁模型凭空捏造虚假报价与交期"));
        assertTrue(prompt.zone1SystemPrompt().length() < 20000 + 2000);
    }

    @Test
    @DisplayName("ZONE2 目标超上限时保留头部并附加截断标记")
    void zone2GoalIsCapped() {
        String hugeGoal = "G".repeat(12000);
        var prompt = builder.build(null, hugeGoal, List.of(), null, List.of(), null, Map.of());

        assertTrue(prompt.zone2ImmutableGoal().contains("已保留头部并截断"));
        assertTrue(prompt.zone2ImmutableGoal().contains("<<<END_ZONE_2>>>"));
    }

    @Test
    @DisplayName("ZONE3 ReAct 轨迹受 Observation 预算约束：超预算步骤被截断并留痕")
    void zone3ObservationBudgetTruncatesSteps() {
        List<ReActStep> steps = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            steps.add(ReActStep.observation(i, "tool_" + i, "工具" + i, "SUCCESS",
                    Map.of("payload", "O".repeat(240)), 5));
        }
        var prompt = builder.build(null, "目标", List.of(), null, steps, null, Map.of());

        assertTrue(prompt.zone3ScratchpadWorking().contains("已按 Observation 预算"));
        // 预算 1000 字符：即使每步 250 字符，最终 zone3 总长也必须远小于 30 步全量
        assertTrue(prompt.zone3ScratchpadWorking().length() < 4000);
    }

    @Test
    @DisplayName("预算内的小轨迹不受影响，全部步骤保留")
    void smallTrajectoryKeepsAllSteps() {
        List<ReActStep> steps = List.of(
                ReActStep.observation(1, "calculate_tier_price", "阶梯报价", "SUCCESS",
                        Map.of("fobTotalUsdRaw", 116.0), 5),
                ReActStep.observation(2, "estimate_freight_cif", "海运费", "SUCCESS",
                        Map.of("freightUsd", 2250.0), 5));
        var prompt = builder.build(null, "目标", List.of(), null, steps, null, Map.of());

        assertTrue(prompt.zone3ScratchpadWorking().contains("tool_1") || prompt.zone3ScratchpadWorking().contains("calculate_tier_price"));
        assertTrue(prompt.zone3ScratchpadWorking().contains("estimate_freight_cif"));
        assertTrue(!prompt.zone3ScratchpadWorking().contains("已按 Observation 预算"));
    }
}
