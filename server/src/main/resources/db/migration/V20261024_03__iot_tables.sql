-- 温氏智慧养殖 IoT 链路：设备数据入库、异常事件、每日总结
-- 链路: 智能设备 --MQTT/HTTP--> 入库(MySQL) --> 规则引擎 --> 异常事件 --> 飞书人工预警(Webhook)
--                                                    └--------> 每日汇总 --> 飞书多维表格(Bitable)

CREATE TABLE IF NOT EXISTS iot_device (
    device_id   VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    farm_id     VARCHAR(64) NOT NULL COMMENT '栋舍/场区',
    name        VARCHAR(80) NOT NULL COMMENT '设备名称 (环控器/料线/水表)',
    type        VARCHAR(32) NOT NULL DEFAULT 'ENV' COMMENT 'ENV 环控 / FEED 料线 / WATER 水线',
    last_seen   DATETIME NULL COMMENT '最近一次上报时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_iot_device_tenant (tenant_id, farm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IoT 设备台账';

CREATE TABLE IF NOT EXISTS iot_device_data (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    farm_id      VARCHAR(64) NOT NULL,
    device_id    VARCHAR(64) NOT NULL,
    metric       VARCHAR(32) NOT NULL COMMENT 'temperature/humidity/nh3/co2/...',
    metric_value DOUBLE NOT NULL,
    unit         VARCHAR(16) NULL,
    collected_at DATETIME NOT NULL COMMENT '设备采集时间',
    ingest_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_iot_data_query (tenant_id, device_id, collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IoT 设备时序数据';

CREATE TABLE IF NOT EXISTS iot_anomaly_event (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    farm_id      VARCHAR(64) NOT NULL,
    device_id    VARCHAR(64) NOT NULL,
    metric       VARCHAR(32) NOT NULL,
    metric_value DOUBLE NOT NULL,
    rule_code    VARCHAR(64) NOT NULL COMMENT 'TEMP_HIGH/HUMIDITY_LOW/NH3_HIGH/DATA_GAP...',
    severity     VARCHAR(16) NOT NULL COMMENT 'WARN/CRITICAL',
    message      VARCHAR(500) NOT NULL,
    feishu_state VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'SENT/SKIPPED_NO_WEBHOOK/FAILED/PENDING',
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_iot_anomaly_query (tenant_id, device_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IoT 异常事件 (触发飞书人工预警)';

CREATE TABLE IF NOT EXISTS iot_daily_summary (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    farm_id       VARCHAR(64) NOT NULL,
    summary_date  DATE NOT NULL,
    summary_json  MEDIUMTEXT NOT NULL COMMENT '各设备指标 min/max/avg + 异常数',
    feishu_state  VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'BITABLE_SENT/LOCAL_ONLY/FAILED',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_iot_summary (tenant_id, farm_id, summary_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日设备情况总结 (推送飞书多维表格)';
