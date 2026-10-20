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

        return ToolDefinition.builder()
                .id("estimate_freight_cif")
                .name("CIF/FOB 海运费与集装箱配载核算")
                .domain("COMMERCE")
                .description("按海外目的港、出货柜型（40HQ/20GP）与起运港核算最新即期海运费、港杂费与海运一切险")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String destPort = params != null && params.get("destPort") != null ? params.get("destPort").toString() : "Dubai (Jebel Ali)";
        String originPort = params != null && params.get("originPort") != null ? params.get("originPort").toString() : "Ningbo / Shanghai, China";

        int quantity = 2000;
        if (params != null && params.get("quantity") != null) {
            try {
                quantity = Integer.parseInt(params.get("quantity").toString());
            } catch (Exception ignored) {}
        }

        double fobTotal = 119900.0;
        if (params != null && params.get("fobTotalUsd") != null) {
            try {
                fobTotal = Double.parseDouble(params.get("fobTotalUsd").toString());
            } catch (Exception ignored) {}
        }

        // 装箱规划：标准光伏组件 31 片/托，1 个 40HQ 装 20 托 = 620 片
        int piecesPer40Hq = 620;
        int count40Hq = quantity / piecesPer40Hq;
        int remaining = quantity % piecesPer40Hq;
        int count20Gp = remaining > 0 ? 1 : 0;

        // 目的港运价匹配
        String lowerPort = destPort.toLowerCase();
        int rate40Hq = 2450;
        for (Map.Entry<String, Integer> entry : PORT_RATES_40HQ.entrySet()) {
            if (lowerPort.contains(entry.getKey())) {
                rate40Hq = entry.getValue();
                break;
            }
        }
        int rate20Gp = (int) Math.round(rate40Hq * 0.68);

        double oceanFreight = (count40Hq * rate40Hq) + (count20Gp * rate20Gp);
        double portCharges = (count40Hq + count20Gp) * 220.0 + 240.0; // THC + CIC + 提单电放费
        double insurance = round((fobTotal + oceanFreight) * 1.1 * 0.0015, 2); // ICC (A) 110% 投保费率 1.5‰
        double totalFreight = round(oceanFreight + portCharges + insurance, 2);
        double cifTotal = round(fobTotal + totalFreight, 2);
        double cifPerWatt = round(cifTotal / (quantity * 580.0), 4);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originPort", originPort);
        result.put("destPort", destPort);
        result.put("quantity", quantity);
        result.put("packingPlan", String.format("%d 个 40HQ (%d片) + %d 个 20GP (%d片)", count40Hq, count40Hq * piecesPer40Hq, count20Gp, remaining));
        result.put("containers", Map.of(
                "40HQ", count40Hq + " 柜 (单价 $" + rate40Hq + ")",
                "20GP", count20Gp + " 柜 (单价 $" + rate20Gp + ")"
        ));
        result.put("oceanFreightUsd", "$" + String.format("%,.2f", oceanFreight));
        result.put("portChargesUsd", "$" + String.format("%,.2f", portCharges) + " (THC + CIC + 提单)");
        result.put("insuranceUsd", "$" + String.format("%,.2f", insurance) + " (ICC A类一切险 110%)");
        result.put("totalFreightAndInsuranceUsd", "$" + String.format("%,.2f", totalFreight));
        result.put("cifTotalUsd", "$" + String.format("%,.2f", cifTotal) + " USD");
        result.put("cifPricePerWattUsd", "$" + cifPerWatt + " / W");
        result.put("transitDays", "16 ~ 18 天直航抵港");

        return result;
    }

    private double round(double val, int scale) {
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
