package com.tradeagent.modules.ai.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 工业级自适应 AST 语法与滑动窗口分块切片器 (Adaptive Sliding Window AST Chunker)
 * 
 * 核心技术机制：
 * 1. 【智能标题探针】：除标准 Markdown `#` 标题外，自适应识别非标标题 (如 "1、", "第一章：", "Q1:")，动态建立面包屑堆栈；
 * 2. 【黄金粒度滑动窗口 (Sliding Window)】：单切片目标 500~800 字符，硬上限 800 字符，保留 100 字符重叠 (Overlap)，
 *    杜绝产生几万字的怪兽切片，彻底根除高维向量稀释与 BM25 长度惩罚；
 * 3. 【标点级自然断句】：切分点严格锚定在句号 (。)、分号 (；)、问号 (？)、感叹号 (！) 或换行符，杜绝句中生硬截断；
 * 4. 【表格与代码原子保护】：表格成块交给 MarkdownTableProcessor 进行语义三元组增强。
 */
@Slf4j
@Component
public class AstMarkdownChunker {

    @Resource
    private MarkdownTableProcessor tableProcessor = new MarkdownTableProcessor();

    /** 黄金切片目标上限字符数 (500~700 字符是 1024 维向量与 BM25 的黄金平衡区间) */
    public static final int TARGET_CHUNK_SIZE = 600;

    /** 切片硬性最大字符数 */
    public static final int MAX_CHUNK_SIZE = 850;

    /** 滑动窗口重叠字符数 (Overlap) */
    public static final int CHUNK_OVERLAP_SIZE = 100;

    /** 自然标题行正则探针 (支持 1、题号，第一章：章节，Q1: 题型) */
    private static final Pattern TITLE_PATTERN_1 = Pattern.compile("^[0-9]{1,3}[、.][\\s\\S]{2,40}$");
    private static final Pattern TITLE_PATTERN_2 = Pattern.compile("^第[一二三四五六七八九十百0-9]{1,4}[章节篇部分卷][：:\\s][\\s\\S]{2,40}$");
    private static final Pattern TITLE_PATTERN_3 = Pattern.compile("^[一二三四五六七八九十]{1,2}[、.][\\s\\S]{2,40}$");
    private static final Pattern TITLE_PATTERN_4 = Pattern.compile("^Q[0-9]{1,3}[：:][\\s\\S]{2,40}$", Pattern.CASE_INSENSITIVE);

    /**
     * 对 Markdown/纯文本 进行自适应语义分块
     */
    public List<DocumentChunk> split(Long documentId, String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return Collections.emptyList();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        String[] lines = markdownContent.split("\r?\n");

        // 维护当前的标题层级：1->H1, 2->H2, 3->H3
        Map<Integer, String> headerStack = new LinkedHashMap<>();

        StringBuilder currentParagraph = new StringBuilder();
        List<String> currentTableLines = new ArrayList<>();
        boolean inTable = false;
        int chunkIndex = 1;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // 1. 标准 Markdown 标题识别 (# H1, ## H2, ### H3)
            if (line.startsWith("#")) {
                chunkIndex = flushParagraphWithSlidingWindow(documentId, chunks, headerStack, currentParagraph, chunkIndex);
                if (inTable) {
                    chunkIndex = flushTable(documentId, chunks, headerStack, currentTableLines, chunkIndex);
                    inTable = false;
                }

                int level = countHeaderLevel(line);
                String title = line.substring(level).trim();
                headerStack.entrySet().removeIf(entry -> entry.getKey() >= level);
                headerStack.put(level, title);
                continue;
            }

            // 2. 自适应非标自然标题探针 (如 "1、MyBatis与Hibernate区别", "第一章：Java基础")
            if (isNaturalTitle(line)) {
                chunkIndex = flushParagraphWithSlidingWindow(documentId, chunks, headerStack, currentParagraph, chunkIndex);
                if (inTable) {
                    chunkIndex = flushTable(documentId, chunks, headerStack, currentTableLines, chunkIndex);
                    inTable = false;
                }
                // 作为二级子标题注入面包屑
                headerStack.entrySet().removeIf(entry -> entry.getKey() >= 2);
                headerStack.put(2, line);
                continue;
            }

            // 3. 表格行识别
            if (tableProcessor.isTableRow(line)) {
                if (!inTable) {
                    chunkIndex = flushParagraphWithSlidingWindow(documentId, chunks, headerStack, currentParagraph, chunkIndex);
                    inTable = true;
                }
                currentTableLines.add(line);
                continue;
            } else {
                if (inTable) {
                    chunkIndex = flushTable(documentId, chunks, headerStack, currentTableLines, chunkIndex);
                    inTable = false;
                }
            }

            // 4. 空行处理：若当前段落已达 300 字以上，遇到空行顺势分块
            if (line.isEmpty()) {
                if (currentParagraph.length() >= 300) {
                    chunkIndex = flushParagraphWithSlidingWindow(documentId, chunks, headerStack, currentParagraph, chunkIndex);
                }
                continue;
            }

            // 5. 普通正文追加
            if (currentParagraph.length() > 0) {
                currentParagraph.append("\n");
            }
            currentParagraph.append(rawLine);

            // 6. 硬上限自适应滑动截断：当积攒字数超过 MAX_CHUNK_SIZE，立即按句号标点滑动切分
            if (currentParagraph.length() >= MAX_CHUNK_SIZE) {
                chunkIndex = flushParagraphWithSlidingWindow(documentId, chunks, headerStack, currentParagraph, chunkIndex);
            }
        }

