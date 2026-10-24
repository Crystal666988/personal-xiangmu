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

/** IoT 设备台账（一栋舍多台环控/料线设备）。 */
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
    private String name;
    private String type;
    private LocalDateTime lastSeen;
    private LocalDateTime createTime;
}
