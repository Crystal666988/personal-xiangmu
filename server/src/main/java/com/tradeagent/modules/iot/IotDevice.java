package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** IoT 设备台账（租户下多栋舍多台设备；CRUD 管理设备注册信息）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("iot_device")
public class IotDevice {
    @TableId(type = IdType.INPUT)
    private String deviceId;
    private String tenantId;
    private String farmId;
    private String productId;
    private String location;
    private String secret;
    private String firmwareVersion;
    private String name;
    private String type;
    /** 环控阈值覆盖：null = 用全局默认；采集校验时优先生效 */
    private Double tempLow;
    private Double tempHigh;
    private Double humidLow;
    private Double humidHigh;
    private LocalDateTime lastSeen;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