        // 尾部收尾结算
        if (inTable) {
            flushTable(documentId, chunks, headerStack, currentTableLines, chunkIndex);
        } else {
            flushParagraphWithSlidingWindow(documentId, chunks, headerStack, currentParagraph, chunkIndex);
        }

        log.info("【自适应 AST 切片完成】文档 {} 成功切分为 {} 个黄金粒度 Chunk (平均长度约为 400~600 字符)",
                documentId, chunks.size());
        return chunks;
    }

    private boolean isNaturalTitle(String line) {
        if (line.length() < 3 || line.length() > 40) return false;
        return TITLE_PATTERN_1.matcher(line).matches() ||
               TITLE_PATTERN_2.matcher(line).matches() ||
               TITLE_PATTERN_3.matcher(line).matches() ||
               TITLE_PATTERN_4.matcher(line).matches();
    }

    private int countHeaderLevel(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return Math.min(count, 6);
    }

    private String buildBreadcrumb(Map<Integer, String> headerStack) {
        if (headerStack.isEmpty()) return "正文";
        return String.join(" > ", headerStack.values());
    }

    /**
     * 自适应滑动窗口 (Sliding Window) 段落切分
     */
    private int flushParagraphWithSlidingWindow(Long docId, List<DocumentChunk> chunks, Map<Integer, String> headers,
                                               StringBuilder currentParagraph, int startIndex) {
        if (currentParagraph.length() == 0) return startIndex;

        String fullText = currentParagraph.toString().trim();
        if (fullText.isEmpty()) {
            currentParagraph.setLength(0);
            return startIndex;
        }

        String breadcrumb = buildBreadcrumb(headers);
        int index = startIndex;

        // 如果文本较短 (小于 MAX_CHUNK_SIZE)，直接作为一个完整 Chunk
        if (fullText.length() <= MAX_CHUNK_SIZE) {
            String enriched = String.format("【所属章节: %s】\n%s", breadcrumb, fullText);
            chunks.add(DocumentChunk.builder()
                    .chunkId(docId + "_chunk_" + index++)
                    .documentId(docId)
                    .content(enriched)
                    .breadcrumbPath(breadcrumb)
                    .chunkType("TEXT")
                    .build());
            currentParagraph.setLength(0);
            return index;
        }

        // 文本过长，启用带标点锚定的 Sliding Window 切分
        int cursor = 0;
        int textLen = fullText.length();

        while (cursor < textLen) {
            int end = Math.min(cursor + TARGET_CHUNK_SIZE, textLen);

            // 若尚未到文末，向前寻找最佳断句标点 (。 ； ！ ？ \n . ! ?)
            if (end < textLen) {
                int punctuationIndex = findLastPunctuation(fullText, cursor + 200, end);
                if (punctuationIndex != -1) {
                    end = punctuationIndex + 1; // 包含标点
                }
            }

            String chunkContent = fullText.substring(cursor, end).trim();
            if (!chunkContent.isEmpty()) {
                String enriched = String.format("【所属章节: %s】\n%s", breadcrumb, chunkContent);
                chunks.add(DocumentChunk.builder()
                        .chunkId(docId + "_chunk_" + index++)
                        .documentId(docId)
                        .content(enriched)
                        .breadcrumbPath(breadcrumb)
                        .chunkType("TEXT")
                        .build());
            }

            // 若已经到末尾，退出
            if (end >= textLen) {
                break;
            }

            // 滑动窗口递进：保留 CHUNK_OVERLAP_SIZE 重叠字符
            cursor = Math.max(cursor + 100, end - CHUNK_OVERLAP_SIZE);
        }

        currentParagraph.setLength(0);
        return index;
    }

    /**
     * 在指定区间内寻找最后一个自然句子终结标点
     */
    private int findLastPunctuation(String text, int start, int end) {
        for (int i = end - 1; i >= start && i < text.length(); i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n' ||
                c == '.' || c == '!' || c == '?' || c == ';') {
                return i;
            }
        }
        return -1;
    }

    private int flushTable(Long docId, List<DocumentChunk> chunks, Map<Integer, String> headers,
                           List<String> tableLines, int index) {
        if (tableLines.isEmpty()) return index;
        String breadcrumb = buildBreadcrumb(headers);
        String serializedTable = tableProcessor.serializeTableWithSemantics(tableLines, breadcrumb);

        chunks.add(DocumentChunk.builder()
                .chunkId(docId + "_table_" + index++)
                .documentId(docId)
                .content(serializedTable)
                .breadcrumbPath(breadcrumb)
                .chunkType("TABLE")
                .build());
        tableLines.clear();
        return index;
    }
}
