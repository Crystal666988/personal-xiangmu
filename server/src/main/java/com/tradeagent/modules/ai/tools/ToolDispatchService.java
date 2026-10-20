package com.tradeagent.modules.ai.tools;

import com.tradeagent.core.dag.IntentRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体工具自动派发与聚合遥测服务
 */
@Slf4j
@Service
public class ToolDispatchService {

    private final ToolRegistry toolRegistry;
    private final IntentRouter router;

    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(\\d{1,7})\\s*(套|片|个|块|台|pcs|sets)?");
    private static final Pattern PORT_PATTERN = Pattern.compile("(?i)(hamburg|dubai|jebel ali|rotterdam|singapore|los angeles|汉堡|迪拜|杰贝阿里|鹿特丹|新加坡|洛杉矶)");
    private static final Pattern MODEL_PATTERN = Pattern.compile("(?i)(topcon[-\\s]?580w?|perc[-\\s]?550w?|580w|550w|540w|hjt[-\\s]?700w?)");

    public ToolDispatchService(ToolRegistry toolRegistry, IntentRouter router) {
        this.toolRegistry = toolRegistry;
        this.router = router;
    }

    /**
     * 单独物理调用一个工具
     */
    public ToolExecutionTrace executeTool(String toolId, Map<String, Object> params) {
        return toolRegistry.executeTool(toolId, params);
    }

    /**
     * 根据用户自然语言提问与意图路由，自动提取入参并调度匹配的真实业务工具
     *
     * @param userQuery 用户提问
     * @param routing 意图路由结果 (若为空则自动路由)
     * @return 聚合单轮对话遥测信息 TurnTelemetry
     */
    public TurnTelemetry autoDispatch(String userQuery, IntentRouter.Routing routing) {
        if (routing == null) {
            routing = router.route(userQuery, null);
        }

        String domain = routing.domain();
        List<ToolExecutionTrace> traces = new ArrayList<>();

        // 1. 如果是通用闲聊 GENERAL，不调用任何工具，剪枝率 100%
        if ("GENERAL".equalsIgnoreCase(domain) || routing.activeTools().isEmpty()) {
            return TurnTelemetry.builder()
                    .turnId("turn_" + System.currentTimeMillis())
                    .toolsCalledCount(0)
                    .totalLatencyMs(0)
                    .avgLatencyMs(0.0)
                    .traces(Collections.emptyList())
                    .build();
        }

        // 2. 根据用户提问解析实体与意图参数
        Map<String, Object> parsedParams = extractEntitiesFromQuery(userQuery);

        // 3. 针对不同领域调度对应真实工具
        String text = (userQuery != null ? userQuery : "").toLowerCase();

        // 商业领域 (COMMERCE)
        if (domain.contains("COMMERCE") || text.contains("报价") || text.contains("运费") || text.contains("价格") || text.contains("cif")) {
            // 3.1 阶梯算价
            Map<String, Object> priceParams = new LinkedHashMap<>();
            priceParams.put("productModel", parsedParams.getOrDefault("model", "TOPCon-580W"));
            priceParams.put("quantity", parsedParams.getOrDefault("quantity", 2000));
            ToolExecutionTrace priceTrace = toolRegistry.executeTool("calculate_tier_price", priceParams);
            traces.add(priceTrace);

            // 3.2 CIF 运费核算
            Map<String, Object> freightParams = new LinkedHashMap<>();
            freightParams.put("destPort", parsedParams.getOrDefault("destPort", "Dubai (Jebel Ali)"));
            freightParams.put("quantity", parsedParams.getOrDefault("quantity", 2000));
            if (priceTrace.getOutputResult() instanceof Map<?, ?> map && map.get("fobTotalUsdRaw") != null) {
                freightParams.put("fobTotalUsd", map.get("fobTotalUsdRaw"));
            }
            ToolExecutionTrace freightTrace = toolRegistry.executeTool("estimate_freight_cif", freightParams);
            traces.add(freightTrace);

            // 3.3 外汇汇率
            ToolExecutionTrace fxTrace = toolRegistry.executeTool("currency_exchange_rate", Map.of("baseCurrency", "USD", "targetCurrency", "CNY"));
            traces.add(fxTrace);
        }

        // 知识库领域 (KNOWLEDGE)
        if (domain.contains("KNOWLEDGE") || text.contains("参数") || text.contains("规格") || text.contains("认证") || text.contains("衰减") || text.contains("标准")) {
            Map<String, Object> ragParams = new LinkedHashMap<>();
            ragParams.put("query", userQuery);
            ragParams.put("topK", 3);
            ToolExecutionTrace ragTrace = toolRegistry.executeTool("rag_vector_search", ragParams);
            traces.add(ragTrace);
        }

        // 客户背调领域 (PROSPECT)
        if (domain.contains("PROSPECT") || text.contains("海关") || text.contains("提单") || text.contains("采购商") || text.contains("背调") || text.contains("公司")) {
            Map<String, Object> prospectParams = new LinkedHashMap<>();
            String company = parsedParams.get("companyName") != null ? parsedParams.get("companyName").toString() : "Al-Futtaim Solar Energy Solutions LLC";
            prospectParams.put("companyName", company);
            ToolExecutionTrace prospectTrace = toolRegistry.executeTool("customs_data_lookup", prospectParams);
            traces.add(prospectTrace);
        }

        // 4. 计算本轮遥测聚合数据
        long totalLatency = traces.stream().mapToLong(ToolExecutionTrace::getLatencyMs).sum();
        double avgLatency = traces.isEmpty() ? 0.0 : Math.round(((double) totalLatency / traces.size()) * 10.0) / 10.0;

        return TurnTelemetry.builder()
                .turnId("turn_" + System.currentTimeMillis())
                .toolsCalledCount(traces.size())
                .totalLatencyMs(totalLatency)
                .avgLatencyMs(avgLatency)
                .traces(traces)
                .build();
    }

