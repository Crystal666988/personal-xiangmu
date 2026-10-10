package com.tradeagent.modules.prospect;

import java.util.List;

/**
 * 采集通道 SPI 统一提供商接口 (Service Provider Interface)
 * 纯新增类接入规范
 */
public interface ProspectCollectorProvider {

    /**
     * 渠道唯一标识 (e.g. "GOOGLE_MAPS", "FACEBOOK", "LINKEDIN", "WHATSAPP")
     */
    String getChannelCode();

    /**
     * 执行渠道客源采集
     *
     * @param param 采集参数
     * @return 抓取到的客源线索列表
     */
    List<ProspectLead> collect(ProspectTaskParam param);
}
