package com.tradeagent.modules.ai.tools;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实工具：海关提单与企业背调检索器 (customs_data_lookup)
 * 领域：PROSPECT (海外客户背调)
 */
@Component
public class CustomsDataLookupTool implements IToolHandler {

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("companyName", Map.of("type", "string", "description", "海外采购商企业名称 (如 Al-Futtaim Solar, BayWa r.e.)", "required", true));
        schema.put("hsCode", Map.of("type", "string", "description", "海关商品 HS Code (默认 8541.4300 太阳能电池)", "required", false));

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("companyName", "Al-Futtaim Solar Energy Solutions LLC");
        example.put("hsCode", "8541.4300");

        return ToolDefinition.builder().readOnly(true)
                .id("customs_data_lookup")
                .name("全球海关提单与买家画像检索")
                .domain("PROSPECT")
                .description("DEMO company fixtures only; no verified customs shipment records.")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> input) {
        var p = TradeToolRules.prepare("customs_data_lookup", input).parameters();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetCompany", p.get("companyName"));
        if (p.containsKey("hsCode")) result.put("hsCodeFilter", p.get("hsCode"));
        result.put("dataSource", "DEMO_CUSTOMS_FIXTURES"); result.put("isLive", false);
        result.put("verifiedRecords", List.of());
        result.put("notice", "Demo company fixture only; no live or verified customs shipments are available.");
        return result;
    }
}