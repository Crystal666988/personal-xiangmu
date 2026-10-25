-- 设备型号/物模型表
CREATE TABLE IF NOT EXISTS iot_product (
    id           VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    name         VARCHAR(80) NOT NULL COMMENT '产品名称 (养殖环控器)',
    model        VARCHAR(80) NOT NULL COMMENT '设备型号 (WS-ENV-3000)',
    protocol     VARCHAR(32) NOT NULL DEFAULT 'MQTT',
    vendor       VARCHAR(80) NULL COMMENT '厂商',
    metrics_json MEDIUMTEXT NULL COMMENT '物模型: [{metric,label,unit,low,high}]',
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_iot_product_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备型号/物模型 (产品→设备 1:N)';

ALTER TABLE iot_device
    ADD COLUMN product_id  VARCHAR(64) NULL COMMENT '所属产品型号',
    ADD COLUMN location    VARCHAR(255) NULL COMMENT '安装地点';

CREATE TABLE IF NOT EXISTS iot_command_log (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    device_id   VARCHAR(64) NOT NULL,
    command_json MEDIUMTEXT NOT NULL,
    state       VARCHAR(32) NOT NULL COMMENT 'SENT_MQTT/NOT_CONNECTED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_iot_cmd_device (tenant_id, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备指令下发日志';
