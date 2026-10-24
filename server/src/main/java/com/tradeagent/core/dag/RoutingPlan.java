package com.tradeagent.core.dag;

import java.util.List;

/**
 * 单轮路由的纯规划结果。
 *
 * @param domain         最终领域（GENERAL / COMMERCE / KNOWLEDGE / PROSPECT / CRM / VISION / COMPOSITE）
 * @param domainName     领域中文名
 * @param candidateTools 当前 IntentRouter 认为可以挂载的工具（可能包含尚未实现的工具）
 * @param toolCalls      规则基线口径下真正打算执行的工具调用（生产链路以 ValidatedPlanExecutor 预检结果为准）
 * @param reason         路由原因
 */
public record RoutingPlan(String domain,
                          String domainName,
                          List<String> candidateTools,
                          List<ToolCallPlan> toolCalls,
                          String reason) {
}
