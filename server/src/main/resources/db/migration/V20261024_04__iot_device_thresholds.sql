-- 设备台账补阈值覆盖列（温氏场景：不同栋舍/季节阈值不同）
ALTER TABLE iot_device
    ADD COLUMN temp_low DOUBLE NULL COMMENT '温度下限覆盖',
    ADD COLUMN temp_high DOUBLE NULL COMMENT '温度上限覆盖',
    ADD COLUMN humid_low DOUBLE NULL COMMENT '湿度下限覆盖',
    ADD COLUMN humid_high DOUBLE NULL COMMENT '湿度上限覆盖',
    ADD COLUMN update_time DATETIME NULL COMMENT '更新时间';
