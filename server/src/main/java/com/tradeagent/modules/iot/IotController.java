package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradeagent.core.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * IoT 接口：设备 CRUD / 数据上报 / 批量上报 / 压测器 / 异常与数据查询。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/iot")
public class IotController {

    private final IotDeviceService deviceService;
    private final IotIngestService ingestService;
    private final IotDataBufferService bufferService;
    private final IotLoadSimulatorService loadSimulator;
    private final IotAnomalyEventMapper anomalyMapper;
    private final IotDeviceDataMapper dataMapper;

    public IotController(IotDeviceService deviceService, IotIngestService ingestService,
                         IotDataBufferService bufferService, IotLoadSimulatorService loadSimulator,
                         IotAnomalyEventMapper anomalyMapper, IotDeviceDataMapper dataMapper) {
        this.deviceService = deviceService;
        this.ingestService = ingestService;
        this.bufferService = bufferService;
        this.loadSimulator = loadSimulator;
        this.anomalyMapper = anomalyMapper;
        this.dataMapper = dataMapper;
    }

    // ---------- 设备 CRUD ----------

    public record DeviceBody(String deviceId, String farmId, String name, String type,
                             Double tempLow, Double tempHigh, Double humidLow, Double humidHigh) {}

    @GetMapping("/devices")
    public ApiResponse<List<IotDevice>> devices(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.success(deviceService.list(tenantId, keyword));
    }

    @PostMapping("/devices")
    public ApiResponse<IotDevice> createDevice(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestBody IotController.DeviceBody body) {
        return ApiResponse.success(deviceService.create(tenantId, toUpsert(body)));
    }

    @PutMapping("/devices/{deviceId}")
    public ApiResponse<IotDevice> updateDevice(
            @PathVariable("deviceId") String deviceId,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestBody IotController.DeviceBody body) {
        return ApiResponse.success(deviceService.update(tenantId, deviceId, toUpsert(body)));
    }

    @DeleteMapping("/devices/{deviceId}")
    public ApiResponse<Boolean> deleteDevice(
            @PathVariable("deviceId") String deviceId,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId) {
        return ApiResponse.success(deviceService.delete(tenantId, deviceId));
    }

    private IotDeviceService.DeviceUpsert toUpsert(IotController.DeviceBody body) {
        return new IotDeviceService.DeviceUpsert(body.deviceId(), body.farmId(), body.name(),
                body.type(), body.tempLow(), body.tempHigh(), body.humidLow(), body.humidHigh());
    }

    // ---------- 数据上报（单点 = 设备直报；批量 = 网关汇聚） ----------

    public record IngestBody(String tenantId, String farmId, String deviceId,
                             String collectedAt, List<IotIngestService.MetricPoint> metrics) {}

    @PostMapping("/ingest")
    public ApiResponse<IotIngestService.IngestResult> ingest(@RequestBody IngestBody body) {
        IotDevice device = deviceService.require(
                body.tenantId() == null ? "1001" : body.tenantId(), body.deviceId());
        IotIngestService.IngestRequest request = new IotIngestService.IngestRequest(
                device.getTenantId(), device.getFarmId(), device.getDeviceId(),
                body.collectedAt(), body.metrics());
        IotIngestService.IngestResult result = ingestService.ingest(request);
        // 正常指标点走缓冲批量刷盘（与压测/批量链路一致），异常事件由 ingest 即时落库
        for (var metric : body.metrics()) {
            if (result.anomalies().stream().noneMatch(a -> a.getMetric().equals(metric.metric()))) {
                bufferService.offer(IotDeviceData.builder()
                        .tenantId(device.getTenantId()).farmId(device.getFarmId()).deviceId(device.getDeviceId())
                        .metric(metric.metric()).metricValue(metric.value()).unit(metric.unit())
                        .collectedAt(body.collectedAt() != null && !body.collectedAt().isBlank()
                                ? LocalDateTime.parse(body.collectedAt().replace(' ', 'T'))
                                : LocalDateTime.now())
                        .ingestTime(LocalDateTime.now())
                        .build());
            }
        }
        return ApiResponse.success(result);
    }

    /** 网关汇聚批量上报：一次最多 10000 点，进缓冲队列由刷盘器批量写库。 */
    @PostMapping("/ingest/batch")
    public ApiResponse<Map<String, Object>> ingestBatch(@RequestBody BatchBody body) {
        if (body.points() == null || body.points().isEmpty()) {
            return ApiResponse.error(400, "points 不能为空");
        }
        if (body.points().size() > 10000) {
            return ApiResponse.error(400, "单次批量上报上限 10000 点，请分批发送");
        }
        for (var point : body.points()) {
            deviceService.require(body.tenantId() == null ? "1001" : body.tenantId(), point.deviceId());
        }
        List<IotDeviceData> points = new java.util.ArrayList<>(body.points().size());
        for (var point : body.points()) {
            points.add(IotDeviceData.builder()
                    .tenantId(body.tenantId() == null ? "1001" : body.tenantId())
                    .farmId(point.farmId()).deviceId(point.deviceId())
                    .metric(point.metric()).metricValue(point.value()).unit(point.unit())
                    .collectedAt(point.collectedAt() != null
                            ? LocalDateTime.parse(point.collectedAt().replace(' ', 'T'))
                            : LocalDateTime.now())
                    .ingestTime(LocalDateTime.now())
                    .build());
        }
        int accepted = bufferService.offerBatch(points);
        return ApiResponse.success(Map.of("accepted", accepted, "buffered", bufferService.buffered()));
    }

    public record BatchPoint(String farmId, String deviceId, String metric,
                             Double value, String unit, String collectedAt) {}

    public record BatchBody(String tenantId, List<BatchPoint> points) {}

    // ---------- 异常 / 数据查询 ----------

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

    // ---------- 压测器 ----------

    public record LoadBody(int deviceCount, int pointsPerTick, long intervalMs, int durationSeconds) {}

    @PostMapping("/load/start")
    public ApiResponse<IotLoadSimulatorService.LoadStats> loadStart(@RequestBody LoadBody body) {
        return ApiResponse.success(loadSimulator.start(new IotLoadSimulatorService.LoadConfig(
                body.deviceCount(), body.pointsPerTick(), body.intervalMs(), body.durationSeconds())));
    }

    @PostMapping("/load/stop")
    public ApiResponse<IotLoadSimulatorService.LoadStats> loadStop() {
        return ApiResponse.success(loadSimulator.stop());
    }

    @GetMapping("/load/status")
    public ApiResponse<IotLoadSimulatorService.LoadStats> loadStatus() {
        return ApiResponse.success(loadSimulator.status());
    }

    /** 一次性灌入：直接批量 INSERT，返回真实行/秒（回答"20 亿怎么扛"的实测口径）。 */
    @PostMapping("/load/burst")
    public ApiResponse<Map<String, Object>> loadBurst(
            @RequestParam(value = "count", defaultValue = "50000") int count,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId) {
        return ApiResponse.success(loadSimulator.burst(Math.min(count, 500000), tenantId, "burst_farm"));
    }

    @GetMapping("/buffer/status")
    public ApiResponse<Map<String, Object>> bufferStatus() {
        return ApiResponse.success(Map.of(
                "buffered", bufferService.buffered(),
                "generated", bufferService.totalGenerated(),
                "flushed", bufferService.totalFlushed(),
                "dropped", bufferService.totalDropped(),
                "lastFlushSize", bufferService.lastFlushSize(),
                "lastFlushMs", bufferService.lastFlushMs()));
    }
}
