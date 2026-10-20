package com.tradeagent.modules.ai.tools;

import java.util.Map;

/**
 * 可执行工具统一接口
 */
public interface IToolHandler {

    /**
     * 获取工具元数据定义与入参规范
     */
    ToolDefinition getDefinition();

    /**
     * 执行具体业务工具
     *
     * @param params 大模型提取或调用方传入的入参 Query
     * @return 结构化业务数据结果
     * @throws Exception 业务执行异常
     */
    Object execute(Map<String, Object> params) throws Exception;
}
