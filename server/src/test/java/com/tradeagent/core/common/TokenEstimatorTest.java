package com.tradeagent.core.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    @DisplayName("CJK 字符按 1 token/字 估算（全角标点属 COMMON，按 4:1 计）")
    void cjkCountsOneTokenPerChar() {
        assertEquals(4, TokenEstimator.estimateTokens("你好世界"));
        assertEquals(5, TokenEstimator.estimateTokens("你好世界ab"));
        assertEquals(5, TokenEstimator.estimateTokens("你好，世界！"));
    }

    @Test
    @DisplayName("非 CJK 字符按 4:1 估算（向上取整）")
    void asciiCountsOneTokenPerFourChars() {
        assertEquals(2, TokenEstimator.estimateTokens("abcdefgh"));
        assertEquals(3, TokenEstimator.estimateTokens("abcdefghi"));
        assertEquals(0, TokenEstimator.estimateTokens(""));
        assertEquals(0, TokenEstimator.estimateTokens(null));
    }

    @Test
    @DisplayName("中英混合按口径叠加，且长文本估算随规模单调增长")
    void mixedTextScalesMonotonically() {
        String shortText = "报价 TOPCon580W 2000片";
        String longText = shortText.repeat(50);
        int shortEstimate = TokenEstimator.estimateTokens(shortText);
        int longEstimate = TokenEstimator.estimateTokens(longText);
        assertTrue(shortEstimate > 0);
        assertEquals(shortEstimate * 50L, longEstimate);
    }
}
