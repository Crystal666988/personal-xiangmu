package com.tradeagent.modules.iot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 冷热分层：每小时把上一个整小时的原始数据降采样成 (min/max/avg/count) 写入
 * iot_metric_hourly（温数据层）。查询曲线走本表，原始表到期按分区归档/删除。
 */
@Slf4j
@Service
public class IotDownsamplingService {

    private final JdbcTemplate jdbc;

    public IotDownsamplingService(ObjectProvider<JdbcTemplate> provider) {
        this.jdbc = provider.getIfAvailable();
    }

    @Scheduled(cron = "${iot.downsample-cron:0 5 * * * ?}")
    public void hourly() {
        if (jdbc == null) return;
        try {
            downsampleLastHour();
        } catch (Exception e) {
            log.warn("【降采样】失败: {}", e.getMessage());
        }
    }

    /** 聚合上一个完整小时；INSERT IGNORE 保证任务重跑幂等。 */
    public int downsampleLastHour() {
        LocalDateTime end = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        LocalDateTime start = end.minusHours(1);
        int rows = jdbc.update("""
                INSERT IGNORE INTO iot_metric_hourly
                    (tenant_id, farm_id, device_id, metric, hour_start, min_v, max_v, avg_v, sample_count)
                SELECT tenant_id, farm_id, device_id, metric, ?,
                       MIN(metric_value), MAX(metric_value), AVG(metric_value), COUNT(*)
                FROM iot_device_data
                WHERE collected_at >= ? AND collected_at < ?
                GROUP BY tenant_id, farm_id, device_id, metric
                """, start, start, end);
        log.info("【降采样】{} ~ {} 生成 {} 行小时聚合", start, end, rows);
        return rows;
    }

    /** 温数据查询：设备的小时曲线（前端图表直接消费）。 */
    public List<Map<String, Object>> hourlySeries(String deviceId, String metric, int hours) {
        if (jdbc == null) return List.of();
        return jdbc.queryForList("""
                SELECT hour_start, min_v, max_v, avg_v, sample_count
                FROM iot_metric_hourly
                WHERE device_id = ? AND metric = ?
                ORDER BY hour_start DESC LIMIT ?
                """, deviceId, metric, Math.min(Math.max(hours, 1), 168));
    }
}
