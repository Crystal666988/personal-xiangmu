package com.tradeagent.modules.ai.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 汇率工具测试：ECB 实时路径（覆写网络方法，零真实调用）、不支持币种回退 DEMO、失败回退。
 */
class CurrencyExchangeRateToolTest {

    private CurrencyExchangeRateTool toolWith(Optional<CurrencyExchangeRateTool.LiveRate> live) {
        return new CurrencyExchangeRateTool() {
            @Override
            protected Optional<CurrencyExchangeRateTool.LiveRate> fetchLiveRate(String base, String target) {
                return live;
            }
        };
    }

    @Test
    @DisplayName("ECB 篮子未覆盖的币种组合 (USD/AED) 直接回退静态演示汇率且 isLive=false")
    void unsupportedPairFallsBackToDemo() {
        Object result = toolWith(Optional.empty()).execute(Map.of("baseCurrency", "USD", "targetCurrency", "AED"));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("USD/AED", map.get("currencyPair"));
        assertEquals(false, map.get("isLive"));
        assertEquals("DEMO_STATIC_RATE_TABLE", map.get("source"));
        assertEquals(3.6725, ((Number) map.get("middleRate")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("实时获取成功时返回 ECB 参考汇率并标记 isLive=true")
    void liveRateUsedWhenFetchSucceeds() {
        CurrencyExchangeRateTool tool = new CurrencyExchangeRateTool() {
            @Override
            protected Optional<CurrencyExchangeRateTool.LiveRate> fetchLiveRate(String base, String target) {
                return Optional.of(new CurrencyExchangeRateTool.LiveRate(7.1100, LocalDate.of(2026, 10, 23)));
            }
        };
        Object result = tool.execute(Map.of("baseCurrency", "USD", "targetCurrency", "CNY"));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(true, map.get("isLive"));
        assertEquals(7.1100, ((Number) map.get("middleRate")).doubleValue(), 1e-9);
        assertEquals("2026-10-23", map.get("rateDate"));
        assertTrue(String.valueOf(map.get("source")).contains("ECB"));
    }

    @Test
    @DisplayName("实时获取失败时回退静态演示汇率并标记 isLive=false")
    void liveFetchFailureFallsBackToDemo() {
        Object result = toolWith(Optional.empty()).execute(Map.of("baseCurrency", "USD", "targetCurrency", "CNY"));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(false, map.get("isLive"));
        assertEquals("DEMO_STATIC_RATE_TABLE", map.get("source"));
    }

    @Test
    @DisplayName("第二次调用命中 60 分钟缓存，不再发起网络请求")
    void secondCallHitsCache() {
        final int[] calls = {0};
        CurrencyExchangeRateTool tool = new CurrencyExchangeRateTool() {
            @Override
            protected Optional<CurrencyExchangeRateTool.LiveRate> fetchLiveRate(String base, String target) {
                calls[0]++;
                return Optional.of(new CurrencyExchangeRateTool.LiveRate(7.11, LocalDate.now()));
            }
        };
        tool.execute(Map.of("baseCurrency", "USD", "targetCurrency", "CNY"));
        tool.execute(Map.of("baseCurrency", "USD", "targetCurrency", "CNY"));
        assertEquals(1, calls[0], "缓存 TTL 内不应重复请求");
    }
}
