package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 设备指令下发日志。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("iot_command_log")
public class IotCommandLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String deviceId;
    private String commandJson;
    private String state;
    /** 幂等键 (设备/网关 messageId) */
    private String idempotencyKey;
    private LocalDateTime createTime;
}
