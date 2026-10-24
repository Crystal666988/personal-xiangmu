package com.tradeagent.modules.prospect.provider;

import com.tradeagent.modules.prospect.ProspectCollectorProvider;
import com.tradeagent.modules.prospect.ProspectLead;
import com.tradeagent.modules.prospect.ProspectTaskParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WhatsApp 海外群组与商机号采集插件 (纯新增类)
 */
@Slf4j
@Component
public class WhatsAppCollectorProvider implements ProspectCollectorProvider {

    @Override
    public String getChannelCode() {
        return "WHATSAPP";
    }

    @Override
    public List<ProspectLead> collect(ProspectTaskParam param) {
        log.info("【WhatsApp 采集】正在扫描外贸群组与公开名片中的有效商业号码...");
        return List.of(
            ProspectLead.builder()
                .companyName("Arabian Clean Tech Trading")
                .country(param.getCountry())
                .website("https://wa.me/971520000000")
                .phone("+971-52-0000000")
                .email("info@arabian-cleantech.com")
                .dataSource("DEMO_SYNTHETIC").isLive(false)
                .sourceChannel("WHATSAPP")
                .address("Sharjah Industrial Area, UAE")
                .build()
        );
    }
}
