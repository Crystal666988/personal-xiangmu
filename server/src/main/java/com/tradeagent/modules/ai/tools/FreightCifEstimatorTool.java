package com.tradeagent.modules.ai.tools;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实工具：CIF/FOB 海运费与柜型配载核算器 (estimate_freight_cif)
 * 领域：COMMERCE (外贸商业)
 */
@Component
public class FreightCifEstimatorTool implements IToolHandler {

    private static final Map<String, Integer> PORT_RATES_40HQ = Map.of(
            "hamburg", 2450,
            "dubai", 2250,
            "jebel ali", 2250,
            "rotterdam", 2480,
            "singapore", 850,
            "los angeles", 1950,
            "antwerp", 2420
    );

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("destPort", Map.of("type", "string", "description", "目的港英文名或中文 (如 Hamburg, Jebel Ali, Rotterdam)", "required", true));
        schema.put("originPort", Map.of("type", "string", "description", "起运港 (默认 Ningbo / Shanghai)", "required", false));
        schema.put("quantity", Map.of("type", "integer", "description", "装箱组件数量 (套/片)", "required", true));
        schema.put("fobTotalUsd", Map.of("type", "number", "description", "FOB 货值总额 (USD)", "required", false));

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("destPort", "Jebel Ali");
        example.put("originPort", "Ningbo, China");
        example.put("quantity", 2000);
        example.put("fobTotalUsd", 119900.0);

        return ToolDefinition.builder().readOnly(true)
                .id("estimate_freight_cif")
                .name("CIF/FOB 海运费与集装箱配载核算")
                .domain("COMMERCE")
                .description("DEMO static freight table and explicit packing assumptions; not a live carrier rate.")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> input) {
        var prepared = TradeToolRules.prepare("estimate_freight_cif", input);
        var p = prepared.parameters();
        String destination = TradeToolRules.text(p, "destPort");
        int quantity = TradeToolRules.integer(p, "quantity");
        long containers = ((long) quantity + 619) / 620;
        double ocean = containers * TradeToolRules.PORT_RATES.get(destination);
        double charges = containers * 220 + 240;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destPort", destination); result.put("originPort", p.get("originPort"));
        result.put("quantity", quantity); result.put("oceanFreightUsd", ocean);
        result.put("portChargesUsd", charges); result.put("freightExcludingInsuranceUsd", ocean + charges);
        result.put("dataSource", "DEMO_STATIC_FREIGHT_TABLE"); result.put("isLive", false);
        result.put("packingAssumption", "DEMO: 620 pieces per 40HQ; actual packing must be confirmed");
        if (p.containsKey("fobTotalUsd")) {
            double fob = TradeToolRules.decimal(p, "fobTotalUsd");
            double insurance = round((fob + ocean) * 1.1 * 0.0015, 2);
            result.put("insuranceUsd", insurance); result.put("cifTotalUsd", round(fob + ocean + charges + insurance, 2));
        } else result.put("cifStatus", "FOB_REQUIRED_FOR_CIF_AND_INSURANCE");
        return result;
    }
    private double round(double val, int scale) {
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
