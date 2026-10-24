package com.tradeagent.modules.ai.tools;

import com.tradeagent.core.dag.IntentRouter;
import org.springframework.stereotype.Service;
import java.util.Map;

/**
 * 单工具手动执行器（P2 定位）。
 *
 * 服务对象：技能中心（SkillService 手动触发技能绑定的原子工具）与 /agent/tools/execute 调试接口。
 * 生产聊天主路径的执行闸门是 ValidatedPlanExecutor（整单预检 + 证据绑定参数）；
 * 遗留的规则式自动派发（autoDispatch）已退役，直接抛出异常防止误用。
 */
@Service
public class ToolDispatchService {
    private final ToolRegistry registry;
    public ToolDispatchService(ToolRegistry registry, IntentRouter unused) { this.registry = registry; }
    public ToolExecutionTrace executeTool(String toolId, Map<String, Object> params) { return registry.executeTool(toolId, params); }
    public TurnTelemetry autoDispatch(String query, IntentRouter.Routing routing) {
        throw new UnsupportedOperationException("Legacy automatic dispatch is disabled; use the session context workflow");
    }
}
