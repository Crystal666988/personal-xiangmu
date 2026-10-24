package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** IoT 异常事件（规则引擎命中后生成，并触发飞书人工预警）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("iot_anomaly_event")
public class IotAnomalyEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String farmId;
    private String deviceId;
    private String metric;
    private Double metricValue;
    private String ruleCode;
    private String severity;
    private String message;
    private String feishuState;
    private LocalDateTime createTime;
}
