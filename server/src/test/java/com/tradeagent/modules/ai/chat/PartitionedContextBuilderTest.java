package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.ReActStep;
import com.tradeagent.modules.ai.tools.TurnTelemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("任务五：PartitionedContextBuilder 三层上下文隔离构建器测试")
class PartitionedContextBuilderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final PartitionedContextBuilder builder = new PartitionedContextBuilder(json);

    @Test
    @DisplayName("物理隔离验证：输出包含 ZONE 1、ZONE 2、ZONE 3 明确隔离界标")
    void testThreeZonePartitioning() {
        String baseSystem = "你是一名资深外贸顾问";
        String userGoal = "请帮我核算 5000 件光伏逆变器出口德国汉堡的 CIF 价格与海运费";

        List<ReActStep> reactSteps = List.of(
                ReActStep.action(1, "calculate_tier_price", "阶梯价格计算", Map.of("quantity", 5000)),
                ReActStep.observation(2, "calculate_tier_price", "阶梯价格计算", "SUCCESS", Map.of("fobTotalUsd", 125000), 120L)
        );

        TurnTelemetry telemetry = TurnTelemetry.builder()
                .turnId("turn-101")
                .toolsCalledCount(1)
                .totalLatencyMs(120L)
                .build();

        Map<String, Object> slots = Map.of("destinationPort", "HAMBURG", "currency", "USD");

        var prompt = builder.build(baseSystem, userGoal, List.of(), "客户历史偏好CIF条款", reactSteps, telemetry, slots);

        assertNotNull(prompt);
        // ZONE 1 验证
        assertTrue(prompt.zone1SystemPrompt().contains("<<<ZONE_1_SYSTEM_PROMPT>>>"));
        assertTrue(prompt.zone1SystemPrompt().contains("【系统级指令 · 严格不可篡改】"));
        assertTrue(prompt.zone1SystemPrompt().contains("你是一名资深外贸顾问"));

        // ZONE 2 验证
        assertTrue(prompt.zone2ImmutableGoal().contains("<<<ZONE_2_USER_IMMUTABLE_GOAL>>>"));
        assertTrue(prompt.zone2ImmutableGoal().contains("【用户原始核心目标 · 全程置顶防漂移】"));
        assertTrue(prompt.zone2ImmutableGoal().contains("5000 件光伏逆变器出口德国汉堡"));

        // ZONE 3 验证
        assertTrue(prompt.zone3ScratchpadWorking().contains("<<<ZONE_3_SCRATCHPAD_WORKING>>>"));
        assertTrue(prompt.zone3ScratchpadWorking().contains("calculate_tier_price"));
        assertTrue(prompt.zone3ScratchpadWorking().contains("destinationPort"));

        // 验证标准 Spring AI 消息序列
        List<Message> messages = prompt.structuredMessages();
        assertFalse(messages.isEmpty());
        assertTrue(messages.get(0) instanceof SystemMessage, "第1条必须为 SystemMessage 固化 ZONE 1");
        assertTrue(messages.get(1) instanceof SystemMessage, "第2条必须为 SystemMessage 置顶固化 ZONE 2 用户不可变目标");
    }

    @Test
    @DisplayName("大文本观测值裁剪保护：超大文本 Observation 被安全修剪，防止挤爆上下文")
    void testScratchpadObservationTruncation() {
        String hugeObservation = "A".repeat(5000);
        List<ReActStep> reactSteps = List.of(
                ReActStep.observation(1, "raw_web_fetcher", "网页检索", "SUCCESS", hugeObservation, 300L)
        );

        var prompt = builder.build("系统人设", "目标任务", List.of(), null, reactSteps, null, Map.of());
        String zone3 = prompt.zone3ScratchpadWorking();

        assertTrue(zone3.contains("[已剪裁]"), "单步超长数据必须被自动截断，防止 Token 膨胀目标漂移");
        assertTrue(zone3.length() < 3000, "裁剪后工作区长度应受严格限制");
    }

    @Test
    @DisplayName("全景视图方法：toFullView 返回包含全部三层区域的格式化内容")
    void testToFullView() {
        var prompt = builder.build("角色指令", "用户终极目标", List.of(), null, List.of(), null, Map.of());
        String fullView = prompt.toFullView();
        assertTrue(fullView.contains("[ZONE 1: SYSTEM_PROMPT]"));
        assertTrue(fullView.contains("[ZONE 2: USER_IMMUTABLE_GOAL]"));
        assertTrue(fullView.contains("[ZONE 3: SCRATCHPAD_WORKING]"));
    }
}
