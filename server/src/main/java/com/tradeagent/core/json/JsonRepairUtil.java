package com.tradeagent.core.json;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Stack;

/**
 * 残缺与截断 JSON 智能自愈修复工具 (JSON Repair Utility)
 * 
 * 解决面试官核心考点：
 * 应对大模型因超长文本导致 Token 耗尽被物理截断、或者漏写闭合括号引发 Jackson 抛出 JsonParseException 的极端事故。
 */
@Slf4j
@Component
public class JsonRepairUtil {

    /**
     * 自动修复残缺截断的 JSON 字符串
     */
    public static String autoRepairJson(String rawText) {
        if (rawText == null || rawText.isBlank()) return "{}";

        String text = rawText.trim();
        // 1. 剥离 Markdown 包装
        if (text.startsWith("```json")) text = text.substring(7);
        if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        text = text.trim();

        // 2. 检查未闭合的双引号
        int quoteCount = 0;
        for (char c : text.toCharArray()) {
            if (c == '"') quoteCount++;
        }
        if (quoteCount % 2 != 0) {
            text = text + "\""; // 自动补齐缺失的闭合双引号
        }

        // 3. 栈平衡检测：自动补齐缺失的 } 和 ]
        Stack<Character> stack = new Stack<>();
        for (char c : text.toCharArray()) {
            if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}') {
                if (!stack.isEmpty() && stack.peek() == '{') stack.pop();
            } else if (c == ']') {
                if (!stack.isEmpty() && stack.peek() == '[') stack.pop();
            }
        }

        StringBuilder repaired = new StringBuilder(text);
        while (!stack.isEmpty()) {
            char open = stack.pop();
            if (open == '{') repaired.append("}");
            else if (open == '[') repaired.append("]");
        }

        log.info("【JSON 自愈修补】已自动平衡闭合残缺符号，修复完成.");
        return repaired.toString();
    }
}
