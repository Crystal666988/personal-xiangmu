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
        
        // 模拟采集到的实体工商业线索 (生产环境对接 Google Places API / SerpAPI)
        return List.of(
            ProspectLead.builder()
                .companyName("Al-Futtaim Solar Energy LLC")
                .country(param.getCountry())
                .website("https://www.al-futtaim-solar.ae")
                .phone("+971-4-2085000")
                .email("procurement@al-futtaim.ae")
                .sourceChannel("GOOGLE_MAPS")
                .address("Dubai Festival City, Building 3, Dubai, UAE")
                .build()
        );
    }
}
