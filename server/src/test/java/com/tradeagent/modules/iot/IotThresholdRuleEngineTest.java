package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 温氏 IoT 链路规则引擎单测：阈值越界/逼近/数据中断 + 入库链路异常生成（内存 Mapper 桩）。
 */
class IotThresholdRuleEngineTest {

    private final IotThresholdRuleEngine engine = new IotThresholdRuleEngine();

    @Test
    @DisplayName("温度 35.2℃ 超上限 → CRITICAL TEMP_HIGH")
    void highTemperatureIsCritical() {
        var results = engine.evaluate("temperature", 35.2);
        assertEquals(1, results.size());
        assertEquals("TEMPERATURE_HIGH", results.get(0).ruleCode());
        assertEquals("CRITICAL", results.get(0).severity());
    }

    @Test
    @DisplayName("温度 31℃ 逼近上限（区间 90%）→ WARN 观察项")
    void nearBoundaryIsWarn() {
        var results = engine.evaluate("temperature", 31.0);
        assertEquals(1, results.size());
        assertEquals("TEMPERATURE_HIGH_RISK", results.get(0).ruleCode());
        assertEquals("WARN", results.get(0).severity());
    }

    @Test
    @DisplayName("正常值 25℃ 不产生事件")
    void normalValueNoEvent() {
        assertTrue(engine.evaluate("temperature", 25.0).isEmpty());
    }

    @Test
    @DisplayName("氨气 25ppm 超标 → CRITICAL NH3_HIGH；未登记指标无规则不评估")
    void nh3AndUnknownMetric() {
        assertEquals("NH3_HIGH", engine.evaluate("nh3", 25.0).get(0).ruleCode());
        assertTrue(engine.evaluate("weight", 5.0).isEmpty());
    }

    @Test
    @DisplayName("数据中断 15 分钟 → CRITICAL DATA_GAP；5 分钟不触发")
    void dataGapDetection() {
        var now = LocalDateTime.now();
        assertTrue(engine.evaluateGap(now.minusMinutes(15), now).isPresent());
        assertTrue(engine.evaluateGap(now.minusMinutes(5), now).isEmpty());
    }

    @Test
    @DisplayName("入库链路：越界指标生成异常事件并落库（内存 Mapper 桩，预警状态回写）")
    void ingestGeneratesAnomaliesAndPersistsThem() {
        IotDeviceDataMapper dataMapper = Mockito.mock(IotDeviceDataMapper.class);
        IotAnomalyEventMapper anomalyMapper = Mockito.mock(IotAnomalyEventMapper.class);
        IotFeishuAlertService alertService = new IotFeishuAlertService();

        Mockito.when(dataMapper.selectOne(Mockito.any())).thenReturn(null);

        IotIngestService service = new IotIngestService(dataMapper, anomalyMapper, engine, alertService, new IotAlarmSuppressor(), d -> {});

        var request = new IotIngestService.IngestRequest("1001", "farm_001", "dev_env_01",
                LocalDateTime.now().toString(),
                List.of(new IotIngestService.MetricPoint("temperature", 35.2, "℃"),
                        new IotIngestService.MetricPoint("humidity", 60.0, "%")));
        var result = service.ingest(request);

        assertEquals(2, result.pointsSaved());
        assertEquals(1, result.anomalies().size());
        assertEquals("TEMPERATURE_HIGH", result.anomalies().get(0).getRuleCode());
        assertEquals("CRITICAL", result.anomalies().get(0).getSeverity());
        // 告警抑制: 首轮越界仅留痕(feishuState 保持 PENDING), 连续第 3 轮才尝试推送
        assertEquals("PENDING", result.anomalies().get(0).getFeishuState());
        IotIngestService.IngestResult second = service.ingest(request);
        assertEquals("PENDING", second.anomalies().get(0).getFeishuState());
        IotIngestService.IngestResult third = service.ingest(request);
        // 第 3 轮确认 → 尝试推送, 未配置 Webhook 如实标记 SKIPPED_NO_WEBHOOK
        assertEquals("SKIPPED_NO_WEBHOOK", third.anomalies().get(0).getFeishuState());
        Mockito.verify(anomalyMapper, Mockito.times(3)).insert(Mockito.any(IotAnomalyEvent.class));
        Mockito.verify(dataMapper, Mockito.times(6)).insert(Mockito.any(IotDeviceData.class));
    }

    @Test
    @DisplayName("数据中断异常：与上次上报间隔 15 分钟时生成 DATA_GAP 事件")
    void ingestDetectsDataGap() {
        IotDeviceDataMapper dataMapper = Mockito.mock(IotDeviceDataMapper.class);
        IotAnomalyEventMapper anomalyMapper = Mockito.mock(IotAnomalyEventMapper.class);
        IotFeishuAlertService alertService = new IotFeishuAlertService();

        LocalDateTime lastSeen = LocalDateTime.now().minusMinutes(20);
        Mockito.when(dataMapper.selectOne(Mockito.any())).thenReturn(
                IotDeviceData.builder().deviceId("dev_x").collectedAt(lastSeen).build());

        IotIngestService service = new IotIngestService(dataMapper, anomalyMapper, engine, alertService, new IotAlarmSuppressor(), d -> {});
        var request = new IotIngestService.IngestRequest("1001", "farm_001", "dev_x",
                LocalDateTime.now().toString(),
                List.of(new IotIngestService.MetricPoint("temperature", 25.0, "℃")));
        var result = service.ingest(request);

        assertTrue(result.anomalies().stream().anyMatch(a -> "DATA_GAP".equals(a.getRuleCode())));
    }

    @Test
    @DisplayName("阈值查询包装：规则引擎标签中文化")
    void labelLookup() {
        assertEquals("舍内温度", engine.evaluate("temperature", 40.0).get(0).label());
    }

}
