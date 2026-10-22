package com.tradeagent.modules.ai.tools;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实工具：外汇即期牌价查询 (currency_exchange_rate)
 * 领域：COMMERCE (外贸商业)
 */
@Component
public class CurrencyExchangeRateTool implements IToolHandler {

    private static final Map<String, Double> RATES = Map.of(
            "USD/CNY", 7.2385,
            "USD/EUR", 0.9242,
            "USD/AED", 3.6725,
            "USD/GBP", 0.7891,
            "EUR/USD", 1.0820,
            "CNY/USD", 0.1381
    );

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("baseCurrency", Map.of("type", "string", "description", "基准货币 (如 USD, EUR, CNY)", "required", false));
        schema.put("targetCurrency", Map.of("type", "string", "description", "目标货币 (如 CNY, EUR, AED, GBP)", "required", false));

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("baseCurrency", "USD");
        example.put("targetCurrency", "CNY");

        return ToolDefinition.builder().readOnly(true)
                .id("currency_exchange_rate")
                .name("外汇即期牌价与汇率查询")
                .domain("COMMERCE")
                .description("DEMO static currency rates; not live CFETS/BOC data.")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> input) {
        var p = TradeToolRules.prepare("currency_exchange_rate", input).parameters();
        String pair = p.get("baseCurrency") + "/" + p.get("targetCurrency");
        return Map.of("currencyPair", pair, "middleRate", TradeToolRules.FX_RATES.get(pair),
                "source", "DEMO_STATIC_RATE_TABLE", "isLive", false);
    }
}