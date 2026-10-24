package com.tradeagent.modules.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实工具：外汇即期牌价查询 (currency_exchange_rate)
 * 领域：COMMERCE (外贸商业)
 *
 * 数据策略（P1 可信度改造）：
 * 1. 实时路径：调用 Frankfurter（欧洲央行 ECB 参考汇率，免密钥）获取中间价，结果缓存 60 分钟；
 * 2. 回退路径：ECB 货币篮子不含的币种（如 AED）或网络失败时，回退到登记的静态演示汇率，
 *    并显式标记 isLive=false / source=DEMO_STATIC_RATE_TABLE，供下游"演示数据纪律"如实披露。
 * TradeToolRules.FX_RATES 仅作为"已登记币种组合"的预检白名单，不再充当数据源。
 */
@Slf4j
@Component
public class CurrencyExchangeRateTool implements IToolHandler {

    /** ECB 参考汇率篮子支持的币种（Frankfurter 数据源） */
    private static final Set<String> ECB_SUPPORTED = Set.of("USD", "EUR", "CNY", "GBP", "JPY", "HKD", "SGD", "AUD", "CAD", "CHF", "SEK", "NOK");
    private static final String FRANKFURTER_URL = "https://api.frankfurter.app/latest?from=%s&to=%s";
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Map<String, CachedRate> rateCache = new ConcurrentHashMap<>();

    private record CachedRate(double rate, LocalDate rateDate, long cachedAtMs) {
        boolean fresh() {
            return System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS;
        }
    }

    /** 包级可见：单元测试覆写网络方法时需要引用该类型 */
    record LiveRate(double rate, LocalDate rateDate) {}

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("baseCurrency", Map.of("type", "string", "description", "基准货币 (如 USD, EUR, CNY)", "required", false));
        schema.put("targetCurrency", Map.of("type", "string", "description", "目标货币 (如 CNY, EUR, GBP; AED 走演示汇率)", "required", false));

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("baseCurrency", "USD");
        example.put("targetCurrency", "CNY");

        return ToolDefinition.builder().readOnly(true)
                .id("currency_exchange_rate")
                .name("外汇即期牌价与汇率查询")
                .domain("COMMERCE")
                .description("优先返回 Frankfurter/ECB 实时参考汇率 (缓存60分钟)；ECB 未覆盖币种或离线时回退静态演示汇率并标记 isLive=false")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> input) {
        var p = TradeToolRules.prepare("currency_exchange_rate", input).parameters();
        String pair = p.get("baseCurrency") + "/" + p.get("targetCurrency");

        // 1. 实时路径：ECB 篮子覆盖的币种组合，走缓存 + Frankfurter
        String base = (String) p.get("baseCurrency");
        String target = (String) p.get("targetCurrency");
        if (ECB_SUPPORTED.contains(base) && ECB_SUPPORTED.contains(target)) {
            CachedRate cached = rateCache.get(pair);
            if (cached != null && cached.fresh()) {
                return liveResult(pair, cached.rate(), cached.rateDate(), true);
            }
            Optional<LiveRate> live = fetchLiveRate(base, target);
            if (live.isPresent()) {
                rateCache.put(pair, new CachedRate(live.get().rate(), live.get().rateDate(), System.currentTimeMillis()));
                return liveResult(pair, live.get().rate(), live.get().rateDate(), false);
            }
            log.warn("【汇率工具】{} 实时汇率获取失败，回退静态演示汇率", pair);
        }

        // 2. 回退路径：未覆盖币种或获取失败 → 静态演示汇率，如实标注
        return Map.of("currencyPair", pair, "middleRate", TradeToolRules.FX_RATES.get(pair),
                "source", "DEMO_STATIC_RATE_TABLE", "isLive", false);
    }

    private Map<String, Object> liveResult(String pair, double rate, LocalDate rateDate, boolean fromCache) {
        double buyRate = Math.round(rate * 0.998 * 10000.0) / 10000.0;
        double sellRate = Math.round(rate * 1.002 * 10000.0) / 10000.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currencyPair", pair);
        result.put("middleRate", rate);
        result.put("cashBuyRate", buyRate);
        result.put("cashSellRate", sellRate);
        result.put("source", "Frankfurter / ECB 参考汇率" + (fromCache ? " (缓存)" : ""));
        result.put("rateDate", rateDate.toString());
        result.put("isLive", true);
        return result;
    }

    /** 独立抽出便于单元测试覆写；生产为真实 Frankfurter HTTP 调用 */
    protected Optional<LiveRate> fetchLiveRate(String base, String target) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(FRANKFURTER_URL, base, target)))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            double rate = root.path("rates").path(target).asDouble(0.0);
            if (rate <= 0) {
                return Optional.empty();
            }
            LocalDate date = LocalDate.parse(root.path("date").asText(LocalDate.now().toString()));
            return Optional.of(new LiveRate(rate, date));
        } catch (Exception e) {
            log.warn("【汇率工具】Frankfurter 调用异常: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
