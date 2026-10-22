package com.tradeagent.modules.ai.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tradeagent.core.dag.HitlRiskEvaluator;
import com.tradeagent.core.dag.exception.ToolExecutionException;
import com.tradeagent.core.dag.model.ToolCorrectionCandidate;

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
    public boolean supportsCompensation(String id) {
        var handler = toolMap.get(id); return handler != null && handler.supportsCompensation();
    }
    public boolean compensationRetrySafe(String id) {
        var handler = toolMap.get(id); return handler != null && handler.compensationRetrySafe();
    }
    public Object compensate(String id, Map<String, Object> params, Object result, String idempotencyKey) throws Exception {
        var handler = toolMap.get(id);
        if (handler == null || !handler.supportsCompensation()) throw new UnsupportedOperationException("COMPENSATION_UNSUPPORTED");
        return handler.compensate(Map.copyOf(params), result, idempotencyKey);
    }
    public List<ToolCorrectionCandidate> correctionCandidates(String id, Map<String, Object> params, Map<String, String> sources) {
        var handler = toolMap.get(id);
        return handler == null ? List.of() : List.copyOf(handler.correctionCandidates(Map.copyOf(params), Map.copyOf(sources)));
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
                    .errorCode("TOOL_NOT_REGISTERED").errorCategory("FATAL").effectOutcome("NOT_APPLIED")
                    .executedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                    .build();
        }

        ToolDefinition def = handler.getDefinition();
        Map<String, Object> safeParams = params == null ? Map.of() : Map.copyOf(params);
        if (HitlRiskEvaluator.risk(def, safeParams).isPresent() && !HitlRiskEvaluator.authorized(toolId, safeParams)) {
            throw new IllegalArgumentException("该调用需要人工审批，请通过会话 DAG 审批流程执行");
        }
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
                    .error(e instanceof ToolExecutionException typed ? typed.getMessage() : "工具调用失败，请核查服务日志")
                    .errorCode(e instanceof ToolExecutionException typed ? typed.getCode() : "TOOL_EXECUTION_FAILED")
                    .errorCategory(e instanceof ToolExecutionException typed ? typed.getCategory().name() : "FATAL")
                    .effectOutcome(e instanceof ToolExecutionException typed ? typed.getEffectOutcome().name()
                            : HitlRiskEvaluator.hasSideEffects(def) ? "UNKNOWN" : "NOT_APPLIED")
                    .executedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                    .build();
        }
    }
}
