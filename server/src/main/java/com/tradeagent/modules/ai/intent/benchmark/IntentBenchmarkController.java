package com.tradeagent.modules.ai.intent.benchmark;

import com.tradeagent.core.common.ApiResponse;
import com.tradeagent.core.common.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 意图路由 200 题基准自动化评测接口控制器
 */
@Slf4j
@Tag(name = "意图路由基准评测")
@RestController
@RequestMapping("/api/v1/agent/intent-benchmark")
public class IntentBenchmarkController {

    private final IntentBenchmarkService intentBenchmarkService;

    public IntentBenchmarkController(IntentBenchmarkService intentBenchmarkService) {
        this.intentBenchmarkService = intentBenchmarkService;
    }

    /**
     * 1. 一键触发意图路由 200 题基准自动化评测
     */
    @PostMapping("/run")
    @Operation(summary = "一键执行意图路由 200 题基准评测流水线")
    public ApiResponse<IntentBenchmarkReport> runBenchmark() {
        log.info("【API】收到意图路由 200 题全自动跑测请求...");
        IntentBenchmarkReport report = intentBenchmarkService.runBenchmark();
        return ApiResponse.success(report);
    }

    /**
     * 2. 获取最新一次意图评测的持久化报告
     */
    @GetMapping("/latest")
    @Operation(summary = "获取最新意图评测报告")
    public ApiResponse<IntentBenchmarkReport> getLatestReport() {
        IntentBenchmarkReport report = intentBenchmarkService.getLatestReport();
        return ApiResponse.success(report);
    }

    /**
     * 3. 分页查询历史意图基准评测记录列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询历史意图评测记录")
    public ApiResponse<PageResult<IntentBenchmarkRecord>> pageRecords(
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize
    ) {
        return ApiResponse.success(intentBenchmarkService.pageRecords(pageNum, pageSize));
    }

    /**
     * 4. 获取历史单次意图评测完整报告详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询单次历史意图评测详情报告")
    public ApiResponse<IntentBenchmarkReport> getDetail(@PathVariable("id") String id) {
        IntentBenchmarkReport report = intentBenchmarkService.getDetail(id);
        if (report == null) {
            return ApiResponse.error(404, "未找到该意图评测报告: " + id);
        }
        return ApiResponse.success(report);
    }

    /**
     * 5. 删除历史意图评测报告
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除历史意图评测报告")
    public ApiResponse<Boolean> deleteReport(@PathVariable("id") String id) {
        return ApiResponse.success(intentBenchmarkService.deleteRecord(id));
    }
}
