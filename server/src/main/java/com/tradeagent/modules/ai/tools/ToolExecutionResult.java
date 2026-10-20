package com.tradeagent.modules.ai.tools;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标准化工具执行返回包装体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    private int code;
    private String toolId;
    private String status; // SUCCESS, FAILED
    private long costMs;
    private Object data;
    private String error;

    public static ToolExecutionResult success(String toolId, long costMs, Object data) {
        return ToolExecutionResult.builder()
                .code(200)
                .toolId(toolId)
                .status("SUCCESS")
                .costMs(costMs)
                .data(data)
                .build();
    }

    public static ToolExecutionResult failed(String toolId, long costMs, String error) {
        return ToolExecutionResult.builder()
                .code(500)
                .toolId(toolId)
                .status("FAILED")
                .costMs(costMs)
                .error(error)
                .build();
    }
}
