package com.tradeagent.modules.ai.chat;

import com.tradeagent.core.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 执行监控接口：独立「执行监控」页面专用。
 * 展示每轮对话的意图路由分析与工具调用耗时（数据来自消息路由元数据，不含密钥）。
 */
@RestController
@RequestMapping("/api/v1/agent/monitor")
public class AgentMonitorController {

    private final AgentMonitorService monitorService;

    public AgentMonitorController(AgentMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/recent")
    public ApiResponse<List<AgentMonitorService.MonitorRow>> recent(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResponse.success(monitorService.listRecent(tenantId, limit));
    }
}
