package com.tradeagent.modules.ai.memory.episodic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 历史成功交易情景案例模型 (Episodic Case)
 *
 * 对应 Carl (youngyangyang04/llm-master) 中的 Episodic Memory 单元：
 * 记录特定时间、客户诉求、成功解决方案与工具调用链的高价值案例。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpisodicCase implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 案例唯一编号 */
    private String caseId;

    /** 租户标识 */
    private String tenantId;

    /** 来源会话标识 */
    private String sessionId;

    /** 客户原始真实询盘/诉求 */
    private String userInquiry;

    /** 最终高分成交方案摘要 */
    private String solutionSummary;

    /** 完整黄金工具调用链 (如 [calculate_tier_price, estimate_freight_cif]) */
    @Builder.Default
    private List<String> toolChain = Collections.emptyList();

    /** 评价类型: DEAL_CLOSED (真实成交), UPVOTE (业务员点赞), BENCHMARK_GOLDEN */
    private String rating;

    /** 成交金额 (USD) */
    private Double dealAmount;

    /** 1024 维百炼文本嵌入向量 (text-embedding-v3) */
    private float[] embedding;

    /** 扩展元数据 */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    /** 沉淀入库时间 */
    private LocalDateTime createdAt;
}
