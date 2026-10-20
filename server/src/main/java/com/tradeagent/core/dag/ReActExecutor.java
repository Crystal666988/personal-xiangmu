package com.tradeagent.core.dag;

import com.tradeagent.modules.ai.tools.ToolExecutionTrace;
import com.tradeagent.modules.ai.tools.ToolRegistry;
import com.tradeagent.modules.ai.tools.TurnTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct (Thought -> Action -> Observation) 决策循环执行引擎
 * 参考 llm-master 智能体单步循环与透明决策链路设计规范
 */
@Slf4j
@Service
public class ReActExecutor {

    private final ToolRegistry toolRegistry;
    private final IntentRouter router;

    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(\\d{1,7})\\s*(套|片|个|块|台|pcs|sets)?");
    private static final Pattern PORT_PATTERN = Pattern.compile("(?i)(hamburg|dubai|jebel ali|rotterdam|singapore|los angeles|汉堡|迪拜|杰贝阿里|鹿特丹|新加坡|洛杉矶)");
    private static final Pattern MODEL_PATTERN = Pattern.compile("(?i)(topcon[-\\s]?580w?|perc[-\\s]?550w?|580w|550w|540w|hjt[-\\s]?700w?)");

    public ReActExecutor(ToolRegistry toolRegistry, IntentRouter router) {
        this.toolRegistry = toolRegistry;
        this.router = router;
    }

