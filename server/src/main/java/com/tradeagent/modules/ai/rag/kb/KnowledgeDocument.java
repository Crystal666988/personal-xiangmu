package com.tradeagent.modules.ai.rag.kb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档持久化实体 (Knowledge Document - 对应 MySQL 表 knowledge_document)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_document")
public class KnowledgeDocument implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 文档唯一标识 (如 doc_1792315328976) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 文档原始名称 */
    private String name;

    /** 文件大小 (字节数) */
    private Long size;

    /** 文件类型 (MARKDOWN / PDF / TXT / DOCX) */
    private String type;

    /** 结构化切片总数 */
    private Integer chunkCount;

    /** 上传与入库展示时间 */
    private String uploadTime;

    /** 入库状态: INDEXED (已建立倒排索引与切片) */
    private String status;

    /** 阿里云 OSS 归档地址 (如有) */
    private String ossUrl;

    /** 文档原始文本内容 */
    private String rawContent;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 逻辑删除: 0未删除 1已删除 */
    @TableLogic
    private Integer deleted;
}
