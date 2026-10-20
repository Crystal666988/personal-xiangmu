package com.tradeagent.modules.ai.rag.benchmark;

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
 * RAG 基准评测持久化记录实体 (对应 MySQL rag_benchmark_record 表)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("rag_benchmark_record")
public class RagBenchmarkRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评测任务 ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 评测文档 ID (或 "all") */
    private String docId;

    /** 评测文档名称 */
    private String docName;

    /** 倒排切片数 */
    private Integer totalChunks;

    /** 总考题数 */
    private Integer totalQuestions;

    /** 正向检索题数 */
    private Integer positiveQuestions;

    /** 命中题数 */
    private Integer hitCount;

    /** 召回率 % */
    private Double recallRate;

    /** 缺陷题数 */
    private Integer badCaseCount;

    /** 缺陷率 % */
    private Double badCaseRate;

    /** 对抗诱导题数 */
    private Integer adversarialQuestions;

    /** 对抗防御成功数 */
    private Integer adversarialPassed;

    /** 对抗防御成功率 % */
    private Double adversarialRejectionRate;

    /** 平均检索耗时 ms */
    private Double avgLatencyMs;

    /** 评测大模型 */
    private String modelUsed;

    /** 完整评测报告 JSON (含所有真题、标准答案、判定明细与切片快照) */
    private String reportJson;

    /** 评测创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
