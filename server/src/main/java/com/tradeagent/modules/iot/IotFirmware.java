package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 固件包（OTA 灰度发布：批次写入设备影子 desired，设备上线自取升级）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("iot_firmware")
public class IotFirmware {
    @TableId("id")
    private String id;
    private String version;
    private String url;
    private Long sizeBytes;
    private String md5;
    private Integer greyBatch;
    private String state;
    private LocalDateTime createTime;
}
