package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每日设备情况总结（定时聚合生成，推送飞书多维表格）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("iot_daily_summary")
public class IotDailySummary {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String farmId;
    private LocalDate summaryDate;
    private String summaryJson;
    private String feishuState;
    private LocalDateTime createTime;
}