    /**
     * 运行单轮 ReAct 决策循环并产生可追溯的时间轴步骤
     *
     * @param userQuery 用户提问
     * @param routing   意图路由结果
     * @return 结构化的 ReAct 步骤列表
     */
    public List<ReActStep> executeReActLoop(String userQuery, IntentRouter.Routing routing) {
        List<ReActStep> steps = new ArrayList<>();
        int stepIdx = 1;
        long startLoopTime = System.currentTimeMillis();

        if (routing == null) {
            routing = router.route(userQuery, null);
        }

        String domain = routing.domain();

        // 1. 阶段一：初始 Thought (思考意图与决策目标)
        long thoughtStart = System.currentTimeMillis();
        String initialThought = buildInitialThought(userQuery, routing);
        long thoughtCost = Math.max(1, System.currentTimeMillis() - thoughtStart);
        steps.add(ReActStep.thought(stepIdx++, initialThought, thoughtCost));

        // 如果是闲聊 GENERAL 或未挂载任何工具，直接进入最终收敛步骤
        if ("GENERAL".equalsIgnoreCase(domain) || routing.activeTools() == null || routing.activeTools().isEmpty()) {
            steps.add(ReActStep.finalAnswer(stepIdx++,
                    "通用日常对话或闲聊交互，已完成无关工具剪枝（剪枝率 100%），无需派发物理工具，直接生成自然语言答复。",
                    Math.max(1, System.currentTimeMillis() - startLoopTime)));
            return steps;
        }

        // 2. 从上下文与提问抽取意图参数
        Map<String, Object> parsedParams = extractEntitiesFromQuery(userQuery);
        String text = (userQuery != null ? userQuery : "").toLowerCase();

        // 3. 商业领域 (COMMERCE) 单步派发：阶梯算价 -> 海运费 -> 汇率
        if (domain.contains("COMMERCE") || text.contains("报价") || text.contains("运费") || text.contains("价格") || text.contains("cif")) {
            // Step: 阶梯算价
            Map<String, Object> priceParams = new LinkedHashMap<>();
            priceParams.put("productModel", parsedParams.getOrDefault("model", "TOPCon-580W"));
            priceParams.put("quantity", parsedParams.getOrDefault("quantity", 2000));

            steps.add(ReActStep.thought(stepIdx++, "需求涉及光伏组件批量采购，需要首先调用阶梯报价工具核算 FOB 货值与单瓦折扣。", 3));
            steps.add(ReActStep.action(stepIdx++, "calculate_tier_price", "阶梯价格计算器", priceParams));
            ToolExecutionTrace priceTrace = toolRegistry.executeTool("calculate_tier_price", priceParams);
            steps.add(ReActStep.observation(stepIdx++, "calculate_tier_price", "阶梯价格计算器",
                    priceTrace.getStatus(), priceTrace.getOutputResult(), priceTrace.getLatencyMs()));

            // Step: CIF 运费核算
            Map<String, Object> freightParams = new LinkedHashMap<>();
            freightParams.put("destPort", parsedParams.getOrDefault("destPort", "Dubai (Jebel Ali)"));
            freightParams.put("quantity", parsedParams.getOrDefault("quantity", 2000));
            if (priceTrace.getOutputResult() instanceof Map<?, ?> map && map.get("fobTotalUsdRaw") != null) {
                freightParams.put("fobTotalUsd", map.get("fobTotalUsdRaw"));
            }

            steps.add(ReActStep.thought(stepIdx++, "已获得 FOB 货值基准，下一步调度海运费估算器核算国际集装箱柜量（40HQ/20GP）与保费。", 4));
            steps.add(ReActStep.action(stepIdx++, "estimate_freight_cif", "CIF/FOB 海运费核算", freightParams));
            ToolExecutionTrace freightTrace = toolRegistry.executeTool("estimate_freight_cif", freightParams);
            steps.add(ReActStep.observation(stepIdx++, "estimate_freight_cif", "CIF/FOB 海运费核算",
                    freightTrace.getStatus(), freightTrace.getOutputResult(), freightTrace.getLatencyMs()));

            // Step: 外汇即期牌价
            Map<String, Object> fxParams = Map.of("baseCurrency", "USD", "targetCurrency", "CNY");
            steps.add(ReActStep.thought(stepIdx++, "外贸交易涉及跨币种汇损评估，查询当前 USD/CNY 即期基准汇率以便结汇核算。", 2));
            steps.add(ReActStep.action(stepIdx++, "currency_exchange_rate", "外汇牌价查询", fxParams));
            ToolExecutionTrace fxTrace = toolRegistry.executeTool("currency_exchange_rate", fxParams);
            steps.add(ReActStep.observation(stepIdx++, "currency_exchange_rate", "外汇牌价查询",
                    fxTrace.getStatus(), fxTrace.getOutputResult(), fxTrace.getLatencyMs()));
        }

        // 4. 知识库领域 (KNOWLEDGE)
        if (domain.contains("KNOWLEDGE") || text.contains("参数") || text.contains("规格") || text.contains("认证") || text.contains("衰减") || text.contains("标准")) {
            Map<String, Object> ragParams = new LinkedHashMap<>();
            ragParams.put("query", userQuery);
            ragParams.put("topK", 3);

            steps.add(ReActStep.thought(stepIdx++, "检测到工业参数或技术认证咨询，调度两阶段知识库进行 1024 维阿里百炼向量 + BM25 混合检索。", 5));
            steps.add(ReActStep.action(stepIdx++, "rag_vector_search", "知识库检索", ragParams));
            ToolExecutionTrace ragTrace = toolRegistry.executeTool("rag_vector_search", ragParams);
            steps.add(ReActStep.observation(stepIdx++, "rag_vector_search", "知识库检索",
                    ragTrace.getStatus(), ragTrace.getOutputResult(), ragTrace.getLatencyMs()));
        }

        // 5. 客户背调领域 (PROSPECT)
        if (domain.contains("PROSPECT") || text.contains("海关") || text.contains("提单") || text.contains("采购商") || text.contains("背调") || text.contains("公司")) {
            Map<String, Object> prospectParams = new LinkedHashMap<>();
            String company = parsedParams.get("companyName") != null ? parsedParams.get("companyName").toString() : "Al-Futtaim Solar Energy Solutions LLC";
            prospectParams.put("companyName", company);

            steps.add(ReActStep.thought(stepIdx++, "针对海外采购商进行贸易真实性穿透，检索海关进出口提单记录与历史采买品类。", 3));
            steps.add(ReActStep.action(stepIdx++, "customs_data_lookup", "海关提单检索", prospectParams));
            ToolExecutionTrace prospectTrace = toolRegistry.executeTool("customs_data_lookup", prospectParams);
            steps.add(ReActStep.observation(stepIdx++, "customs_data_lookup", "海关提单检索",
                    prospectTrace.getStatus(), prospectTrace.getOutputResult(), prospectTrace.getLatencyMs()));
        }

        // 6. 终审与整合步骤 (Final Answer)
        long totalCost = Math.max(1, System.currentTimeMillis() - startLoopTime);
        long successCount = steps.stream().filter(s -> s.getType() == ReActStep.StepType.OBSERVATION && "SUCCESS".equalsIgnoreCase(s.getStatus())).count();
        steps.add(ReActStep.finalAnswer(stepIdx,
                String.format("ReAct 决策闭环完成：已成功观测并采集 %d 项业务事实数据，准备交由大模型进行综合专业研判与流式输出。", successCount),
                totalCost));

        return steps;
    }

