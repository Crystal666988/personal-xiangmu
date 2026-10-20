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
 * 知识库切片持久化实体类 (对应 MySQL 表 document_chunk)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("document_chunk")
public class DocumentChunkEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 切片唯一ID */
    @TableId(type = IdType.INPUT)
    private String chunkId;

    /** 所属文档ID */
    private String docId;

    /** 切片内容 (已注入标题面包屑与语义富化后的文本) */
    private String content;

    /** 层级标题面包屑路径 (e.g. "技术规格 > 电气特性 > 550W组件") */
    private String breadcrumbPath;

    /** 切片类型: TEXT (普通文本) / TABLE (原子化表格) */
    private String chunkType;

    /** 阿里百炼 1024 维稠密特征向量 JSON (float[]) */
    private String vectorJson;

    /** 附加元数据 JSON */
    private String metadataJson;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
