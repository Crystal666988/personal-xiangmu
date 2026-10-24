package com.tradeagent.core.dag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 路由规划器：只产出工具调用计划，不执行任何工具。
 *
 * 定位（P2 路由体系归一）：这是【规则路由评测基线】，供 RoutingEval 基线测量规则路由的能力边界；
 * 生产聊天主路径是 LLM 规划管线（RoutingWorkflowService → RoutingPreviewService → ValidatedPlanExecutor）。
 * 修正规则口径时只需维护本类与 RoutingEntityExtractor，不再影响生产链路。
 */
@Component
public class RoutingPlanner {

    private final IntentRouter router;

    public RoutingPlanner(IntentRouter router) {
        this.router = router;
    }

    public RoutingPlan plan(String query, String attachmentName) {
        IntentRouter.Routing routing = router.route(query, attachmentName);
        String domain = routing.domain();
        List<ToolCallPlan> calls = new ArrayList<>();

        if (!"GENERAL".equalsIgnoreCase(domain)
                && routing.activeTools() != null
                && !routing.activeTools().isEmpty()) {
            Map<String, Object> parsed = RoutingEntityExtractor.extract(query);
            String text = query != null ? query.toLowerCase(Locale.ROOT) : "";

            // COMMERCE：当前基线是“只要命中商业领域，就把报价、运费、汇率三件套全部规划上”
            if (domain.contains("COMMERCE")
                    || text.contains("报价") || text.contains("运费")
                    || text.contains("价格") || text.contains("cif")) {
                plansForCommerce(parsed, calls);
            }

            // KNOWLEDGE：当前基线只规划 RAG 检索
            if (domain.contains("KNOWLEDGE")
                    || text.contains("参数") || text.contains("规格")
                    || text.contains("认证") || text.contains("衰减")
                    || text.contains("标准")) {
                Map<String, Object> ragParams = new LinkedHashMap<>();
                ragParams.put("query", query);
                ragParams.put("topK", 3);
                calls.add(new ToolCallPlan("rag_vector_search", "知识库检索", ragParams));
            }

            // PROSPECT：当前基线只规划海关工具
            if (domain.contains("PROSPECT")
                    || text.contains("海关") || text.contains("提单")
                    || text.contains("采购商") || text.contains("背调")
                    || text.contains("公司")) {
                Map<String, Object> prospectParams = new LinkedHashMap<>();
                String company = parsed.get("companyName") != null
                        ? parsed.get("companyName").toString()
                        : "Al-Futtaim Solar Energy Solutions LLC";
                prospectParams.put("companyName", company);
                calls.add(new ToolCallPlan("customs_data_lookup", "海关提单检索", prospectParams));
            }

            // 当前基线没有 CRM / VISION 的执行分支
        }

        List<String> candidateTools = routing.activeTools().stream()
                .map(IntentRouter.Tool::id)
                .toList();

        return new RoutingPlan(
                routing.domain(),
                routing.domainName(),
                candidateTools,
                calls,
                routing.reason()
        );
    }

    private void plansForCommerce(Map<String, Object> parsed, List<ToolCallPlan> calls) {
        Map<String, Object> priceParams = new LinkedHashMap<>();
        priceParams.put("productModel", parsed.getOrDefault("model", "TOPCon-580W"));
        priceParams.put("quantity", parsed.getOrDefault("quantity", 2000));
        calls.add(new ToolCallPlan("calculate_tier_price", "阶梯价格计算器", priceParams));

        Map<String, Object> freightParams = new LinkedHashMap<>();
        freightParams.put("destPort", parsed.getOrDefault("destPort", "Dubai (Jebel Ali)"));
        freightParams.put("quantity", parsed.getOrDefault("quantity", 2000));
        calls.add(new ToolCallPlan("estimate_freight_cif", "CIF/FOB 海运费核算", freightParams));

        calls.add(new ToolCallPlan(
                "currency_exchange_rate",
                "外汇牌价查询",
                Map.of("baseCurrency", "USD", "targetCurrency", "CNY")
        ));
    }
}
