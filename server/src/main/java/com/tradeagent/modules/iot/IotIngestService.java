package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * IoT 数据入库 + 规则评估 + 异常落库 + 飞书人工预警。
 *
 * 入口双路：设备 MQTT 上报（IotMqttGateway，启用 MQTT 时）或 HTTP 模拟上报（IotController），
 * 两者汇聚到本服务，后续链路完全一致。
 *
 * 事件解耦：数据点入库后发布 DeviceDataEvent（进程内事件总线）；生产部署可将
 * 发布器替换为 Kafka 生产者（IotKafkaConfig），消费侧无感。
 */
@Slf4j
@Service
public class IotIngestService {

    private final IotDeviceDataMapper dataMapper;
    private final IotAnomalyEventMapper anomalyMapper;
    private final IotThresholdRuleEngine ruleEngine;
    private final IotFeishuAlertService alertService;
    private final IotAlarmSuppressor alarmSuppressor;

    public IotIngestService(IotDeviceDataMapper dataMapper,
                            IotAnomalyEventMapper anomalyMapper, IotThresholdRuleEngine ruleEngine,
                            IotFeishuAlertService alertService, IotAlarmSuppressor alarmSuppressor) {
        this.dataMapper = dataMapper;
        this.anomalyMapper = anomalyMapper;
        this.ruleEngine = ruleEngine;
        this.alertService = alertService;
        this.alarmSuppressor = alarmSuppressor;
    }

    /** 上报载荷：一次上报可带多个指标（环控器一次上温湿度氨气一组）。 */
    public record MetricPoint(String metric, Double value, String unit) {}

    public record IngestRequest(String tenantId, String farmId, String deviceId, String deviceName, String location,
                                String collectedAt, List<MetricPoint> metrics) {
        public IngestRequest(String tenantId, String farmId, String deviceId,
                             String collectedAt, List<MetricPoint> metrics) {
            this(tenantId, farmId, deviceId, deviceId, farmId, collectedAt, metrics);
        }
    }

    public record IngestResult(int pointsSaved, List<IotAnomalyEvent> anomalies,
                               IotFeishuAlertService.AlertResult alertResult) {}

    public IngestResult ingest(IngestRequest request) {
        if (request == null || request.tenantId() == null || request.deviceId() == null
                || request.metrics() == null || request.metrics().isEmpty()) {
            throw new IllegalArgumentException("tenantId/deviceId/metrics 不能为空");
        }
        LocalDateTime collectedAt = request.collectedAt() != null
                ? LocalDateTime.parse(request.collectedAt().replace(' ', 'T'))
                : LocalDateTime.now();

        // 时钟校验：设备本地时钟漂移防护——未来超过 10 分钟或早于 7 天的采集时间直接拒绝
        // (乱序/漂移时间会写错按天分区，且污染按日聚合口径)
        LocalDateTime now = LocalDateTime.now();
        if (collectedAt.isAfter(now.plusMinutes(10))) {
            throw new IllegalArgumentException("设备时钟漂移: 采集时间 " + collectedAt
                    + " 超前当前时间超过 10 分钟，请校准设备 NTP");
        }
        if (collectedAt.isBefore(now.minusDays(7))) {
            throw new IllegalArgumentException("采集时间 " + collectedAt + " 早于 7 天前 (超出分区保留窗口)，已拒绝");
        }

        // 数据中断检测：与该设备上次上报间隔超阈值 → 掉线异常
        LocalDateTime lastSeen = lastSeenBefore(request.deviceId(), collectedAt);
        final List<IotAnomalyEvent> allAnomalies = new ArrayList<>();
        List<IotAnomalyEvent> anomalies = allAnomalies;
        ruleEngine.evaluateGap(lastSeen, collectedAt).ifPresent(gap ->
                allAnomalies.add(IotAnomalyEvent.builder()
                        .tenantId(request.tenantId()).farmId(request.farmId()).deviceId(request.deviceId())
                        .metric(gap.metric()).metricValue(gap.value()).ruleCode(gap.ruleCode())
                        .severity(gap.severity()).message(gap.message())
                        .feishuState("PENDING").createTime(LocalDateTime.now())
                        .build()));

        // 逐指标入库 + 阈值评估
        int saved = 0;
        for (MetricPoint point : request.metrics()) {
            if (point.metric() == null || point.value() == null) continue;
            dataMapper.insert(IotDeviceData.builder()
                    .tenantId(request.tenantId()).farmId(request.farmId()).deviceId(request.deviceId())
                    .metric(point.metric()).metricValue(point.value()).unit(point.unit())
                    .collectedAt(collectedAt).ingestTime(LocalDateTime.now())
                    .build());
            saved++;
            ruleEngine.evaluate(point.metric(), point.value()).forEach(eval ->
                    allAnomalies.add(IotAnomalyEvent.builder()
                            .tenantId(request.tenantId()).farmId(request.farmId()).deviceId(request.deviceId())
                            .metric(eval.metric()).metricValue(eval.value()).ruleCode(eval.ruleCode())
                            .severity(eval.severity()).message(eval.message())
                            .feishuState("PENDING").createTime(LocalDateTime.now())
                            .build()));
        }

        // 异常全部落库留痕; 告警抑制(连续3轮确认+10分钟静默, DATA_GAP 直通)后合并推送飞书
        if (!anomalies.isEmpty()) {
            // 全部落库留痕; 抑制器只决定"哪些值得推飞书"——返回值仍包含全部异常(可见性)
            List<IotAnomalyEvent> alertable = new java.util.ArrayList<>();
            for (IotAnomalyEvent event : anomalies) {
                anomalyMapper.insert(event);
                boolean immediate = "DATA_GAP".equals(event.getRuleCode());
                if (alarmSuppressor.onBreach(request.deviceId(), event.getRuleCode(), immediate)
                        == IotAlarmSuppressor.Decision.ALERT) {
                    alertable.add(event);
                }
            }
            if (!alertable.isEmpty()) {
            var alert = alertService.pushAnomalyAlert(request.farmId(), request.deviceId(), alertable,
                    request.deviceName(), request.location());
            alertable.forEach(a -> {
                a.setFeishuState(alert.state().name());
                if (a.getId() != null) {
                    anomalyMapper.updateById(IotAnomalyEvent.builder()
                            .id(a.getId()).feishuState(alert.state().name()).build());
                }
            });
            log.info("【IoT 入库】设备 {} 保存 {} 点, 异常 {} 条 (推送 {}), 预警: {}",
                    request.deviceId(), saved, anomalies.size(), alertable.size(), alert.state());
            } else {
                log.info("【IoT 入库】设备 {} 保存 {} 点, 异常 {} 条 (全部被抑制, 仅留痕)",
                        request.deviceId(), saved, anomalies.size());
            }
        }
        return new IngestResult(saved, anomalies,
                anomalies.isEmpty() ? new IotFeishuAlertService.AlertResult(
                        IotFeishuAlertService.AlertState.SENT, "无异常，无需预警")
                : null);
    }

    private LocalDateTime lastSeenBefore(String deviceId, LocalDateTime before) {
        IotDeviceData latest = dataMapper.selectOne(new LambdaQueryWrapper<IotDeviceData>()
                .eq(IotDeviceData::getDeviceId, deviceId)
                .lt(IotDeviceData::getCollectedAt, before)
                .orderByDesc(IotDeviceData::getCollectedAt)
                .last("LIMIT 1"));
        return latest != null ? latest.getCollectedAt() : null;
    }
}
