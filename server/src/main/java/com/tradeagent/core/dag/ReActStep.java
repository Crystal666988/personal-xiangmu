package com.tradeagent.core.dag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * ReAct (Thought -> Action -> Observation) 单步决策执行轨迹模型
 * 遵循 llm-master 开源标准架构：白盒化回显智能体推理逻辑与工具调用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActStep implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 步骤序号 (1-indexed)
     */
    private int stepIndex;

    /**
     * 步骤类型:
     * - THOUGHT: 智能体思考与决策理由
     * - ACTION: 意图调度工具与结构化入参抽取
     * - OBSERVATION: 工具执行结果与环境观测值返回
     * - FINAL_ANSWER: 工具闭环总结或终审交接
     */
    private StepType type;

    /**
     * 发生时间戳
     */
    private long timestamp;

    /**
     * 当前单步耗时 (ms)
     */
    private long durationMs;

    // ==================== THOUGHT 阶段属性 ====================
    /**
     * 思考推导文本 / 决策逻辑依据
     */
    private String thoughtText;

    // ==================== ACTION 阶段属性 ====================
    /**
     * 目标调用的工具 ID (如 calculate_tier_price)
     */
    private String toolId;

    /**
     * 工具用户友好中文名称
     */
    private String toolName;

    /**
     * 结构化提取出的入参 Map
     */
    private Map<String, Object> toolParams;

    // ==================== OBSERVATION 阶段属性 ====================
    /**
     * 工具执行状态 (SUCCESS / FAILED / SKIPPED)
     */
    private String status;

    /**
     * 工具返回结构体结果 (Payload / JSON)
     */
    private Object toolOutput;

    // ==================== FINAL_ANSWER 阶段属性 ====================
    /**
     * 闭环简要说明
     */
    private String summary;

    public enum StepType {
        THOUGHT,
        ACTION,
        OBSERVATION,
        COMPENSATION,
        REFLECTION,
        FINAL_ANSWER
    }
    public static ReActStep lifecycle(int index, StepType type, String tool, String status, Object output, long costMs) {
        return ReActStep.builder().stepIndex(index).type(type).toolId(tool).toolName(tool).status(status)
                .toolOutput(output).timestamp(System.currentTimeMillis()).durationMs(costMs).build();
    }

    public static ReActStep thought(int stepIndex, String thoughtText, long durationMs) {
        return ReActStep.builder()
                .stepIndex(stepIndex)
                .type(StepType.THOUGHT)
                .timestamp(System.currentTimeMillis())
                .durationMs(durationMs)
                .thoughtText(thoughtText)
                .build();
    }

    public static ReActStep action(int stepIndex, String toolId, String toolName, Map<String, Object> toolParams) {
        return ReActStep.builder()
                .stepIndex(stepIndex)
                .type(StepType.ACTION)
                .timestamp(System.currentTimeMillis())
                .durationMs(0)
                .toolId(toolId)
                .toolName(toolName)
                .toolParams(toolParams)
                .build();
    }

    public static ReActStep observation(int stepIndex, String toolId, String toolName, String status, Object toolOutput, long durationMs) {
        return ReActStep.builder()
                .stepIndex(stepIndex)
                .type(StepType.OBSERVATION)
                .timestamp(System.currentTimeMillis())
                .durationMs(durationMs)
                .toolId(toolId)
                .toolName(toolName)
                .status(status)
                .toolOutput(toolOutput)
                .build();
    }

    public static ReActStep finalAnswer(int stepIndex, String summary, long durationMs) {
        return ReActStep.builder()
                .stepIndex(stepIndex)
                .type(StepType.FINAL_ANSWER)
                .timestamp(System.currentTimeMillis())
                .durationMs(durationMs)
                .summary(summary)
                .build();
    }
}
