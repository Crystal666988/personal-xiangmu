package com.tradeagent.modules.prospect.provider;

import com.tradeagent.modules.prospect.ProspectCollectorProvider;
import com.tradeagent.modules.prospect.ProspectLead;
import com.tradeagent.modules.prospect.ProspectTaskParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 领英 (LinkedIn) 采购决策人采集插件 (纯新增类)
 */
@Slf4j
@Component
public class LinkedInCollectorProvider implements ProspectCollectorProvider {

    @Override
    public String getChannelCode() {
        return "LINKEDIN";
    }

    @Override
    public List<ProspectLead> collect(ProspectTaskParam param) {
        log.info("【LinkedIn 采集】正在定向搜索职位为 [Procurement Director, Buyer, Owner] 的决策人...");
        return List.of(
            ProspectLead.builder()
                .companyName("Middle East Solar Industry Association (MESIA)")
                .country(param.getCountry())
                .website("https://www.linkedin.com/company/mesia")
                .phone("+971-4-3688888")
                .email("tariq@mesia-members.org")
                .dataSource("DEMO_SYNTHETIC").isLive(false)
                .sourceChannel("LINKEDIN")
                .address("Dubai Media City")
                .build()
        );
    }
}
