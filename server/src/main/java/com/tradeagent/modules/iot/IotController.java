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
    private final IotProductMapper productMapper;
    private final IotCommandService commandService;
    private final IotShadowService shadowService;
    private final IotDeadbandFilter deadbandFilter;
    private final IotOtaService otaService;
    private final IotDownsamplingService downsamplingService;
    private final IotDailySummaryService dailySummaryService;
    private final IotDeviceEventPublisher eventPublisher;

    public IotController(IotDeviceService deviceService, IotIngestService ingestService,
                         IotDataBufferService bufferService, IotLoadSimulatorService loadSimulator,
                         IotAnomalyEventMapper anomalyMapper, IotDeviceDataMapper dataMapper,
                         IotProductMapper productMapper, IotCommandService commandService,
                         IotShadowService shadowService, IotDeadbandFilter deadbandFilter,
                         IotOtaService otaService, IotDownsamplingService downsamplingService,
                         IotDailySummaryService dailySummaryService,
                         IotDeviceEventPublisher eventPublisher) {
        this.deviceService = deviceService;
        this.ingestService = ingestService;
        this.bufferService = bufferService;
        this.loadSimulator = loadSimulator;
        this.anomalyMapper = anomalyMapper;
        this.dataMapper = dataMapper;
        this.productMapper = productMapper;
        this.commandService = commandService;
        this.shadowService = shadowService;
        this.deadbandFilter = deadbandFilter;
        this.otaService = otaService;
        this.downsamplingService = downsamplingService;
        this.dailySummaryService = dailySummaryService;
        this.eventPublisher = eventPublisher;
    }

    // ---------- 设备 CRUD ----------

    public record DeviceBody(String deviceId, String farmId, String name, String type,
                             String productId, String location, String secret,
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
                body.type(), body.productId(), body.location(), body.secret(),
                body.tempLow(), body.tempHigh(), body.humidLow(), body.humidHigh());
    }

    // ---------- 产品型号 CRUD ----------

    public record ProductBody(String id, String name, String model, String protocol,
                              String vendor, String metricsJson) {}

    @GetMapping("/products")
    public ApiResponse<List<IotProduct>> products(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId) {
        return ApiResponse.success(productMapper.selectList(new LambdaQueryWrapper<IotProduct>()
                .eq(IotProduct::getTenantId, tenantId)
                .orderByDesc(IotProduct::getCreateTime)));
    }

    @PostMapping("/products")
    public ApiResponse<IotProduct> createProduct(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestBody ProductBody body) {
        if (body.id() == null || body.id().isBlank() || body.name() == null || body.name().isBlank()
                || body.model() == null || body.model().isBlank()) {
            throw new IllegalArgumentException("产品 ID / 名称 / 型号均为必填项");
        }
        IotProduct product = IotProduct.builder()
                .id(body.id().trim()).tenantId(tenantId)
                .name(body.name().trim()).model(body.model().trim())
                .protocol(body.protocol() == null || body.protocol().isBlank() ? "MQTT" : body.protocol().trim())
                .vendor(body.vendor()).metricsJson(body.metricsJson())
                .createTime(LocalDateTime.now())
                .build();
        productMapper.insert(product);
        return ApiResponse.success(product);
    }

    @PutMapping("/products/{id}")
    public ApiResponse<IotProduct> updateProduct(
            @PathVariable("id") String id,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestBody ProductBody body) {
        IotProduct product = productMapper.selectById(id);
        if (product == null || !tenantId.equals(product.getTenantId())) {
            throw new IllegalArgumentException("产品不存在");
        }
        product.setName(body.name().trim());
        product.setModel(body.model().trim());
        product.setProtocol(body.protocol() != null && !body.protocol().isBlank() ? body.protocol().trim() : product.getProtocol());
        product.setVendor(body.vendor());
        product.setMetricsJson(body.metricsJson());
        productMapper.updateById(product);
        return ApiResponse.success(product);
    }

    @DeleteMapping("/products/{id}")
    public ApiResponse<Boolean> deleteProduct(
            @PathVariable("id") String id,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId) {
        IotProduct product = productMapper.selectById(id);
        if (product == null || !tenantId.equals(product.getTenantId())) throw new IllegalArgumentException("产品不存在");
        long deviceCount = deviceService.countByProduct(tenantId, id);
        if (deviceCount > 0) throw new IllegalArgumentException("该产品下还有 " + deviceCount + " 台设备，请先删除设备");
        return ApiResponse.success(productMapper.deleteById(id) > 0);
    }

    // ---------- 指令下发（Downlink） ----------

    public record CommandBody(String commandJson, String idempotencyKey) {}

    @PostMapping("/devices/{deviceId}/command")
    public ApiResponse<IotCommandService.CommandResult> sendCommand(
            @PathVariable("deviceId") String deviceId,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestBody CommandBody body) {
        if (body.commandJson() == null || body.commandJson().isBlank()) {
            throw new IllegalArgumentException("指令内容不能为空");
        }
        return ApiResponse.success(commandService.send(tenantId, deviceId, body.commandJson(), body.idempotencyKey()));
    }

    // ---------- 设备影子 ----------

    @GetMapping("/devices/{deviceId}/shadow")
    public ApiResponse<IotDeviceShadow> deviceShadow(
            @PathVariable("deviceId") String deviceId,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId) {
        deviceService.require(tenantId, deviceId);
        return ApiResponse.success(shadowService.get(tenantId, deviceId));
    }

    @GetMapping("/devices/{deviceId}/commands")
    public ApiResponse<List<IotCommandLog>> deviceCommands(
            @PathVariable("deviceId") String deviceId,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.success(commandService.recentCommands(tenantId, deviceId, limit));
    }

    // ---------- 数据上报（单点 = 设备直报；批量 = 网关汇聚） ----------

    public record IngestBody(String tenantId, String farmId, String deviceId,
                             String collectedAt, List<IotIngestService.MetricPoint> metrics) {}

    @PostMapping("/ingest")
    public ApiResponse<Map<String, Object>> ingest(@RequestBody IngestBody body,
            @RequestHeader(value = "X-Device-Secret", required = false) String deviceSecret) {
        IotDevice device = deviceService.require(
                body.tenantId() == null ? "1001" : body.tenantId(), body.deviceId());
        // 一机一密认证: 设备配置了 secret 后, 上报必须携带匹配密钥 (防伪造设备写脏数据)
        if (device.getSecret() != null && !device.getSecret().isBlank()
                && !device.getSecret().equals(deviceSecret)) {
            throw new IllegalArgumentException("设备密钥校验失败: " + body.deviceId());
        }
        IotIngestService.IngestRequest request = new IotIngestService.IngestRequest(
                device.getTenantId(), device.getFarmId(), device.getDeviceId(),
                body.collectedAt(), body.metrics());
        IotIngestService.IngestResult result = ingestService.ingest(request);
        // 变化上报死区：抖动值不持久化（异常值豁免；心跳兜底见 IotDeadbandFilter）
        LocalDateTime collectedAt = body.collectedAt() != null && !body.collectedAt().isBlank()
                ? LocalDateTime.parse(body.collectedAt().replace(' ', 'T'))
                : LocalDateTime.now();
        int persisted = 0;
        for (var metric : body.metrics()) {
            boolean isAnomaly = result.anomalies().stream().anyMatch(a -> a.getMetric().equals(metric.metric()));
            if (deadbandFilter.shouldPersist(device.getDeviceId(), metric.metric(), metric.value(), isAnomaly)) {
                IotDeviceData point = IotDeviceData.builder()
                        .tenantId(device.getTenantId()).farmId(device.getFarmId()).deviceId(device.getDeviceId())
                        .metric(metric.metric()).metricValue(metric.value()).unit(metric.unit())
                        .collectedAt(collectedAt)
                        .ingestTime(LocalDateTime.now())
                        .build();
                bufferService.offer(point);
                persisted++;
            } else {
                deadbandFilter.recordFiltered();
            }
        }
        // 设备影子：合并 reported；若设备离线期间有漏执行的期望指令，返回 pending 让设备补执行
        String pendingDesired = shadowService.mergeReported(device.getTenantId(), device.getDeviceId(), body.metrics());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("pointsSaved", persisted);
        payload.put("filteredByDeadband", body.metrics().size() - persisted);
        payload.put("anomalies", result.anomalies());
        payload.put("pendingDesired", pendingDesired == null ? "" : pendingDesired);
        return ApiResponse.success(payload);
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

    // ---------- 每日设备总结 → 飞书多维表格 ----------

    @PostMapping("/summary/now")
    public ApiResponse<String> summaryNow(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestParam(value = "date", required = false) String date) {
        java.time.LocalDate day = date != null && !date.isBlank()
                ? java.time.LocalDate.parse(date) : java.time.LocalDate.now();
        return ApiResponse.success(dailySummaryService.summarizeAndPush(day, tenantId));
    }

    // ---------- 难点5: 冷热分层降采样 ----------

    @PostMapping("/downsample/now")
    public ApiResponse<Integer> downsampleNow() {
        return ApiResponse.success(downsamplingService.downsampleLastHour());
    }

    @GetMapping("/devices/{deviceId}/hourly")
    public ApiResponse<List<Map<String, Object>>> hourlySeries(
            @PathVariable("deviceId") String deviceId,
            @RequestParam("metric") String metric,
            @RequestParam(value = "hours", defaultValue = "24") int hours) {
        return ApiResponse.success(downsamplingService.hourlySeries(deviceId, metric, hours));
    }

    // ---------- 难点6: OTA 固件灰度 ----------

    public record FirmwareBody(String id, String version, String url, Long sizeBytes, String md5, Integer greyBatch) {}

    @PostMapping("/firmware")
    public ApiResponse<IotFirmware> createFirmware(@RequestBody FirmwareBody body) {
        return ApiResponse.success(otaService.create(body.id(), body.version(), body.url(),
                body.sizeBytes(), body.md5(), body.greyBatch() == null ? 10 : body.greyBatch()));
    }

    @GetMapping("/firmware")
    public ApiResponse<List<IotFirmware>> listFirmware() {
        return ApiResponse.success(otaService.list());
    }

    @PostMapping("/firmware/{id}/rollout")
    public ApiResponse<Integer> rolloutFirmware(
            @PathVariable("id") String id,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId) {
        return ApiResponse.success(otaService.rollout(tenantId, id));
    }

    @GetMapping("/firmware/{id}/progress")
    public ApiResponse<String> otaProgress(
            @PathVariable("id") String id,
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId) {
        return ApiResponse.success(otaService.progress(tenantId, id));
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
                "lastFlushMs", bufferService.lastFlushMs(),
                "deadbandFiltered", deadbandFilter.filteredCount()));
    }
}
