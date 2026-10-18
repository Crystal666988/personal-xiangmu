package com.tradeagent.modules.ai.vision;

import com.tradeagent.modules.ai.rag.DocumentChunk;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 多模态文档智能解析与分级降级结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 原始文件名 */
    private String fileName;

    /** 触发的三级管道级别: LEVEL_1_TEXT / LEVEL_2_OCR_FILTER / LEVEL_3_VISION_LLM */
    private String pipelineLevel;

    /** 通路中文描述 */
    private String levelDescription;

    /** 提取的总字符数量 */
    private Integer characterCount;

    /** 解析耗时 (毫秒) */
    private Long costMs;

    /** 渲染的图像页数 (若未降级则为 0) */
    private Integer renderedPages;

    /** 结构化提取出的完整 Markdown 内容 */
    private String rawMarkdown;

    /** 识别并增强的表格数量 */
    private Integer tableCount;

    /** 阿里云 OSS 云端归档访问 URL */
    private String ossUrl;

    /** 实际调度的 OCR/解析引擎名称 (如 Qwen-VL-OCR / Apache PDFBox / Markdown AST) */
    private String ocrEngine;

    /** OCR/文本提取的原始关键切片回显 (便于前端 CoT 回显透明调用过程) */
    private String ocrSnippet;

    /** AST 语法树切片列表 (已继承标题面包屑与表格三元组) */
    private List<DocumentChunk> chunks;
}
