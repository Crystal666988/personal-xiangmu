-- 设备影子: 离线指令不丢 (desired 期望态 vs reported 上报态, version 乐观锁)
CREATE TABLE IF NOT EXISTS iot_device_shadow (
    device_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    desired_json  MEDIUMTEXT NULL COMMENT '期望状态 (云端下发的指令/参数)',
    reported_json MEDIUMTEXT NULL COMMENT '上报状态 (设备最新汇报)',
    version       BIGINT NOT NULL DEFAULT 0,
    pending_json  MEDIUMTEXT NULL COMMENT '待设备执行的期望差量',
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备影子';

-- 指令幂等: 同一设备同一 messageId 只执行一次 (MQTT QoS1 至少一次 => 可能重复)
ALTER TABLE iot_command_log
    ADD COLUMN idempotency_key VARCHAR(80) NULL,
    ADD UNIQUE KEY uk_iot_cmd_idem (device_id, idempotency_key);
