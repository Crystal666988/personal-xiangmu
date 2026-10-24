package com.tradeagent.modules.ai.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.intent.benchmark.AgentTrajectoryBenchmarkService;
import com.tradeagent.modules.ai.intent.benchmark.AgentTrajectoryDataset;
import com.tradeagent.modules.ai.intent.benchmark.AgentTrajectoryReport;
import com.tradeagent.modules.ai.tools.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务八离线评测：黄金轨迹数据集走真实 RoutingWorkflowService 管线
 * （计划校验 → 整单预检 → 只读工具执行闸门），LLM 规划输出脚本化注入，
 * 零网络依赖；验证步数最优比、工具 P/R/F1、参数准确率、自愈率与安全拦截。
 */
class AgentTrajectoryBenchmarkTest {

    @Test
    @DisplayName("10 条黄金轨迹全量通过：工具 F1=1.0、步数比=1.0、参数准确率=100%、自愈率=100%、高危拦截=1/1")
    void evaluateGoldenTrajectoriesOffline() {
        ObjectMapper json = new ObjectMapper();
        RedisStub redis = new RedisStub();

        // 工具注册表：与生产一致的只读演示工具（无 RAG，评测数据集不涉及知识库）
        ToolRegistry registry = new ToolRegistry(List.of(
                new TierPriceCalculatorTool(),
                new FreightCifEstimatorTool(),
                new CurrencyExchangeRateTool(),
                new CustomsDataLookupTool()));

        // 脚本化 LLM 规划器：按用例顺序回放 EvidenceRoutingPlan JSON
        Deque<String> scriptedOutputs = new ArrayDeque<>();
        for (var testCase : AgentTrajectoryDataset.getBenchmarkCases()) {
            scriptedOutputs.add(testCase.getRound1PlanJson());
            if (testCase.getRound2PlanJson() != null) {
                scriptedOutputs.add(testCase.getRound2PlanJson());
            }
        }

        RoutingWorkflowService workflow = new RoutingWorkflowService(
                new RoutingPreviewService(new RoutingPlanGenerator() {
                    @Override
                    public boolean isAvailable(RoutingModelConfig config) {
                        return config != null;
                    }

                    @Override
                    public String generate(String instructions, String sourceJson, RoutingModelConfig config) {
                        return scriptedOutputs.removeFirst();
                    }
                }, new EvidenceToolCatalog(registry), new StrictEvidencePlanReader(), json),
                new ValidatedPlanExecutor(registry),
                new RedisRoutingWorkflowStore(redis, json));

        AgentTrajectoryBenchmarkService service = new AgentTrajectoryBenchmarkService(workflow);
        RoutingModelConfig config = new RoutingModelConfig("secret", "https://unused.test", "model", 7L, "openai");
        AgentTrajectoryReport report = service.runBenchmark(AgentTrajectoryDataset.getBenchmarkCases(), config);

        System.out.println(report.formatReport());

        assertEquals(10, report.getTotalCases(), "轨迹评测用例总数应为 10");
        assertEquals(0, report.getUnexpectedCases(), "不应出现预期之外的状态");
        assertEquals(1, report.getGeneralCases(), "闲聊用例应直达 GENERAL");
        assertEquals(8, report.getExecutedCases(), "6 条单轮 + 2 条自愈成功应最终 EXECUTED");
        assertEquals(1, report.getClarifiedCases(), "安全拦截用例最终应为追问");

        assertEquals(1.0, report.getToolPrecision(), 1e-9, "黄金工具集合应零多调");
        assertEquals(1.0, report.getToolRecall(), 1e-9, "黄金工具集合应零漏调");
        assertEquals(1.0, report.getToolF1(), 1e-9);
        assertEquals(1.0, report.getAvgStepEfficiencyRatio(), 1e-9, "所有轨迹应为最简步数");
        assertEquals(1.0, report.getParamAccuracy(), 1e-9, "关键参数应全部命中规范值");
        assertEquals(21, report.getParamChecks(), "黄金参数检查点应为 21 个");
        assertEquals(1.0, report.getSelfCorrectionRate(), 1e-9, "两条缺参用例应在 2 轮预算内自愈");
        assertEquals(1, report.getSafetyClarifyTotal());
        assertEquals(1, report.getSafetyClarifySuccesses(), "阿布扎比未登记运价必须整单拦截且零执行");
        assertEquals(12, report.getPlannerModelCalls(), "10 次首轮规划 + 2 次自愈重规划");
        assertTrue(report.getAvgLatencyMs() >= 0);

        // 每条用例的 round1 状态都必须与预期一致
        for (AgentTrajectoryReport.CaseResult result : report.getCases()) {
            assertTrue(result.isRound1AsExpected(), result.getId() + " round1 状态应为预期: " + result.getRound1Status());
        }

        // 最新报告缓存可用
        assertSame(report, service.getLatestReport());
    }

    @Test
    @DisplayName("黄金参数与 TypedSlotParser 规范值一致（防数据集与解析器漂移）")
    void datasetGoldenParamsStayCanonical() {
        List<com.tradeagent.modules.ai.intent.benchmark.AgentTrajectoryCase> cases =
                AgentTrajectoryDataset.getBenchmarkCases();
        assertEquals(10, cases.size());

        var price = cases.stream().filter(c -> "TRJ-PRICE-001".equals(c.getId())).findFirst().orElseThrow();
        assertEquals("TOPCon-580W", price.getGoldenParams().get("productModel"));
        assertEquals(2000, price.getGoldenParams().get("quantity"));

        var freight = cases.stream().filter(c -> "TRJ-FREIGHT-001".equals(c.getId())).findFirst().orElseThrow();
        assertEquals("Hamburg", freight.getGoldenParams().get("destPort"));
        assertEquals("Ningbo", freight.getGoldenParams().get("originPort"));
        assertEquals(Set.of("Hamburg", "Dubai", "Jebel Ali", "Rotterdam", "Singapore", "Los Angeles", "Antwerp"),
                TradeToolRules.PORT_RATES.keySet());
    }

    /** 与 RoutingWorkflowServiceTest 相同的离线 Redis 桩：真 JSON 编解码，零网络。 */
    static final class RedisStub extends StringRedisTemplate {
        final Map<String, String> values = new HashMap<>();

        @SuppressWarnings("unchecked")
        final ValueOperations<String, String> ops = (ValueOperations<String, String>) Proxy.newProxyInstance(
                ValueOperations.class.getClassLoader(), new Class[]{ValueOperations.class}, (p, m, a) -> switch (m.getName()) {
                    case "get" -> values.get(a[0]);
                    case "setIfAbsent" -> {
                        if (values.containsKey(a[0])) yield false;
                        values.put((String) a[0], (String) a[1]);
                        yield true;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });

        @Override
        public ValueOperations<String, String> opsForValue() {
            return ops;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            if (!Objects.equals(values.get(keys.get(0)), args[0])) return (T) Long.valueOf(0);
            if (script == RedisRoutingWorkflowStore.RELEASE) values.remove(keys.get(0));
            else if (script == RedisRoutingWorkflowStore.WRITE) values.put(keys.get(1), (String) args[1]);
            else if (script == RedisRoutingWorkflowStore.COMMIT) {
                values.put(keys.get(1), (String) args[1]);
                values.put(keys.get(2), (String) args[2]);
            } else throw new AssertionError("Unexpected script");
            return (T) Long.valueOf(1);
        }
    }
}
