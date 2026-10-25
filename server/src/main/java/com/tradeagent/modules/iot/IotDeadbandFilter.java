package com.tradeagent.modules.iot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变化上报死区过滤器（Reporting Deadband）—— 工业物联网标准节能手段。
 *
 * 价值：鸡舍温度 25.01→25.02→25.03 这种抖动没有存储价值，
 * 每条都入库只会把 20 亿/天放大成 200 亿/天。只有三类点值得持久化：
 *   1) 变化超过死区阈值（温度 0.5℃ / 湿度 2% / 氨气 1ppm / CO2 100ppm）；
 *   2) 心跳兜底：距上次持久化超过 5 分钟，即使没变化也写一条（证明设备活着）；
 *   3) 异常值：规则引擎判出异常的点永不过滤（告警证据链必须完整）。
 *
 * 注意：过滤只作用于"持久化"，规则引擎对每个上报值都会评估——
 * 抖动值不会入库，但若抖动值本身越界照样触发告警。
 * 集群部署时 lastValueCache 应换 Redis（当前为单机演示实现）。
 */
@Slf4j
@Service
public class IotDeadbandFilter {

    /** 各指标死区阈值（变化小于此值不上报） */
    private static final Map<String, Double> DEADBANDS = Map.of(
            "temperature", 0.5,
            "humidity", 2.0,
            "nh3", 1.0,
            "co2", 100.0);

    /** 心跳兜底间隔：即使无变化也最长这么久持久化一次 */
    private static final java.time.Duration HEARTBEAT = java.time.Duration.ofMinutes(5);

    private record LastPoint(double value, LocalDateTime persistedAt) {}

    private final Map<String, LastPoint> lastPersisted = new ConcurrentHashMap<>();

    private static String key(String deviceId, String metric) {
        return deviceId + ":" + metric;
    }

    /** 判定该数据点是否应当持久化。isAnomaly = 规则引擎已判定越界（永不过滤）。 */
    public boolean shouldPersist(String deviceId, String metric, double value, boolean isAnomaly) {
        if (isAnomaly) return true;
        String k = key(deviceId, metric);
        LastPoint last = lastPersisted.get(k);
        LocalDateTime now = LocalDateTime.now();
        if (last == null) {
            lastPersisted.put(k, new LastPoint(value, now));
            return true;
        }
        // 心跳兜底：长时间没有变化也持久化一条，证明链路与设备存活
        if (java.time.Duration.between(last.persistedAt(), now).compareTo(HEARTBEAT) > 0) {
            lastPersisted.put(k, new LastPoint(value, now));
            return true;
        }
        double deadband = DEADBANDS.getOrDefault(metric, 0.0);
        if (Math.abs(value - last.value()) >= deadband) {
            lastPersisted.put(k, new LastPoint(value, now));
            return true;
        }
        return false;
    }

    /** 统计口径：被过滤了多少点（监控页展示节能效果）。 */
    private final java.util.concurrent.atomic.AtomicLong filteredCount = new java.util.concurrent.atomic.AtomicLong();

    public void recordFiltered() {
        filteredCount.incrementAndGet();
    }

    public long filteredCount() {
        return filteredCount.get();
    }
}
