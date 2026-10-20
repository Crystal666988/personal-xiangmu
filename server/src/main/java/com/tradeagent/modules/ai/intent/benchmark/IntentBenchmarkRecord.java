package com.tradeagent.modules.ai.intent.benchmark;

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
 * 意图路由评测持久化记录 (对应 MySQL intent_benchmark_record)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("intent_benchmark_record")
public class IntentBenchmarkRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String id;
    private Integer totalCases;
    private Double overallAccuracy;
    private Double chitchatAccuracy;
    private Double singleDomainAccuracy;
    private Double compositeAccuracy;
    private Double avgPruningRate;
    private Double avgLatencyMs;
    private String confusionMatrixJson;
    private String reportJson;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
