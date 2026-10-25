package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 设备影子：desired（云端期望）与 reported（设备上报）的差量在设备离线时不丢。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("iot_device_shadow")
public class IotDeviceShadow {
    @TableId("device_id")
    private String deviceId;
    private String tenantId;
    private String desiredJson;
    private String reportedJson;
    private Long version;
    private String pendingJson;
    private LocalDateTime updateTime;
}
