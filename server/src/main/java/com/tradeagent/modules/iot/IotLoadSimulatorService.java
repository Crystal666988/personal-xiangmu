package com.tradeagent.modules.iot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 虚拟设备群压测器：回答"一天 20 亿数据入库怎么模拟"。
 *
 * 原理：拉起 N 台虚拟设备（温氏场景 = 环控器每分钟上报温湿度/氨气/CO2 四指标），
 * 按可配置速率生成数据点推进 IotDataBufferService 缓冲队列，由刷盘器批量写 MySQL。
 * 实测吞吐数字（行/秒）直接反映当前链路（无 Kafka/TDengine）的入库能力上限。
 *
 * 诚实结论：压测器验证的是"管道"吞吐；生产 20 亿/天 = 23 万点/秒 的持久层应该是
 * Kafka 缓冲 + TDengine/ClickHouse 时序库，MySQL 分区表作为演示与告警热数据存储。
 */
@Slf4j
@Service
public class IotLoadSimulatorService {

    private final IotDataBufferService buffer;
    private final IotDeviceDataMapper dataMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong rateLimitDropped = new AtomicLong();
    private final Map<String, String> configs = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private volatile String currentRunId;

    private static final String[] METRICS = {"temperature", "humidity", "nh3", "co2"};

    public IotLoadSimulatorService(IotDataBufferService buffer,
                                   ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                   IotDeviceDataMapper dataMapper) {
        this.buffer = buffer;
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        this.dataMapper = dataMapper;
    }

    public record LoadConfig(int deviceCount, int pointsPerTick, long intervalMs, int durationSeconds) {}

    public record LoadStats(String runId, boolean running, String config, long generated,
                            long buffered, long flushed, long dropped, long lastFlushSize,
                            long lastFlushMs, long elapsedSeconds, double rowsPerSecond) {}

    public synchronized LoadStats start(LoadConfig config) {
        if (running.get()) throw new IllegalStateException("压测已在运行中，请先停止");
        if (config.deviceCount() <= 0 || config.pointsPerTick() <= 0 || config.intervalMs() <= 0) {
            throw new IllegalArgumentException("deviceCount / pointsPerTick / intervalMs 必须为正数");
        }
        String runId = "load_" + System.currentTimeMillis();
        running.set(true);
        generated.set(0);
        rateLimitDropped.set(0);
        configs.clear();
        configs.put("deviceCount", String.valueOf(config.deviceCount()));
        configs.put("pointsPerTick", String.valueOf(config.pointsPerTick()));
        configs.put("intervalMs", String.valueOf(config.intervalMs()));
        configs.put("durationSeconds", String.valueOf(config.durationSeconds()));
        currentRunId = runId;
        long startMs = System.currentTimeMillis();

        Thread worker = new Thread(() -> {
            long deadline = config.durationSeconds() > 0
                    ? startMs + config.durationSeconds() * 1000L : Long.MAX_VALUE;
            long tick = 0;
            while (running.get() && System.currentTimeMillis() < deadline) {
                long tickStart = System.currentTimeMillis();
                LocalDateTime now = LocalDateTime.now();
                List<IotDeviceData> points = new ArrayList<>(config.pointsPerTick());
                for (int i = 0; i < config.pointsPerTick(); i++) {
                    String deviceId = "load_dev_" + random.nextInt(config.deviceCount());
                    String metric = METRICS[(int) (tick % METRICS.length)];
                    double value = switch (metric) {
                        case "temperature" -> 20 + random.nextDouble() * 14;
                        case "humidity" -> 45 + random.nextDouble() * 40;
                        case "nh3" -> random.nextDouble() * 18;
                        default -> 500 + random.nextDouble() * 2000;
                    };
                    points.add(IotDeviceData.builder()
                            .tenantId("1001").farmId("load_farm_" + (i % 10))
                            .deviceId(deviceId).metric(metric).metricValue(value).unit("")
                            .collectedAt(now).ingestTime(now)
                            .build());
                }
                buffer.offerBatch(points);
                generated.addAndGet(points.size());
                tick++;
                long cost = System.currentTimeMillis() - tickStart;
                long sleep = config.intervalMs() - cost;
                if (sleep > 0) {
                    try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
                }
            }
            running.set(false);
            log.info("【IoT 压测】{} 结束, 生成 {} 点", runId, generated.get());
        }, "iot-load-" + runId);
        worker.setDaemon(true);
        worker.start();
        return status();
    }

    public LoadStats stop() {
        running.set(false);
        return status();
    }

    public LoadStats status() {
        return new LoadStats(currentRunId, running.get(),
                "devices=" + configs.getOrDefault("deviceCount", "-")
                        + " points/tick=" + configs.getOrDefault("pointsPerTick", "-")
                        + " interval=" + configs.getOrDefault("intervalMs", "-") + "ms",
                generated.get(), buffer.buffered(), buffer.totalFlushed(), buffer.totalDropped(),
                buffer.lastFlushSize(), buffer.lastFlushMs(), 0, 0);
    }

    /** 一次性灌入 count 个数据点（穿透缓冲直达批量刷盘），返回真实写库吞吐。 */
    public Map<String, Object> burst(int count, String tenantId, String farmId) {
        LocalDateTime now = LocalDateTime.now();
        List<IotDeviceData> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String metric = METRICS[i % METRICS.length];
            double value = switch (metric) {
                case "temperature" -> 20 + random.nextDouble() * 14;
                case "humidity" -> 45 + random.nextDouble() * 40;
                case "nh3" -> random.nextDouble() * 18;
                default -> 500 + random.nextDouble() * 2000;
            };
            points.add(IotDeviceData.builder()
                    .tenantId(tenantId).farmId(farmId).deviceId("burst_dev_" + (i % 500))
                    .metric(metric).metricValue(value).unit("")
                    .collectedAt(now).ingestTime(now)
                    .build());
        }
        if (jdbcTemplate == null) return Map.of("count", count, "costMs", 0L, "rowsPerSecond", 0L);
        long start = System.nanoTime();
        jdbcTemplate.batchUpdate(
                "INSERT INTO iot_device_data (tenant_id, farm_id, device_id, metric, metric_value, unit, collected_at, ingest_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                points, 5000,
                (ps, point) -> {
                    ps.setString(1, point.getTenantId());
                    ps.setString(2, point.getFarmId());
                    ps.setString(3, point.getDeviceId());
                    ps.setString(4, point.getMetric());
                    ps.setDouble(5, point.getMetricValue());
                    ps.setString(6, point.getUnit());
                    ps.setTimestamp(7, Timestamp.valueOf(point.getCollectedAt()));
                    ps.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()));
                });
        long ms = Math.max(1, (System.nanoTime() - start) / 1_000_000);
        long perSec = count * 1000L / ms;
        log.info("【IoT 压测】burst {} 行 / {} ms = {} 行/秒", count, ms, perSec);
        return Map.of("count", count, "costMs", ms, "rowsPerSecond", perSec);
    }
}
