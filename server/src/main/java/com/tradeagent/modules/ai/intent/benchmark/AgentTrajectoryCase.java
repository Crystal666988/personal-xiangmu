package com.tradeagent.modules.ai.intent.benchmark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 任务八：Agent 轨迹全息评测用例。
 *
 * goldenTools / goldenParams 为黄金标准；round1/round2PlanJson 为脚本化的
 * EvidenceRoutingPlan JSON（模拟 LLM 规划输出），保证评测确定性与零网络依赖。
 * SELF_CORRECTION 类用例通过"首轮缺参 → 追问 → 补充后重规划"验证两轮预算内的自愈能力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTrajectoryCase {

    private String id;

    /** GENERAL / SINGLE_TURN / SELF_CORRECTION / SAFETY_CLARIFY */
    private String category;

    /** 第一轮用户原始提问 */
    private String query;

    /** 第一轮脚本化 LLM 规划输出 (EvidenceRoutingPlan JSON) */
    private String round1PlanJson;

    /** 第一轮预期状态：GENERAL / EXECUTED / NEEDS_CLARIFICATION */
    private String expectedRound1Status;

    /** 黄金工具集合（GENERAL 期望为空） */
    private List<String> goldenTools;

    /** 黄金关键参数（仅在最终执行时校验声明的 key） */
    private Map<String, Object> goldenParams;

    /** 自愈轮补充提问（仅 SELF_CORRECTION 用例） */
    private String supplementQuery;

    /** 自愈轮脚本化 LLM 规划输出 (EvidenceRoutingPlan JSON) */
    private String round2PlanJson;

    /** 用例说明 */
    private String explanation;
}
