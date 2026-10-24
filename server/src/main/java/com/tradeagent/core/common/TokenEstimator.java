package com.tradeagent.core.common;

/**
 * 轻量 Token 估算器（无需分词器依赖）。
 *
 * 估算口径：CJK 字符（汉字/假名/谚文）按 1 token/字，其余字符按 4:1 估算。
 * 用途：上下文预算、轨迹评测的规划器 Token 指标、服务端 Prompt 可观测性日志。
 * 注意：这是工程估算口径，不是模型厂商的精确计费口径，禁止用于计费对账。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) cjk++;
            else other++;
        }
        return cjk + (other + 3) / 4;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
