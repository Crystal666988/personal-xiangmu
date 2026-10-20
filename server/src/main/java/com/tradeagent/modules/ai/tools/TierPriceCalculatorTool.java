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

        return ToolDefinition.builder()
                .id("calculate_tier_price")
                .name("光伏组件阶梯价格核算器")
                .domain("COMMERCE")
                .description("根据海外采购商意向订购数量，核算起运港 FOB 阶梯单价、单片价格与整批货值总额")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String model = params != null && params.get("productModel") != null ? params.get("productModel").toString() : "TOPCon-580W";
        int quantity = 2000;
        if (params != null && params.get("quantity") != null) {
            try {
                quantity = Integer.parseInt(params.get("quantity").toString());
            } catch (Exception ignored) {}
        }

        int wattage = 580;
        if (params != null && params.get("wattage") != null) {
            try {
                wattage = Integer.parseInt(params.get("wattage").toString());
            } catch (Exception ignored) {}
        } else if (model.contains("550")) {
            wattage = 550;
        } else if (model.contains("540")) {
            wattage = 540;
        }

        double basePrice = 0.118;
        if (params != null && params.get("basePricePerWatt") != null) {
            try {
                basePrice = Double.parseDouble(params.get("basePricePerWatt").toString());
            } catch (Exception ignored) {}
        }

        // 阶梯折扣逻辑
        double discountRate = 1.0;
        String tierApplied = "标准散单阶梯 (< 500套)";
        if (quantity >= 2000) {
            discountRate = 0.9237; // 折合 0.109 USD/W
            tierApplied = "大单专属折扣阶梯 (>= 2000套)";
        } else if (quantity >= 500) {
            discountRate = 0.95; // 折合 0.112 USD/W
            tierApplied = "集装箱集采阶梯 (500 ~ 1999套)";
        }

        double unitPricePerWatt = round(basePrice * discountRate, 4);
        double unitPricePerPiece = round(unitPricePerWatt * wattage, 2);
        double fobTotal = round(unitPricePerPiece * quantity, 2);
        double totalCapacityMw = round((double) (quantity * wattage) / 1_000_000.0, 3);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productModel", model);
        result.put("quantity", quantity);
        result.put("wattagePerPiece", wattage + "W");
        result.put("totalCapacityMw", totalCapacityMw + " MW");
        result.put("tierApplied", tierApplied);
        result.put("discountRate", round((1.0 - discountRate) * 100.0, 1) + "%");
        result.put("unitPricePerWattUsd", "$" + unitPricePerWatt + " / W");
        result.put("unitPricePerPieceUsd", "$" + unitPricePerPiece + " USD");
        result.put("fobTotalUsd", "$" + String.format("%,.2f", fobTotal) + " USD");
        result.put("fobTotalUsdRaw", fobTotal);
        result.put("validityDays", 14);

        return result;
    }

    private double round(double val, int scale) {
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
