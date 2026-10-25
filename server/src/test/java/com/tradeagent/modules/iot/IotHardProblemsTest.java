package com.tradeagent.modules.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IoT 工业难点 1-4 单测：变化上报死区 / 设备影子 / 指令幂等 / 时钟漂移。
 */
class IotHardProblemsTest {

    private final ObjectMapper json = new ObjectMapper();

    // ================= 难点1: 变化上报死区 =================

    @Test
    @DisplayName("死区: 抖动值(±0.1℃)被过滤, 超阈值(≥0.5℃)放行, 异常值豁免")
    void deadbandFiltersNoise() {
        IotDeadbandFilter filter = new IotDeadbandFilter();
        // 第一个点必持久化
        assertTrue(filter.shouldPersist("d1", "temperature", 25.0, false));
        // 抖动 ±0.1℃ 被过滤
        assertFalse(filter.shouldPersist("d1", "temperature", 25.1, false));
        assertFalse(filter.shouldPersist("d1", "temperature", 25.0, false));
        // 变化 ≥ 0.5℃ 放行
        assertTrue(filter.shouldPersist("d1", "temperature", 25.6, false));
        // 异常值即使无变化也豁免（告警证据链）
        assertTrue(filter.shouldPersist("d1", "temperature", 25.7, true));
    }

    @Test
    @DisplayName("死区: 不同指标独立判定, 未知指标默认全放行")
    void deadbandPerMetric() {
        IotDeadbandFilter filter = new IotDeadbandFilter();
        assertTrue(filter.shouldPersist("d2", "temperature", 20.0, false));
        assertTrue(filter.shouldPersist("d2", "humidity", 60.0, false));
        assertFalse(filter.shouldPersist("d2", "temperature", 20.2, false));   // 温度死区内
        assertTrue(filter.shouldPersist("d2", "humidity", 63.0, false));       // 湿度独立: +3% 超阈值
        assertTrue(filter.shouldPersist("d2", "weight", 5.0, false));          // 未知指标默认放行
    }

    // ================= 难点2: 设备影子 =================

    @Test
    @DisplayName("影子: 离线下发指令 -> pending 出现; 设备上报追平 -> pending 清空")
    void shadowPendingLifecycle() throws Exception {
        IotDeviceShadowMapper mapper = Mockito.mock(IotDeviceShadowMapper.class);
        AtomicReference<IotDeviceShadow> store = new AtomicReference<>();
        Mockito.when(mapper.selectById(Mockito.any())).thenAnswer(inv -> store.get());
        Mockito.doAnswer(inv -> { store.set(inv.getArgument(0)); return 1; })
                .when(mapper).updateById(Mockito.any(IotDeviceShadow.class));
        Mockito.when(mapper.insert(Mockito.any(IotDeviceShadow.class))).thenAnswer(inv -> {
            store.set(inv.getArgument(0)); return 1;
        });
        IotShadowService shadow = new IotShadowService(mapper);

        // 设备离线时云端下发调温指令
        shadow.mergeDesired("1001", "dev_s", "{\"command\":\"set_temp\",\"targetTemp\":26}");
        IotDeviceShadow s1 = shadow.get("1001", "dev_s");
        long v1 = s1.getVersion();
        assertTrue(s1.getPendingJson().contains("targetTemp"), "离线指令应出现在 pending");

        // 设备上线，上报了状态但还没执行调温 → pending 仍在
        shadow.mergeReported("1001", "dev_s",
                List.of(new IotIngestService.MetricPoint("temperature", 25.0, "℃")));
        IotDeviceShadow s2 = shadow.get("1001", "dev_s");
        assertTrue(s2.getPendingJson().contains("targetTemp"), "未执行前 pending 保持");
        assertTrue(s2.getReportedJson().contains("temperature"));

        // 设备执行完毕上报 targetTemp=26 → 追平 desired, pending 清空
        shadow.mergeReported("1001", "dev_s",
                List.of(new IotIngestService.MetricPoint("targetTemp", 26.0, "")));
        IotDeviceShadow s3 = shadow.get("1001", "dev_s");
        assertEquals(0, json.readTree(s3.getPendingJson()).size(), "reported 追平后 pending 应清空");
        assertTrue(s3.getVersion() > v1, "影子版本号应递增");
    }

    // ================= 难点3: 指令幂等 =================

