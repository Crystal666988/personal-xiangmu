package com.tradeagent.modules.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Markdown 表格原子化与“行语义三元组”序列化处理器
 * 
 * 核心技术机制：
 * 1. 【表格原子完整性保全】：检测到连续的表格行时，绝不在物理中间腰斩切断；
 * 2. 【行语义三元组序列化】：
 *    把生硬的竖线表格每一行转化为大模型与 Embedding 模型极易理解的自然语言三元组：
 *    原行: | TOPCon-550 | 550W | $0.11/W |
 *    转为: 【电气参数】[产品型号 = TOPCon-550], [峰值功率 = 550W], [批量单价 = $0.11/W]
 *    彻底规避表头与数据行脱钩引发的严重检索幻觉！
 */
@Slf4j
@Component
public class MarkdownTableProcessor {

    /**
     * 判断一行是否属于 Markdown 表格行
     */
    public boolean isTableRow(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    /**
     * 判断是否是 Markdown 表格的分隔符行 (如 |---|---|)
     */
    public boolean isTableDelimiter(String line) {
        if (!isTableRow(line)) return false;
        String content = line.replaceAll("[|\\s-:]", "");
        return content.isEmpty();
    }

    /**
     * 将一段 Markdown 表格行列表，序列化为“带表头的自然语言行三元组切片文本”
     *
     * @param tableLines 表格的所有原始行
     * @param contextTitle 表格所属的上级上下文标题
     * @return 语义强化后的切片文本
     */
    public String serializeTableWithSemantics(List<String> tableLines, String contextTitle) {
        if (tableLines == null || tableLines.isEmpty()) {
            return "";
        }

        // 1. 提取表头
        String headerLine = tableLines.get(0);
        List<String> headers = parseRowCells(headerLine);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【表格数据概要: %s】\n", contextTitle != null ? contextTitle : "产品数据表"));

        // 2. 遍历数据行 (跳过第 0 行表头和第 1 行分隔符)
        int startRow = 1;
        if (tableLines.size() > 1 && isTableDelimiter(tableLines.get(1))) {
            startRow = 2;
        }

        for (int i = startRow; i < tableLines.size(); i++) {
            List<String> cells = parseRowCells(tableLines.get(i));
            if (cells.isEmpty()) continue;

            sb.append("行数据记录: ");
            for (int col = 0; col < headers.size() && col < cells.size(); col++) {
                String header = headers.get(col);
                String cell = cells.get(col);
                sb.append(String.format("[%s = %s] ", header, cell));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 解析单行单元格
     */
    private List<String> parseRowCells(String line) {
        String trimmed = line.trim();
        // 去除首尾的 |
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        return Arrays.stream(trimmed.split("\\|"))
                .map(String::trim)
                .collect(Collectors.toList());
    }
}
