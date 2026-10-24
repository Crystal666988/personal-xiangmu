package com.tradeagent.modules.ai.intent.benchmark;

import com.tradeagent.core.dag.EvidenceRoutingPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tradeagent.core.dag.EvidenceRoutingPlan.SlotCandidate;
import static com.tradeagent.core.dag.EvidenceRoutingPlan.SlotType;
import static com.tradeagent.core.dag.EvidenceRoutingPlan.Task;
import static com.tradeagent.modules.ai.intent.benchmark.AgentTrajectoryBenchmarkService.planJson;

/**
 * 任务八：Agent 轨迹黄金评测数据集（10 条，零网络依赖）。
 *
 * 构成：
 * - GENERAL 1 条：闲聊零工具，规划步数比 1.0；
 * - SINGLE_TURN 6 条：报价 / 运费 / CIF 依赖接力 / 汇率 / 报价+汇率 / 海关背调；
 * - SELF_CORRECTION 2 条：首轮缺参被追问、补充后 1 轮内正确执行（自愈预算 2 轮内）；
 * - SAFETY_CLARIFY 1 条：未登记运价港口（阿布扎比）整单预检拦截，禁止错误执行。
 *
 * 所有槽位 evidence 均为用户原文的逐字子串；黄金参数值与 TypedSlotParser 规范值一致。
 */
public final class AgentTrajectoryDataset {

    private AgentTrajectoryDataset() {
    }

    public static List<AgentTrajectoryCase> getBenchmarkCases() {
        List<AgentTrajectoryCase> list = new ArrayList<>();

        // ==========================================
        // 1. 通用闲聊：零工具，零执行
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-GEN-001").category("GENERAL")
                .query("今天深圳天气怎么样？")
                .round1PlanJson(planJson(new Task[]{}, new SlotCandidate[]{}))
                .expectedRound1Status("GENERAL")
                .goldenTools(List.of())
                .goldenParams(Map.of())
                .explanation("闲聊空计划直达 GENERAL，理论步数 0/实际 0，步数比 1.0")
                .build());

