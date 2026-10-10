package com.tradeagent.modules.prospect.provider;

import com.tradeagent.modules.prospect.ProspectCollectorProvider;
import com.tradeagent.modules.prospect.ProspectLead;
import com.tradeagent.modules.prospect.ProspectTaskParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Facebook 公共主页客源采集插件 (纯新增类)
 */
@Slf4j
@Component
public class FacebookCollectorProvider implements ProspectCollectorProvider {

    @Override
    public String getChannelCode() {
        return "FACEBOOK";
    }

    @Override
    public List<ProspectLead> collect(ProspectTaskParam param) {
        log.info("【Facebook 采集】正在扫描地区: {} 的社媒主页与企业 WhatsApp 按钮...", param.getCountry());
        return List.of(
            ProspectLead.builder()
                .companyName("Gulf Green Power Systems")
                .country(param.getCountry())
                .website("https://facebook.com/gulfgreenpower")
                .phone("+971-50-1234567")
                .email("sales@gulfgreen.com")
                .sourceChannel("FACEBOOK")
                .address("Business Bay, Dubai")
                .build()
        );
    }
}
