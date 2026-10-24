package com.tradeagent.modules.iot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IoT 数据批量缓冲写入器：入库与存储解耦的核心。
 *
 * 链路：MQTT/HTTP 上报 → 内存有界队列（背压：满了拒绝并计数）→ 定时批量刷盘
 * （JDBC batchUpdate + rewriteBatchedStatements，MySQL 单表实测可达数万行/秒）。
 *
 * 队列满 = 上游消费不过来 → 返回 false 由入口侧选择丢弃或告警，绝不阻塞设备上报线程。
 * 按天 RANGE 分区表配合：老分区可整段归档/丢弃，写入永远打最新分区，无索引碎片问题。
 */
@Slf4j
@Service
public class IotDataBufferService {

    private static final int QUEUE_CAPACITY = 200_000;
    private static final int FLUSH_BATCH_SIZE = 5_000;

    private final BlockingQueue<IotDeviceData> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final JdbcTemplate jdbcTemplate;
    private final IotDeviceDataMapper dataMapper;

    private final AtomicLong totalGenerated = new AtomicLong();
    private final AtomicLong totalFlushed = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private volatile long lastFlushSize = 0;
    private volatile long lastFlushMs = 0;

    public IotDataBufferService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                IotDeviceDataMapper dataMapper) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        this.dataMapper = dataMapper;
    }

    /** 入队（非阻塞）。返回 false = 队列满（背压），调用方计数丢弃。 */
    public boolean offer(IotDeviceData data) {
        totalGenerated.incrementAndGet();
        boolean ok = queue.offer(data);
        if (!ok) totalDropped.incrementAndGet();
        return ok;
    }

    /** 批量入队（压测/批量上报），返回成功入队数。 */
    public int offerBatch(List<IotDeviceData> points) {
        int ok = 0;
        for (IotDeviceData point : points) {
            totalGenerated.incrementAndGet();
            if (queue.offer(point)) ok++;
            else totalDropped.incrementAndGet();
        }
        return ok;
    }

    /** 定时刷盘：每秒把队列写入 MySQL 分区表（单条 SQL 批量 INSERT）。 */
    @Scheduled(fixedDelayString = "${iot.flush-interval-ms:1000}")
    public void flush() {
        if (queue.isEmpty()) return;
        List<IotDeviceData> batch = new ArrayList<>(Math.min(queue.size(), FLUSH_BATCH_SIZE));
        queue.drainTo(batch, FLUSH_BATCH_SIZE);
        if (batch.isEmpty()) return;
        long start = System.nanoTime();
        if (jdbcTemplate != null) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO iot_device_data (tenant_id, farm_id, device_id, metric, metric_value, unit, collected_at, ingest_time) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    batch, batch.size(),
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
        }
        long cost = Math.max(1, (System.nanoTime() - start) / 1_000_000);
        totalFlushed.addAndGet(batch.size());
        lastFlushSize = batch.size();
        lastFlushMs = cost;
        log.debug("【IoT 刷盘】{} 行 / {} ms", batch.size(), cost);
    }

    public int buffered() {
        return queue.size();
    }

    public long totalGenerated() { return totalGenerated.get(); }
    public long totalFlushed() { return totalFlushed.get(); }
    public long totalDropped() { return totalDropped.get(); }
    public long lastFlushSize() { return lastFlushSize; }
    public long lastFlushMs() { return lastFlushMs; }
}
