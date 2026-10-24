package com.tradeagent.modules.ai.intent.benchmark;

import com.tradeagent.core.common.ApiResponse;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 任务八：Agent 轨迹全息自动化评测接口。
 *
 * POST /api/v1/agent/trajectory-benchmark/run  携带租户模型配置执行轨迹评测（真实 LLM 规划）
 * GET  /api/v1/agent/trajectory-benchmark/latest  获取最近一次评测报告
 */
@Slf4j
@Tag(name = "Agent 轨迹全息评测")
@RestController
@RequestMapping("/api/v1/agent/trajectory-benchmark")
public class AgentTrajectoryBenchmarkController {

    private final AgentTrajectoryBenchmarkService benchmarkService;

    public AgentTrajectoryBenchmarkController(AgentTrajectoryBenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @PostMapping("/run")
    @Operation(summary = "执行 Agent 轨迹全息评测（步数最优比 / 工具P-R-F1 / 参数自愈率 / 延迟预算）")
    public ApiResponse<AgentTrajectoryReport> run(@RequestBody RoutingModelConfig config) {
        if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            return ApiResponse.error(400, "需要租户模型配置（apiKey/baseUrl/modelName/tenantId/provider）才能执行轨迹评测");
        }
        log.info("【API】收到 Agent 轨迹全息评测请求...");
        AgentTrajectoryReport report = benchmarkService.runBenchmark(AgentTrajectoryDataset.getBenchmarkCases(), config);
        return ApiResponse.success(report);
    }

    @GetMapping("/latest")
    @Operation(summary = "获取最近一次 Agent 轨迹评测报告")
    public ApiResponse<AgentTrajectoryReport> latest() {
        AgentTrajectoryReport report = benchmarkService.getLatestReport();
        if (report == null) {
            return ApiResponse.error(404, "尚未执行过轨迹评测");
        }
        return ApiResponse.success(report);
    }
}