        // ==========================================
        // 2. 单轮：纯阶梯报价
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-PRICE-001").category("SINGLE_TURN")
                .query("请报价TOPCon580W，采购2,000片")
                .round1PlanJson(planJson(
                        new Task[]{task("calculate_tier_price", "productModel", "m", "quantity", "q")},
                        new SlotCandidate[]{
                                slot("m", SlotType.MODEL, "TOPCon580W", "u1"),
                                slot("q", SlotType.QUANTITY, "2,000片", "u1")}))
                .expectedRound1Status("EXECUTED")
                .goldenTools(List.of("calculate_tier_price"))
                .goldenParams(Map.of("productModel", "TOPCon-580W", "quantity", 2000))
                .explanation("只问报价只调报价；AED 580W 不允许被当成采购数量")
                .build());

        // ==========================================
        // 3. 单轮：纯海运费
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-FREIGHT-001").category("SINGLE_TURN")
                .query("2,000片TOPCon580W组件从宁波港发往汉堡港，核算海运费")
                .round1PlanJson(planJson(
                        new Task[]{task("estimate_freight_cif", "destPort", "d", "originPort", "o", "quantity", "q")},
                        new SlotCandidate[]{
                                slot("d", SlotType.PORT, "汉堡", "u1"),
                                slot("o", SlotType.PORT, "宁波", "u1"),
                                slot("q", SlotType.QUANTITY, "2,000片", "u1")}))
                .expectedRound1Status("EXECUTED")
                .goldenTools(List.of("estimate_freight_cif"))
                .goldenParams(Map.of("destPort", "Hamburg", "originPort", "Ningbo", "quantity", 2000))
                .explanation("纯运费查询不得连带报价与汇率；起运港必须显式提供")
                .build());

        // ==========================================
        // 4. 单轮：CIF 报价（运费依赖报价 FOB 结果接力）
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-CIF-001").category("SINGLE_TURN")
                .query("TOPCon580W 2000片，从宁波发往鹿特丹，CIF报价")
                .round1PlanJson(planJson(
                        new Task[]{
                                task("calculate_tier_price", "productModel", "m", "quantity", "q"),
                                task("estimate_freight_cif", "destPort", "d", "originPort", "o", "quantity", "q")},
                        new SlotCandidate[]{
                                slot("m", SlotType.MODEL, "TOPCon580W", "u1"),
                                slot("q", SlotType.QUANTITY, "2000片", "u1"),
                                slot("o", SlotType.PORT, "宁波", "u1"),
                                slot("d", SlotType.PORT, "鹿特丹", "u1")}))
                .expectedRound1Status("EXECUTED")
                .goldenTools(List.of("calculate_tier_price", "estimate_freight_cif"))
                .goldenParams(Map.of("productModel", "TOPCon-580W", "quantity", 2000,
                        "destPort", "Rotterdam", "originPort", "Ningbo"))
                .explanation("CIF 请求按顺序先报价后运费，运费 FOB 由报价结果派生，执行步数恰好 2")
                .build());

        // ==========================================
        // 5. 单轮：纯汇率换算
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-FX-001").category("SINGLE_TURN")
                .query("把 125,000 美元兑换成人民币是多少")
                .round1PlanJson(planJson(
                        new Task[]{task("currency_exchange_rate", "baseCurrency", "b", "targetCurrency", "t")},
                        new SlotCandidate[]{
                                slot("b", SlotType.CURRENCY, "美元", "u1"),
                                slot("t", SlotType.CURRENCY, "人民币", "u1")}))
                .expectedRound1Status("EXECUTED")
                .goldenTools(List.of("currency_exchange_rate"))
                .goldenParams(Map.of("baseCurrency", "USD", "targetCurrency", "CNY"))
                .explanation("纯汇率查询只调汇率工具；币种必须显式方向语法（兑换成），125,000 美元是金额不是数量")
                .build());

        // ==========================================
        // 6. 单轮复合：报价 + 汇率
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-MULTI-001").category("SINGLE_TURN")
                .query("报价2,000片TOPCon580W，并把125,000美元兑换成人民币")
                .round1PlanJson(planJson(
                        new Task[]{
                                task("calculate_tier_price", "productModel", "m", "quantity", "q"),
                                task("currency_exchange_rate", "baseCurrency", "b", "targetCurrency", "t")},
                        new SlotCandidate[]{
                                slot("m", SlotType.MODEL, "TOPCon580W", "u1"),
                                slot("q", SlotType.QUANTITY, "2,000片", "u1"),
                                slot("b", SlotType.CURRENCY, "美元", "u1"),
                                slot("t", SlotType.CURRENCY, "人民币", "u1")}))
                .expectedRound1Status("EXECUTED")
                .goldenTools(List.of("calculate_tier_price", "currency_exchange_rate"))
                .goldenParams(Map.of("productModel", "TOPCon-580W", "quantity", 2000,
                        "baseCurrency", "USD", "targetCurrency", "CNY"))
                .explanation("显式请求的报价与汇率才允许同时激活；不得自动附加运费")
                .build());

        // ==========================================
        // 7. 单轮：海关背调（公司名走词典规范化）
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-CUSTOMS-001").category("SINGLE_TURN")
                .query("查询Al-Futtaim的海关提单记录")
                .round1PlanJson(planJson(
                        new Task[]{task("customs_data_lookup", "companyName", "c")},
                        new SlotCandidate[]{slot("c", SlotType.COMPANY, "Al-Futtaim", "u1")}))
                .expectedRound1Status("EXECUTED")
                .goldenTools(List.of("customs_data_lookup"))
                .goldenParams(Map.of("companyName", "Al-Futtaim Solar Energy Solutions LLC"))
                .explanation("公司别名规范化为登记全称，禁止替换为其他公司")
                .build());

        // ==========================================
        // 8. 自愈：首轮缺采购数量 -> 追问 -> 补充后 1 轮内正确执行
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-CLARIFY-QTY-001").category("SELF_CORRECTION")
                .query("请报价TOPCon580W")
                .round1PlanJson(planJson(
                        new Task[]{task("calculate_tier_price", "productModel", "m")},
                        new SlotCandidate[]{slot("m", SlotType.MODEL, "TOPCon580W", "u1")}))
                .expectedRound1Status("NEEDS_CLARIFICATION")
                .goldenTools(List.of("calculate_tier_price"))
                .goldenParams(Map.of("productModel", "TOPCon-580W", "quantity", 2000))
                .supplementQuery("采购2,000片")
                .round2PlanJson(planJson(
                        new Task[]{task("calculate_tier_price", "productModel", "m", "quantity", "q")},
                        new SlotCandidate[]{
                                slot("m", SlotType.MODEL, "TOPCon580W", "u1"),
                                slot("q", SlotType.QUANTITY, "2,000片", "u2")}))
                .explanation("缺参数绝不默认 2000；追问后补充原文证据，2 轮预算内自愈成功")
                .build());

        // ==========================================
        // 9. 自愈：首轮缺起运港 -> 追问 -> 补充后正确执行
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-CLARIFY-PORT-001").category("SELF_CORRECTION")
                .query("2,000片TOPCon580W组件从宁波发往汉堡，核算海运费")
                .round1PlanJson(planJson(
                        new Task[]{task("estimate_freight_cif", "destPort", "d", "quantity", "q")},
                        new SlotCandidate[]{
                                slot("d", SlotType.PORT, "汉堡", "u1"),
                                slot("q", SlotType.QUANTITY, "2,000片", "u1")}))
                .expectedRound1Status("NEEDS_CLARIFICATION")
                .goldenTools(List.of("estimate_freight_cif"))
                .goldenParams(Map.of("destPort", "Hamburg", "originPort", "Shanghai", "quantity", 2000))
                .supplementQuery("从上海港起运")
                .round2PlanJson(planJson(
                        new Task[]{task("estimate_freight_cif", "destPort", "d", "originPort", "o", "quantity", "q")},
                        new SlotCandidate[]{
                                slot("d", SlotType.PORT, "汉堡", "u1"),
                                slot("q", SlotType.QUANTITY, "2,000片", "u1"),
                                slot("o", SlotType.PORT, "上海", "u2")}))
                .explanation("起运港缺失时整单预检拦截，绝不套用默认港口；补充后正确执行")
                .build());

        // ==========================================
        // 10. 安全：未登记运价港口（阿布扎比）整单拦截，禁止错误执行
        // ==========================================
        list.add(AgentTrajectoryCase.builder()
                .id("TRJ-SAFETY-PORT-001").category("SAFETY_CLARIFY")
                .query("2,000片TOPCon580W从宁波发往阿布扎比，CIF报价")
                .round1PlanJson(planJson(
                        new Task[]{
                                task("calculate_tier_price", "productModel", "m", "quantity", "q"),
                                task("estimate_freight_cif", "destPort", "d", "originPort", "o", "quantity", "q")},
                        new SlotCandidate[]{
                                slot("m", SlotType.MODEL, "TOPCon580W", "u1"),
                                slot("q", SlotType.QUANTITY, "2,000片", "u1"),
                                slot("o", SlotType.PORT, "宁波", "u1"),
                                slot("d", SlotType.PORT, "阿布扎比", "u1")}))
                .expectedRound1Status("NEEDS_CLARIFICATION")
                .goldenTools(List.of())
                .goldenParams(Map.of())
                .explanation("阿布扎比无登记运价时禁止回退迪拜价格；整单预检拦截且零执行")
                .build());

        return list;
    }

    private static Task task(String tool, String... paramAndSlotId) {
        Map<String, String> slots = new LinkedHashMap<>();
        for (int i = 0; i + 1 < paramAndSlotId.length; i += 2) {
            slots.put(paramAndSlotId[i], paramAndSlotId[i + 1]);
        }
        return new Task(tool, slots);
    }

    private static SlotCandidate slot(String id, SlotType type, String evidence, String sourceMessageId) {
        return new SlotCandidate(id, type, evidence, null, null, sourceMessageId);
    }
}
