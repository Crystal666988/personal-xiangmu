package com.tradeagent.core.dag;

import com.tradeagent.modules.ai.tools.ToolRegistry;
import com.tradeagent.modules.ai.tools.ToolExecutionTrace;
import com.tradeagent.modules.ai.tools.TurnTelemetry;
import org.springframework.stereotype.Service;
import java.util.*;

/** Legacy rule execution is disabled. Trace conversion is retained for older consumers. */
@Service
public class ReActExecutor {
    public ReActExecutor(ToolRegistry unused, IntentRouter unusedRouter) {}
    public List<ReActStep> executeReActLoop(String query, IntentRouter.Routing routing) {
        throw new UnsupportedOperationException("Legacy ReAct execution is disabled; use the validated session workflow");
    }
    public TurnTelemetry toTurnTelemetry(List<ReActStep> steps) {
        List<ToolExecutionTrace> traces = new ArrayList<>();
        if (steps != null) for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            if (step.getType() != ReActStep.StepType.OBSERVATION) continue;
            Map<String, Object> input = Map.of();
            for (int j = i - 1; j >= 0; j--) if (steps.get(j).getType() == ReActStep.StepType.ACTION
                    && Objects.equals(step.getToolId(), steps.get(j).getToolId())) { input = steps.get(j).getToolParams(); break; }
            traces.add(ToolExecutionTrace.builder().toolId(step.getToolId()).toolName(step.getToolName())
                    .status(step.getStatus()).queryInput(input).outputResult(step.getToolOutput()).latencyMs(step.getDurationMs()).build());
        }
        long total = traces.stream().mapToLong(ToolExecutionTrace::getLatencyMs).sum();
        return TurnTelemetry.builder().turnId("turn_" + UUID.randomUUID()).toolsCalledCount(traces.size())
                .totalLatencyMs(total).avgLatencyMs(traces.isEmpty() ? 0 : (double) total / traces.size()).traces(traces).build();
    }
}
