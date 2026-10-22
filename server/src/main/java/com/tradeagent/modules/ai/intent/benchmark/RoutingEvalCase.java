package com.tradeagent.modules.ai.intent.benchmark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 路由评测用例：同时约束领域、工具集合与关键参数。
 *
 * 与 IntentBenchmarkCase 的区别：
 * 1. IntentBenchmarkCase 只约束 expectedDomain，主要衡量领域分类准确率；
 * 2. RoutingEvalCase 额外约束 expectedTools / expectedParams，用于衡量工具选择是否正确。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingEvalCase {

    private String id;

    /** CHITCHAT / SINGLE_DOMAIN / COMPOSITE / TRICKY */
    private String category;

    private String query;

    /** 可选附件名，参与 IntentRouter 的附件后缀路由 */
    private String attachmentName;

    /** 期望领域：GENERAL / COMMERCE / KNOWLEDGE / PROSPECT / CRM / VISION / COMPOSITE */
    private String expectedDomain;

    /** 期望调用的工具集合（按集合比较，不要求顺序） */
    private List<String> expectedTools;

    /**
     * 期望的关键参数，只校验这里声明的 key。
     * 未声明的参数不参与评分，避免把工具内部默认值当成错误。
     */
    private Map<String, Object> expectedParams;

    /** 人工说明：为什么应该这样路由，以及这个用例在验证什么 */
    private String explanation;
}
