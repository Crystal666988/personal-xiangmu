package com.tradeagent.modules.ai.tools;

import java.math.BigDecimal;
import java.util.*;

/** Shared pure preflight rules used by both the workflow and the actual registered handlers. */
public final class TradeToolRules {
    private TradeToolRules() {}
    public static final BigDecimal DEMO_BASE_PRICE_PER_WATT = new BigDecimal("0.118");
    public static BigDecimal tierPriceFactor(int quantity) {
        return quantity >= 2000 ? new BigDecimal("0.9237") : quantity >= 500 ? new BigDecimal("0.95") : BigDecimal.ONE;
    }
    public static final Map<String, Integer> MODEL_WATTS = Map.of("TOPCon-580W", 580, "PERC-550W", 550);
    public static final Map<String, Integer> PORT_RATES = Map.of("Hamburg", 2450, "Dubai", 2250,
            "Jebel Ali", 2250, "Rotterdam", 2480, "Singapore", 850, "Los Angeles", 1950, "Antwerp", 2420);
    public static final Map<String, Double> FX_RATES = Map.of("USD/CNY", 7.2385, "USD/EUR", 0.9242,
            "USD/AED", 3.6725, "USD/GBP", 0.7891, "EUR/USD", 1.0820, "CNY/USD", 0.1381);
    public static final Set<String> COMPANIES = Set.of("Al-Futtaim Solar Energy Solutions LLC", "BayWa r.e. Solar Trade GmbH");
    public static final Set<String> ALLOWED = Set.of("calculate_tier_price", "estimate_freight_cif",
            "currency_exchange_rate", "customs_data_lookup", "rag_vector_search");
    public record Prepared(Map<String, Object> parameters, Map<String, String> parameterSources) {}

    public static Prepared prepare(String tool, Map<String, Object> input) {
        if (!ALLOWED.contains(tool) || input == null) throw new IllegalArgumentException("Unsupported tool or parameters");
        Map<String, Object> p = new LinkedHashMap<>(input);
        Map<String, String> sources = new LinkedHashMap<>(); p.keySet().forEach(key -> sources.put(key, "USER_EVIDENCE"));
        Set<String> allowed = switch (tool) {
            case "calculate_tier_price" -> Set.of("productModel", "quantity", "wattage", "basePricePerWatt");
            case "estimate_freight_cif" -> Set.of("destPort", "originPort", "quantity", "fobTotalUsd");
            case "currency_exchange_rate" -> Set.of("baseCurrency", "targetCurrency");
            case "customs_data_lookup" -> Set.of("companyName", "hsCode");
            default -> Set.of("query", "topK", "docId");
        };
        if (!allowed.containsAll(p.keySet())) throw new IllegalArgumentException("Unknown tool parameter");
        switch (tool) {
            case "calculate_tier_price" -> {
                String model = text(p, "productModel");
                Integer watts = MODEL_WATTS.get(model);
                if (watts == null) throw new IllegalArgumentException("产品型号尚无登记的演示价格数据，请确认型号。");
                int quantity = integer(p, "quantity");
                if ((long) quantity * watts > Integer.MAX_VALUE) throw new IllegalArgumentException("采购数量超出当前计算工具范围。");
                if (p.containsKey("wattage") && integer(p, "wattage") != watts) throw new IllegalArgumentException("型号与功率不一致。");
                if (!p.containsKey("wattage")) { p.put("wattage", watts); sources.put("wattage", "DERIVED_MODEL_CATALOG"); }
                if (!p.containsKey("basePricePerWatt")) {
                    p.put("basePricePerWatt", DEMO_BASE_PRICE_PER_WATT); sources.put("basePricePerWatt", "DEMO_PRICE_CATALOG_USD_PER_WATT");
                }
                decimal(p, "basePricePerWatt");
            }
            case "estimate_freight_cif" -> {
                if (!PORT_RATES.containsKey(text(p, "destPort"))) throw new IllegalArgumentException("目的港尚无登记运价，不能套用其他港口价格，请确认目的港或补充运价。");
                if (!Set.of("Ningbo", "Shanghai").contains(text(p, "originPort"))) throw new IllegalArgumentException("请明确起运港；当前演示运价仅支持宁波或上海起运。");
                integer(p, "quantity");
                if (p.containsKey("fobTotalUsd")) decimal(p, "fobTotalUsd");
            }
            case "currency_exchange_rate" -> {
                if (!FX_RATES.containsKey(text(p, "baseCurrency") + "/" + text(p, "targetCurrency"))) {
                    throw new IllegalArgumentException("该币种组合没有登记汇率，不能套用默认汇率。");
                }
            }
            case "customs_data_lookup" -> {
                if (!COMPANIES.contains(text(p, "companyName"))) throw new IllegalArgumentException("公司未登记，不能替换为其他公司的海关记录。");
                if (p.containsKey("hsCode") && !text(p, "hsCode").matches("(?:\\d{6}|\\d{8}|\\d{10})")) {
                    throw new IllegalArgumentException("HS编码格式不合法。");
                }
            }
            case "rag_vector_search" -> {
                text(p, "query");
                if (!p.containsKey("topK")) { p.put("topK", 3); sources.put("topK", "BACKEND_RETRIEVAL_LIMIT_POLICY"); }
                if (integer(p, "topK") > 50) throw new IllegalArgumentException("检索结果条数必须在1至50之间。");
                if (p.containsKey("docId")) throw new IllegalArgumentException("当前执行链路尚未校验文档访问权限。");
            }
        }
        return new Prepared(Map.copyOf(p), Map.copyOf(sources));
    }
    public static String text(Map<String, Object> p, String field) {
        if (!(p.get(field) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("请补充明确的" + label(field) + "。");
        return value;
    }
    public static int integer(Map<String, Object> p, String field) {
        try {
            if (!(p.get(field) instanceof Number number)) throw new ArithmeticException();
            int value = new BigDecimal(number.toString()).intValueExact();
            if (value <= 0) throw new ArithmeticException();
            return value;
        } catch (ArithmeticException e) { throw new IllegalArgumentException(label(field) + "必须是明确的正整数。"); }
    }
    public static double decimal(Map<String, Object> p, String field) {
        try {
            if (!(p.get(field) instanceof Number number)) throw new NumberFormatException();
            double value = new BigDecimal(number.toString()).doubleValue();
            if (!Double.isFinite(value) || value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(label(field) + "必须是明确的正数。"); }
    }
    private static String label(String field) {
        return switch (field) {
            case "productModel" -> "产品型号"; case "quantity" -> "采购片数"; case "wattage" -> "标称功率";
            case "originPort" -> "起运港"; case "destPort" -> "目的港"; case "fobTotalUsd" -> "FOB总金额（USD）";
            case "baseCurrency" -> "原币种"; case "targetCurrency" -> "目标币种"; case "basePricePerWatt" -> "每瓦单价";
            case "companyName" -> "公司名称"; case "query" -> "检索问题"; case "topK" -> "结果条数"; default -> "业务参数";
        };
    }
}
