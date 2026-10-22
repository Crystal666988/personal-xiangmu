package com.tradeagent.modules.ai.tools;

import com.tradeagent.core.dag.model.ToolCorrectionCandidate;
import java.util.List;
import java.util.Map;

/** Handler contract. Compensation must undo a real effect; irreversible tools leave this unsupported. */
public interface AgentTool {
    ToolDefinition getDefinition();
    Object execute(Map<String, Object> params) throws Exception;
    default boolean supportsCompensation() { return false; }
    /** True only if the external system deduplicates the supplied idempotency key. */
    default boolean compensationRetrySafe() { return false; }
    default Object compensate(Map<String, Object> originalParams, Object forwardResult, String idempotencyKey) throws Exception {
        throw new UnsupportedOperationException("This tool has no compensating operation");
    }
    /** Backend-authoritative canonical values bound to exact original evidence; never LLM-created values. */
    default List<ToolCorrectionCandidate> correctionCandidates(Map<String, Object> failedParams, Map<String, String> originalMessages) {
        return List.of();
    }
}
