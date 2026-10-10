package com.tradeagent.modules.ai.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工业级 AST 语法感知 Markdown 分块切片器 (Hierarchical Markdown Chunker)
 * 
 * 核心技术机制：
 * 1. 【标题面包屑继承】：动态维护 H1 > H2 > H3 标题堆栈，切出的子段落强制注入完整面包屑路径；
 * 2. 【表格块整体识别与委托】：检测到表格行时成块提取，交给 MarkdownTableProcessor 进行语义三元组增强；
 * 3. 【段落语义完整性】：按双换行与自然段落分界，杜绝句中截断。
 */
@Slf4j
@Component
public class AstMarkdownChunker {

    @Resource
    private MarkdownTableProcessor tableProcessor;

    /**
     * 对 Markdown 文本进行 AST 语义分块
     *
     * @param documentId 文档ID
     * @param markdownContent 原始 Markdown 全文
     * @return 增强切片列表
     */
    public List<DocumentChunk> split(Long documentId, String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return Collections.emptyList();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        String[] lines = markdownContent.split("\r?\n");

        // 维护当前的标题层级：1->H1, 2->H2, 3->H3
        Map<Integer, String> headerStack = new LinkedHashMap<>();

        List<String> currentParagraphLines = new ArrayList<>();
        List<String> currentTableLines = new ArrayList<>();
        boolean inTable = false;
        int chunkIndex = 1;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // 1. 检测标题行 (# H1, ## H2, ### H3)
            if (line.startsWith("#")) {
                // 先把前面积攒的段落或表格结算成 Chunk
                flushBuffers(documentId, chunks, headerStack, currentParagraphLines, currentTableLines, inTable, chunkIndex++);
                inTable = false;

                // 更新标题层级
                int level = countHeaderLevel(line);
                String title = line.substring(level).trim();
                // 清理比当前级别更深的旧子标题
                headerStack.entrySet().removeIf(entry -> entry.getKey() >= level);
                headerStack.put(level, title);
                continue;
            }

            // 2. 检测表格行
            if (tableProcessor.isTableRow(line)) {
                if (!inTable) {
                    // 刚从普通段落切到表格，结算前面的普通段落
                    flushParagraph(documentId, chunks, headerStack, currentParagraphLines, chunkIndex++);
                    inTable = true;
                }
                currentTableLines.add(line);
                continue;
            } else {
                if (inTable) {
                    // 表格结束了，结算表格
                    flushTable(documentId, chunks, headerStack, currentTableLines, chunkIndex++);
                    inTable = false;
                }
            }

            // 3. 空行：作为段落切分点
            if (line.isEmpty()) {
                if (!currentParagraphLines.isEmpty()) {
                    flushParagraph(documentId, chunks, headerStack, currentParagraphLines, chunkIndex++);
                }
                continue;
            }

            // 4. 普通正文内容行追加
            currentParagraphLines.add(rawLine);
        }

        // 尾部最后未结算的缓冲区清理
        flushBuffers(documentId, chunks, headerStack, currentParagraphLines, currentTableLines, inTable, chunkIndex);

        log.info("【RAG 切片完成】文档 {} 成功切分为 {} 个结构化 Chunk (含表格语义加固)", documentId, chunks.size());
        return chunks;
    }

    private int countHeaderLevel(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return count;
    }

    private String buildBreadcrumb(Map<Integer, String> headerStack) {
        if (headerStack.isEmpty()) return "正文";
        return String.join(" > ", headerStack.values());
    }

    private void flushParagraph(Long docId, List<DocumentChunk> chunks, Map<Integer, String> headers,
                                List<String> lines, int index) {
        if (lines.isEmpty()) return;
        String content = String.join("\n", lines).trim();
        if (content.isEmpty()) return;

        String breadcrumb = buildBreadcrumb(headers);
        String enrichedContent = String.format("【所属章节: %s】\n%s", breadcrumb, content);

        chunks.add(DocumentChunk.builder()
                .chunkId(docId + "_chunk_" + index)
                .documentId(docId)
                .content(enrichedContent)
                .breadcrumbPath(breadcrumb)
                .chunkType("TEXT")
                .build());
        lines.clear();
    }

    private void flushTable(Long docId, List<DocumentChunk> chunks, Map<Integer, String> headers,
                            List<String> tableLines, int index) {
        if (tableLines.isEmpty()) return;
        String breadcrumb = buildBreadcrumb(headers);
        String serializedTable = tableProcessor.serializeTableWithSemantics(tableLines, breadcrumb);

        chunks.add(DocumentChunk.builder()
                .chunkId(docId + "_table_" + index)
                .documentId(docId)
                .content(serializedTable)
                .breadcrumbPath(breadcrumb)
                .chunkType("TABLE")
                .build());
        tableLines.clear();
    }

    private void flushBuffers(Long docId, List<DocumentChunk> chunks, Map<Integer, String> headers,
                             List<String> paragraphLines, List<String> tableLines, boolean inTable, int index) {
        if (inTable) {
            flushTable(docId, chunks, headers, tableLines, index);
        } else {
            flushParagraph(docId, chunks, headers, paragraphLines, index);
        }
    }
}
