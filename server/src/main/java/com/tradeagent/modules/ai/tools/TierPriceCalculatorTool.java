package com.tradeagent.modules.ai.tools;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实工具：阶梯价格计算器 (calculate_tier_price)
 * 领域：COMMERCE (外贸商业)
 */
@Component
public class TierPriceCalculatorTool implements IToolHandler {

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("productModel", Map.of("type", "string", "description", "产品型号 (如 TOPCon-580W, PERC-550W)", "required", true));
        schema.put("quantity", Map.of("type", "integer", "description", "采购数量 (套/片)", "required", true));
        schema.put("wattage", Map.of("type", "integer", "description", "单片组件标称功率 (W)，默认 580W", "required", false));
        schema.put("basePricePerWatt", Map.of("type", "number", "description", "基准单瓦售价 ($/W)，默认 0.118", "required", false));

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("productModel", "TOPCon-580W");
        example.put("quantity", 2000);
        example.put("wattage", 580);
        example.put("basePricePerWatt", 0.118);

        return ToolDefinition.builder().readOnly(true)
                .id("calculate_tier_price")
                .name("光伏组件阶梯价格核算器")
                .domain("COMMERCE")
                .description("DEMO registered product pricing table; not a live supplier quotation.")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> input) {
        var prepared = TradeToolRules.prepare("calculate_tier_price", input);
        var p = prepared.parameters();
        String model = TradeToolRules.text(p, "productModel");
        int quantity = TradeToolRules.integer(p, "quantity");
        int wattage = TradeToolRules.integer(p, "wattage");
        double basePrice = TradeToolRules.decimal(p, "basePricePerWatt");
        double discount = TradeToolRules.tierPriceFactor(quantity).doubleValue();
        double unit = round(basePrice * discount, 4);
        return Map.of("productModel", model, "quantity", quantity, "wattage", wattage,
                "unitPricePerWattUsd", unit, "unitPricePerPieceUsd", round(unit * wattage, 2),
                "fobTotalUsdRaw", round(unit * wattage * quantity, 2),
                "dataSource", "DEMO_PRICE_CATALOG", "isLive", false,
                "parameterSources", prepared.parameterSources());
    }
    private double round(double val, int scale) {
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
