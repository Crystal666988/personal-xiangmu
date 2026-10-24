package com.tradeagent.modules.prospect.provider;

import com.tradeagent.modules.prospect.ProspectCollectorProvider;
import com.tradeagent.modules.prospect.ProspectLead;
import com.tradeagent.modules.prospect.ProspectTaskParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Google Maps 地图客源采集插件 (纯新增类)
 */
@Slf4j
@Component
public class GoogleMapsCollectorProvider implements ProspectCollectorProvider {

    @Override
    public String getChannelCode() {
        return "GOOGLE_MAPS";
    }

    @Override
    public List<ProspectLead> collect(ProspectTaskParam param) {
        log.info("【Google Maps 采集】正在扫描地区: {} 关键词: {} 的实体商户与经纬度...", 
                param.getCountry(), param.getIndustryKeyword());
        
        // 合成演示线索 (生产接入点: Google Places API (New) / SerpAPI Local)，isLive=false 必须向下游如实披露
        return List.of(
            ProspectLead.builder()
                .companyName("Al-Futtaim Solar Energy LLC")
                .country(param.getCountry())
                .website("https://www.al-futtaim-solar.ae")
                .phone("+971-4-2085000")
                .email("procurement@al-futtaim.ae")
                .dataSource("DEMO_SYNTHETIC").isLive(false)
                .sourceChannel("GOOGLE_MAPS")
                .address("Dubai Festival City, Building 3, Dubai, UAE")
                .build()
        );
    }
}
