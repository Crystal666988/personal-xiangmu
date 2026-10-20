package com.tradeagent.modules.ai.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体工具注册与物理执行中心
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, IToolHandler> toolMap = new ConcurrentHashMap<>();

    public ToolRegistry(List<IToolHandler> handlers) {
        if (handlers != null) {
            for (IToolHandler handler : handlers) {
                ToolDefinition def = handler.getDefinition();
                toolMap.put(def.getId(), handler);
                log.info("【工具就绪】已注册工具: [{}] - {} ({})", def.getId(), def.getName(), def.getDomain());
            }
        }
    }

    /**
     * 获取所有已注册工具元数据列表
     */
    public List<ToolDefinition> listAllDefinitions() {
        return toolMap.values().stream()
                .map(IToolHandler::getDefinition)
                .sorted(Comparator.comparing(ToolDefinition::getId))
                .toList();
    }

    /**
     * 获取指定工具元数据
     */
    public ToolDefinition getDefinition(String toolId) {
        IToolHandler handler = toolMap.get(toolId);
        return handler != null ? handler.getDefinition() : null;
    }

    /**
     * 物理执行指定工具并生成遥测追踪记录 (Telemetry Trace)
     */
    public ToolExecutionTrace executeTool(String toolId, Map<String, Object> params) {
        String traceId = "tr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        IToolHandler handler = toolMap.get(toolId);

        if (handler == null) {
            return ToolExecutionTrace.builder()
                    .traceId(traceId)
                    .toolId(toolId)
                    .toolName("未知工具")
                    .domain("GENERAL")
                    .queryInput(params != null ? params : Collections.emptyMap())
                    .outputResult(null)
                    .latencyMs(0)
                    .status("FAILED")
                    .error("未找到工具 ID: " + toolId)
                    .executedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                    .build();
        }

        ToolDefinition def = handler.getDefinition();
        long startNano = System.nanoTime();
        try {
            Object result = handler.execute(params != null ? params : Collections.emptyMap());
            long costMs = Math.max(1, (System.nanoTime() - startNano) / 1_000_000);

            return ToolExecutionTrace.builder()
                    .traceId(traceId)
                    .toolId(def.getId())
                    .toolName(def.getName())
                    .domain(def.getDomain())
                    .queryInput(params != null ? params : Collections.emptyMap())
                    .outputResult(result)
                    .latencyMs(costMs)
                    .status("SUCCESS")
                    .executedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                    .build();
        } catch (Exception e) {
            long costMs = Math.max(1, (System.nanoTime() - startNano) / 1_000_000);
            log.error("【工具执行失败】toolId={}, error={}", toolId, e.getMessage());

            return ToolExecutionTrace.builder()
                    .traceId(traceId)
                    .toolId(def.getId())
                    .toolName(def.getName())
                    .domain(def.getDomain())
                    .queryInput(params != null ? params : Collections.emptyMap())
                    .outputResult(null)
                    .latencyMs(costMs)
                    .status("FAILED")
                    .error(e.getMessage())
                    .executedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                    .build();
        }
    }
}