    @Test
    @DisplayName("幂等: 同设备同 messageId 重放不重复下发 (QoS1 至少一次送达防护)")
    void commandIdempotency() {
        // JDK24 下 Mockito 无法代理类, 用 reflect.Proxy 桩 Mapper 接口
        final IotCommandLog existing = IotCommandLog.builder()
                .deviceId("dev_s").state("SENT_MQTT").commandJson("{}").build();
        final boolean[] insertCalled = {false};
        IotCommandLogMapper logMapper = (IotCommandLogMapper) java.lang.reflect.Proxy.newProxyInstance(
                IotCommandLogMapper.class.getClassLoader(), new Class<?>[]{IotCommandLogMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectOne" -> idemQueryHit.get() ? existing : null;
                    case "insert" -> { insertCalled[0] = true; yield 1; }
                    default -> null;
                });
        IotDeviceMapper deviceMapper = (IotDeviceMapper) java.lang.reflect.Proxy.newProxyInstance(
                IotDeviceMapper.class.getClassLoader(), new Class<?>[]{IotDeviceMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> IotDevice.builder().deviceId("dev_s").tenantId("1001").build();
                    default -> null;
                });
        IotShadowService shadowService = new IotShadowService(null) {
            @Override public void mergeDesired(String t, String d, String c) {}
        };
        IotDeviceService deviceService = new IotDeviceService(deviceMapper);
        IotCommandService service = new IotCommandService(logMapper, deviceService,
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public IotMqttConfig getObject(Object... args) { return null; }
                    @Override public IotMqttConfig getObject() { return null; }
                    @Override public IotMqttConfig getIfAvailable() { return null; }
                    @Override public IotMqttConfig getIfUnique() { return null; }
                    @Override public java.util.Iterator<IotMqttConfig> iterator() { return java.util.List.<IotMqttConfig>of().iterator(); }
                }, shadowService);

        // 第一次: 无已有记录 → 真实下发并落库
        idemQueryHit.set(false);
        var fresh = service.send("1001", "dev_s", "{\"command\":\"set_temp\"}", null);
        assertFalse(fresh.duplicate());
        assertTrue(insertCalled[0]);

        // 重放: 同 messageId 已存在 → 幂等命中, 不再落库
        idemQueryHit.set(true);
        insertCalled[0] = false;
        var replay = service.send("1001", "dev_s", "{\"command\":\"set_temp\"}", "msg-001");
        assertTrue(replay.duplicate(), "同 messageId 重放应命中幂等");
        assertFalse(insertCalled[0], "幂等命中后不应重复插入指令日志");
    }

    /** 幂等查桩开关: 模拟 DB 里是否存在同 key 指令 */
    private static final java.util.concurrent.atomic.AtomicBoolean idemQueryHit =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ================= 难点4: 时钟漂移 =================

    @Test
    @DisplayName("时钟: 未来超过10分钟/早于7天的采集时间被拒绝")
    void clockDriftRejected() {
        IotDeviceDataMapper dataMapper = Mockito.mock(IotDeviceDataMapper.class);
        IotAnomalyEventMapper anomalyMapper = Mockito.mock(IotAnomalyEventMapper.class);
        IotThresholdRuleEngine engine = new IotThresholdRuleEngine();
        IotFeishuAlertService alert = new IotFeishuAlertService();
        IotIngestService service = new IotIngestService(dataMapper, anomalyMapper, engine, alert, new IotAlarmSuppressor(), d -> {});

        // 未来 30 分钟 → 拒绝
        var future = new IotIngestService.IngestRequest("1001", "f", "d",
                LocalDateTime.now().plusMinutes(30).toString(),
                List.of(new IotIngestService.MetricPoint("temperature", 25.0, "℃")));
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> service.ingest(future));
        assertTrue(e1.getMessage().contains("时钟漂移"));

        // 8 天前 → 拒绝 (超出分区窗口)
        var past = new IotIngestService.IngestRequest("1001", "f", "d",
                LocalDateTime.now().minusDays(8).toString(),
                List.of(new IotIngestService.MetricPoint("temperature", 25.0, "℃")));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> service.ingest(past));
        assertTrue(e2.getMessage().contains("7 天"));

        // 正常时间 (5 分钟内的轻微超前容忍) → 放行
        var ok = new IotIngestService.IngestRequest("1001", "f", "d",
                LocalDateTime.now().plusMinutes(5).toString(),
                List.of(new IotIngestService.MetricPoint("temperature", 25.0, "℃")));
        assertDoesNotThrow(() -> service.ingest(ok));
    }
}

// ================= 难点8: 告警抑制 =================

class AlarmSuppressionTest {

    @Test
    @org.junit.jupiter.api.DisplayName("抑制: 连续3轮越界才告警, 首两轮仅留痕; 静默期内不重复")
    void threeRoundConfirmAndSilence() {
        IotAlarmSuppressor sup = new IotAlarmSuppressor();
        assertEquals(IotAlarmSuppressor.Decision.RECORD_ONLY, sup.onBreach("d", "TEMPERATURE_HIGH", false));
        assertEquals(IotAlarmSuppressor.Decision.RECORD_ONLY, sup.onBreach("d", "TEMPERATURE_HIGH", false));
        assertEquals(IotAlarmSuppressor.Decision.ALERT, sup.onBreach("d", "TEMPERATURE_HIGH", false));
        // 静默期内第 4 轮不再推送
        assertEquals(IotAlarmSuppressor.Decision.RECORD_ONLY, sup.onBreach("d", "TEMPERATURE_HIGH", false));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("抑制: DATA_GAP 致命类直通告警")
    void dataGapImmediate() {
        IotAlarmSuppressor sup = new IotAlarmSuppressor();
        assertEquals(IotAlarmSuppressor.Decision.ALERT, sup.onBreach("d", "DATA_GAP", true));
    }
}
