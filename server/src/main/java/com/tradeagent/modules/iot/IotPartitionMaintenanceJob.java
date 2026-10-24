package com.tradeagent.modules.iot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * IoT 分区维护任务：每日提前 7 天预建 iot_device_data 的按天分区，防止写入打满 pmax。
 * 历史分区（>30 天）按需归档（本演示保留，生产按合规要求归档后 DROP）。
 */
@Slf4j
@Component
public class IotPartitionMaintenanceJob {

    private final JdbcTemplate jdbcTemplate;

    public IotPartitionMaintenanceJob(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    }

    @Scheduled(cron = "${iot.partition-cron:0 10 0 * * ?}")
    public void preCreatePartitions() {
        if (jdbcTemplate == null) return;
        for (int i = 1; i <= 7; i++) {
            LocalDate day = LocalDate.now().plusDays(i);
            String name = "p" + day.toString().replace("-", "");
            String lessThan = day.plusDays(1).toString();
            try {
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = DATABASE() "
                                + "AND TABLE_NAME = 'iot_device_data' AND PARTITION_NAME = ?",
                        Integer.class, name);
                if (exists != null && exists > 0) continue;
                jdbcTemplate.execute(String.format(
                        "ALTER TABLE iot_device_data ADD PARTITION (PARTITION %s VALUES LESS THAN (TO_DAYS('%s')))",
                        name, lessThan));
                log.info("【IoT 分区】预建分区 {} ({})", name, lessThan);
            } catch (Exception e) {
                log.warn("【IoT 分区】预建 {} 失败: {}", name, e.getMessage());
            }
        }
    }
}