    /**
     * 从用户 Prompt 中提取实体参数
     */
    private Map<String, Object> extractEntitiesFromQuery(String query) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return map;

        // 数量
        Matcher qm = QUANTITY_PATTERN.matcher(query);
        while (qm.find()) {
            try {
                int q = Integer.parseInt(qm.group(1));
                if (q >= 50 && q <= 500000) {
                    map.put("quantity", q);
                    break;
                }
            } catch (Exception ignored) {}
        }

        // 目的港
        Matcher pm = PORT_PATTERN.matcher(query);
        if (pm.find()) {
            String p = pm.group(1);
            if (p.equalsIgnoreCase("hamburg") || p.contains("汉堡")) map.put("destPort", "Hamburg, Germany");
            else if (p.equalsIgnoreCase("dubai") || p.contains("迪拜") || p.contains("杰贝阿里")) map.put("destPort", "Dubai (Jebel Ali)");
            else if (p.equalsIgnoreCase("rotterdam") || p.contains("鹿特丹")) map.put("destPort", "Rotterdam, Netherlands");
            else if (p.equalsIgnoreCase("singapore") || p.contains("新加坡")) map.put("destPort", "Singapore Port");
            else if (p.equalsIgnoreCase("los angeles") || p.contains("洛杉矶")) map.put("destPort", "Los Angeles, USA");
        }

        // 型号
        Matcher mm = MODEL_PATTERN.matcher(query);
        if (mm.find()) {
            map.put("model", mm.group(1).toUpperCase());
        }

        // 企业名称匹配 (粗提取)
        if (query.contains("Al-Futtaim") || query.contains("alfuttaim")) {
            map.put("companyName", "Al-Futtaim Solar Energy Solutions LLC");
        } else if (query.contains("BayWa") || query.contains("baywa")) {
            map.put("companyName", "BayWa r.e. Solar Trade GmbH");
        }

        return map;
    }
}
