package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 设备型号/物模型（产品层）：定义该类设备支持的指标与阈值，设备实例继承。
 * 对齐 JetLinks/FastBee 的「产品 → 设备」物模型架构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("iot_product")
public class IotProduct {
    @TableId("id")
    private String id;

    private String tenantId;

    /** 产品名称（养殖环控器） */
    private String name;

    /** 设备型号（WS-ENV-3000） */
    private String model;

    /** 接入协议：MQTT */
    private String protocol;

    /** 厂商（温氏智能设备部） */
    private String vendor;

    /** 物模型 JSON：[{metric,label,unit,low,high}] */
    private String metricsJson;

    private LocalDateTime createTime;
}