    /**
     * 将 ReAct 步骤列表转换为旧版本的 TurnTelemetry 对象，保证历史 API 消费方无缝兼容
     */
    public TurnTelemetry toTurnTelemetry(List<ReActStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return TurnTelemetry.builder()
                    .turnId("turn_" + System.currentTimeMillis())
                    .toolsCalledCount(0)
                    .totalLatencyMs(0)
                    .avgLatencyMs(0.0)
                    .traces(Collections.emptyList())
                    .build();
        }

        List<ToolExecutionTrace> traces = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            ReActStep step = steps.get(i);
            if (step.getType() == ReActStep.StepType.OBSERVATION) {
                // 寻找前序对应的 Action 取入参
                Map<String, Object> queryInput = Collections.emptyMap();
                for (int j = i - 1; j >= 0; j--) {
                    if (steps.get(j).getType() == ReActStep.StepType.ACTION && Objects.equals(steps.get(j).getToolId(), step.getToolId())) {
                        queryInput = steps.get(j).getToolParams();
                        break;
                    }
                }

                ToolExecutionTrace trace = ToolExecutionTrace.builder()
                        .toolId(step.getToolId())
                        .toolName(step.getToolName())
                        .status(step.getStatus())
                        .latencyMs(step.getDurationMs())
                        .queryInput(queryInput)
                        .outputResult(step.getToolOutput())
                        .build();
                traces.add(trace);
            }
        }

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

    private String buildInitialThought(String userQuery, IntentRouter.Routing routing) {
        String domainName = routing != null ? routing.domainName() : "通用";
        String domain = routing != null ? routing.domain() : "GENERAL";
        int toolCount = (routing != null && routing.activeTools() != null) ? routing.activeTools().size() : 0;
        int prunedCount = routing != null ? routing.prunedToolsCount() : 0;

        return String.format("分析用户提问：「%s」 -> 意图识别为【%s】(%s)。依据领域规则挂载 %d 个核心业务工具，并对 %d 个非相关工具执行上下文安全剪枝。",
                userQuery != null ? (userQuery.length() > 30 ? userQuery.substring(0, 30) + "..." : userQuery) : "",
                domainName, domain, toolCount, prunedCount);
    }

    private Map<String, Object> extractEntitiesFromQuery(String query) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return map;

        Matcher qm = QUANTITY_PATTERN.matcher(query);
        while (qm.find()) {
            try {
                int q = Integer.parseInt(qm.group(1));
                if (q >= 50 && q <= 500000) {
                    map.put("quantity", q);
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        Matcher pm = PORT_PATTERN.matcher(query);
        if (pm.find()) {
            String p = pm.group(1);
            if (p.equalsIgnoreCase("hamburg") || p.contains("汉堡")) map.put("destPort", "Hamburg, Germany");
            else if (p.equalsIgnoreCase("dubai") || p.contains("迪拜") || p.contains("杰贝阿里"))
                map.put("destPort", "Dubai (Jebel Ali)");
            else if (p.equalsIgnoreCase("rotterdam") || p.contains("鹿特丹"))
                map.put("destPort", "Rotterdam, Netherlands");
            else if (p.equalsIgnoreCase("singapore") || p.contains("新加坡")) map.put("destPort", "Singapore Port");
            else if (p.equalsIgnoreCase("los angeles") || p.contains("洛杉矶")) map.put("destPort", "Los Angeles, USA");
        }

        Matcher mm = MODEL_PATTERN.matcher(query);
        if (mm.find()) {
            map.put("model", mm.group(1).toUpperCase());
        }

        if (query.contains("Al-Futtaim") || query.contains("alfuttaim")) {
            map.put("companyName", "Al-Futtaim Solar Energy Solutions LLC");
        } else if (query.contains("BayWa") || query.contains("baywa")) {
            map.put("companyName", "BayWa r.e. Solar Trade GmbH");
        }

        return map;
    }
}
