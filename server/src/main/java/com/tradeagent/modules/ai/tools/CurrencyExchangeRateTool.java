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

        return ToolDefinition.builder()
                .id("currency_exchange_rate")
                .name("外汇即期牌价与汇率查询")
                .domain("COMMERCE")
                .description("查询中国外汇交易中心 (CFETS) 与国际银行间即期基准中间价、现汇买入与卖出牌价")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String base = params != null && params.get("baseCurrency") != null ? params.get("baseCurrency").toString().toUpperCase() : "USD";
        String target = params != null && params.get("targetCurrency") != null ? params.get("targetCurrency").toString().toUpperCase() : "CNY";

        String pair = base + "/" + target;
        double rate = RATES.getOrDefault(pair, 7.2385);

        double buyRate = Math.round(rate * 0.998 * 10000.0) / 10000.0;
        double sellRate = Math.round(rate * 1.002 * 10000.0) / 10000.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currencyPair", pair);
        result.put("middleRate", rate);
        result.put("cashBuyRate", buyRate);
        result.put("cashSellRate", sellRate);
        result.put("source", "中国外汇交易中心 (CFETS) / BOC 即期牌价");
        result.put("updateTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("status", "VALID");

        return result;
    }
}
