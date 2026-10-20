package com.tradeagent.modules.ai.intent.benchmark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 意图路由基准评测用例
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentBenchmarkCase {
    private String id;
    /** 分类: CHITCHAT (通用问答/闲聊), SINGLE_DOMAIN (单一垂直路由), COMPOSITE (复合跨领域路由) */
    private String category;
    /** 预期领域: GENERAL, COMMERCE, KNOWLEDGE, PROSPECT, CRM, COMPOSITE */
    private String expectedDomain;
    private String query;
    /** 预期激活工具列表 (通用闲聊预期为空列表) */
    private List<String> expectedTools;
    /** 业务场景解释与预期目标 */
    private String explanation;
}
