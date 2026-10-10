package com.tradeagent.modules.ai.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 结构化知识库切片实体 (Chunk)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 切片唯一ID */
    private String chunkId;

    /** 所属文档ID */
    private Long documentId;

    /** 切片内容 (已注入标题面包屑与语义富化后的文本) */
    private String content;

    /** 层级标题面包屑路径 (e.g. "技术规格 > 电气特性 > 550W组件") */
    private String breadcrumbPath;

    /** 切片类型: TEXT (普通文本) / TABLE (原子化表格) */
    private String chunkType;

    /** 附加元数据 (行号、原始表头等) */
    private Map<String, Object> metadata;
}
