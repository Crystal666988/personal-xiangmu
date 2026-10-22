package com.tradeagent.modules.ai.tools;

import com.tradeagent.core.dag.IntentRouter;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Manual explicit calls remain available; legacy rule-based automatic execution is retired. */
@Service
public class ToolDispatchService {
    private final ToolRegistry registry;
    public ToolDispatchService(ToolRegistry registry, IntentRouter unused) { this.registry = registry; }
    public ToolExecutionTrace executeTool(String toolId, Map<String, Object> params) { return registry.executeTool(toolId, params); }
    public TurnTelemetry autoDispatch(String query, IntentRouter.Routing routing) {
        throw new UnsupportedOperationException("Legacy automatic dispatch is disabled; use the session context workflow");
    }
}
