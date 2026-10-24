package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradeagent.core.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 温氏智慧养殖 IoT 链路接口。
 *
 * POST /api/v1/iot/ingest          设备数据上报（MQTT 演示/真实设备均可走此入口）
 * GET  /api/v1/iot/anomalies       最近异常事件（含飞书预警状态）
 * GET  /api/v1/iot/data            设备最近数据点
 * POST /api/v1/iot/summary/now     立即聚合今日总结并推送飞书表格
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/iot")
public class IotController {

    private final IotIngestService ingestService;
    private final IotAnomalyEventMapper anomalyMapper;
    private final IotDeviceDataMapper dataMapper;
    private final IotDailySummaryService dailySummaryService;

    public IotController(IotIngestService ingestService, IotAnomalyEventMapper anomalyMapper,
                         IotDeviceDataMapper dataMapper, IotDailySummaryService dailySummaryService) {
        this.ingestService = ingestService;
        this.anomalyMapper = anomalyMapper;
        this.dataMapper = dataMapper;
        this.dailySummaryService = dailySummaryService;
    }

    public record IngestBody(String tenantId, String farmId, String deviceId,
                             String collectedAt, List<IotIngestService.MetricPoint> metrics) {}

    @PostMapping("/ingest")
    public ApiResponse<IotIngestService.IngestResult> ingest(@RequestBody IngestBody body) {
        IotIngestService.IngestRequest request = new IotIngestService.IngestRequest(
                body.tenantId() == null ? "1001" : body.tenantId(),
                body.farmId() == null ? "unknown_farm" : body.farmId(),
                body.deviceId(),
                body.collectedAt(),
                body.metrics());
        return ApiResponse.success(ingestService.ingest(request));
    }

    @GetMapping("/anomalies")
    public ApiResponse<List<IotAnomalyEvent>> anomalies(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResponse.success(anomalyMapper.selectList(new LambdaQueryWrapper<IotAnomalyEvent>()
                .eq(IotAnomalyEvent::getTenantId, tenantId)
                .orderByDesc(IotAnomalyEvent::getCreateTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200))));
    }

    @GetMapping("/data")
    public ApiResponse<List<IotDeviceData>> data(
            @RequestParam("deviceId") String deviceId,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ApiResponse.success(dataMapper.selectList(new LambdaQueryWrapper<IotDeviceData>()
                .eq(IotDeviceData::getTenantId, tenantId)
                .eq(IotDeviceData::getDeviceId, deviceId)
                .orderByDesc(IotDeviceData::getCollectedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 500))));
    }

    @PostMapping("/summary/now")
    public ApiResponse<String> summaryNow(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestParam(value = "date", required = false) String date) {
        LocalDate day = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now();
        String state = dailySummaryService.summarizeAndPush(day, tenantId);
        return ApiResponse.success("今日设备总结完成，飞书状态: " + state
                + (state.equals("LOCAL_ONLY") ? "（未配置飞书凭证，仅本地落库）" : ""));
    }
}
