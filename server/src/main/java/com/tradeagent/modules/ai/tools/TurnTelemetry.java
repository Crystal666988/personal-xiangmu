package com.tradeagent.modules.ai.tools;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单轮对话聚合遥测数据 (Turn Telemetry)
 * 记录本轮对话调用了多少工具、平均耗时、具体调用列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnTelemetry {
    /** 会话轮次标识 */
    private String turnId;

    /** 本轮实际调用的工具数量 */
    private int toolsCalledCount;

    /** 所有工具总耗时 (ms) */
    private long totalLatencyMs;

    /** 单个工具平均耗时 (ms) */
    private double avgLatencyMs;

    /** 链路详细调用明细 */
    private List<ToolExecutionTrace> traces;
}
