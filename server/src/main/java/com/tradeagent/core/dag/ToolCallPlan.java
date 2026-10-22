package com.tradeagent.core.dag;

import java.util.Map;

/**
 * 单个工具调用的规划结果：只描述“打算调用什么、带什么参数”，不执行工具。
 */
public record ToolCallPlan(String toolId, String toolName, Map<String, Object> params) {
}
