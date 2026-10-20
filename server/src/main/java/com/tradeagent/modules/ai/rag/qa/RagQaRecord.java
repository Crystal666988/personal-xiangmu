package com.tradeagent.modules.ai.rag.qa;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RAG 在线切片溯源问答历史持久化实体 (对应 MySQL rag_qa_record 表)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("rag_qa_record")
public class RagQaRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 问答唯一 ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 用户提出的问题 */
    private String question;

    /** 大模型结合参考切片生成的回答 */
    private String answer;

    /** 检索文档 ID (或 "all") */
    private String docId;

    /** 检索文档名称 */
    private String docName;

    /** 检索 Top-K 参数 */
    private Integer topK;

    /** 召回引用切片数 */
    private Integer citationCount;

    /** 召回切片引用的完整 JSON 序列化 */
    private String citationsJson;

    /** 问答响应耗时 ms */
    private Long latencyMs;

    /** 使用的大模型名称 */
    private String modelUsed;

    /** 人工准确率标记: ACCURATE (准确) / INACCURATE (偏差) / PARTIAL (部分准确) */
    private String userRating;

    /** 人工评测反馈/评语 */
    private String feedback;

    /** 问答时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
