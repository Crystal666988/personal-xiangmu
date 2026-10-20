package com.tradeagent.modules.ai.tools;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具元数据定义与入参 Schema
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {
    /** 工具唯一英文标识 */
    private String id;

    /** 工具中文名 */
    private String name;

    /** 所属领域 */
    private String domain;

    /** 工具功能描述 */
    private String description;

    /** 参数 Schema 说明 */
    private Map<String, Object> parameterSchema;

    /** 示例入参 Query */
    private Map<String, Object> exampleQuery;
}
