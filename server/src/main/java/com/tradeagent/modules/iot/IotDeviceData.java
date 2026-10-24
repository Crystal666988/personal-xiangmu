package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** IoT 设备时序数据点（MQTT/HTTP 双路进入，入库后触发规则评估）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("iot_device_data")
public class IotDeviceData {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String farmId;
    private String deviceId;
    private String metric;
    private Double metricValue;
    private String unit;
    private LocalDateTime collectedAt;
    private LocalDateTime ingestTime;
}
