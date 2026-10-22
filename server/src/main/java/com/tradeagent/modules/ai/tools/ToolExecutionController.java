package com.tradeagent.modules.ai.tools;

import com.tradeagent.core.common.ApiResponse;
import com.tradeagent.core.dag.IntentRouter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工具调用与全链路遥测 REST 接口控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/tools")
public class ToolExecutionController {

    private final ToolRegistry toolRegistry;
    private final ToolDispatchService dispatchService;
    private final IntentRouter router;



    /**
     * 1. 获取系统中所有已注册可执行工具及其 Schema 与示例
     */
    @GetMapping("/registry")
    public ApiResponse<List<ToolDefinition>> listTools() {
        return ApiResponse.success(toolRegistry.listAllDefinitions());
    }

    @Data
    public static class ToolExecuteRequest {
        private String toolId;
        private Map<String, Object> params;
    }

    /**
     * 2. 单独调试/调用某个工具，返回详细结构与毫秒耗时
     */
    @PostMapping("/execute")
    public ApiResponse<ToolExecutionTrace> executeTool(@RequestBody ToolExecuteRequest request) {
        if (request == null || request.getToolId() == null || request.getToolId().isBlank()) {
            return ApiResponse.error(400, "toolId 不能为空");
        }
        ToolExecutionTrace trace = dispatchService.executeTool(request.getToolId(), request.getParams());
        return ApiResponse.success(trace);
    }

    @Data
    public static class AutoDispatchRequest {
        private String query;
        private String attachmentName;
    }

    private final com.tradeagent.core.dag.SemanticRouter semanticRouter;

    public ToolExecutionController(ToolRegistry toolRegistry, ToolDispatchService dispatchService,
                                   IntentRouter router, com.tradeagent.core.dag.SemanticRouter semanticRouter) {
        this.toolRegistry = toolRegistry;
        this.dispatchService = dispatchService;
        this.router = router;
        this.semanticRouter = semanticRouter;
    }

    /**
     * 3. 根据自然语言提问与意图路由，自动触发工具调度并聚合遥测数据 (TurnTelemetry)
     */
    @PostMapping("/auto-dispatch")
    public ApiResponse<TurnTelemetry> autoDispatch(@RequestBody AutoDispatchRequest request) {
        return ApiResponse.error(410, "Legacy automatic dispatch is disabled; use the session context workflow");
    }

    /**
     * 4. 真实语义向量路由分析 (计算 1024 维余弦相似度并返回 6 大领域评分)
     */
    @PostMapping("/semantic-route")
    public ApiResponse<com.tradeagent.core.dag.SemanticRouter.SemanticRoutingResult> semanticRoute(@RequestBody AutoDispatchRequest request) {
        String query = request != null ? request.getQuery() : "";
        String attachment = request != null ? request.getAttachmentName() : null;
        return ApiResponse.success(semanticRouter.route(query, attachment));
    }
}
