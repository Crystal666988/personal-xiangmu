package com.tradeagent.modules.ai.tools;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 单次工具调用全链路遥测记录 (Telemetry Trace)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionTrace {
    /** 链路追踪唯一ID */
    private String traceId;

    /** 工具唯一英文标识 (例如 calculate_tier_price) */
    private String toolId;

    /** 工具中文名称 */
    private String toolName;

    /** 所属意图领域 (COMMERCE, KNOWLEDGE, PROSPECT, CRM, VISION) */
    private String domain;

    /** 大模型或调用方传入的结构化 Query 入参 */
    private Map<String, Object> queryInput;

    /** 工具真实执行返回的结构化输出 */
    private Object outputResult;

    /** 工具真实物理执行耗时 (ms) */
    private long latencyMs;

    /** 执行状态: SUCCESS / FAILED */
    private String status;

    /** 异常信息 (若失败) */
    private String error;

    /** 执行时间点 */
    private String executedAt;
}
