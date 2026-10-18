package com.tradeagent.modules.ai.rag.kb;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 知识库文档实体 (Knowledge Document)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 文档唯一标识 (如 doc_1792315328976) */
    private String id;

    /** 文档原始名称 */
    private String name;

    /** 文件大小 (字节数) */
    private long size;

    /** 文件类型 (MARKDOWN / PDF / TXT / DOCX) */
    private String type;

    /** 结构化切片总数 */
    private int chunkCount;

    /** 上传与入库时间 */
    private String uploadTime;

    /** 入库状态: INDEXED (已建立倒排索引与切片) */
    private String status;

    /** 阿里云 OSS 归档地址 (如有) */
    private String ossUrl;

    /** 文档原始文本内容 */
    private String rawContent;
}
