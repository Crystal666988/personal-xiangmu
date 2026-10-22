package com.tradeagent.modules.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.memory.episodic.EpisodicCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task 6: 情景向量记忆服务单元测试 (Episodic Memory & Few-Shot Recall)")
class EpisodicMemoryServiceTest {

    private EpisodicMemoryService episodicMemoryService;
    private final String tenantId = "test_tenant";

    @BeforeEach
    void setUp() {
        episodicMemoryService = new EpisodicMemoryService(null, new ObjectMapper());
        episodicMemoryService.clearInMemoryCases();
    }

    @Test
    @DisplayName("测试沉淀高分成交案例到情景记忆库")
    void testRecordSuccessfulCase() {
        EpisodicCase recorded = episodicMemoryService.recordSuccessfulCase(
                tenantId,
                "sess_rotterdam_101",
                "采购 2000 片 550W 单晶硅光伏组件报 CIF 鹿特丹海运费",
                "阶梯单价 0.115 USD/W；40HQ 海运费 2150 美元；生成正式 PI",
                List.of("calculate_tier_price", "estimate_freight_cif", "generate_pi_invoice"),
                "DEAL_CLOSED",
                126500.0,
                Map.of("port", "Rotterdam", "container", "40HQ")
        );

        assertNotNull(recorded);
        assertNotNull(recorded.getCaseId());
        assertEquals(tenantId, recorded.getTenantId());
        assertEquals("DEAL_CLOSED", recorded.getRating());
        assertEquals(3, recorded.getToolChain().size());
        assertEquals(EpisodicMemoryService.EMBEDDING_DIMENSION, recorded.getEmbedding().length);
        assertEquals(1, episodicMemoryService.getInMemoryCaseCount());
    }

    @Test
    @DisplayName("测试根据新询盘进行语义相似度检索 (Top-K Recall)")
    void testRecallSimilarCases() {
        // 案例 1: 550W 组件 鹿特丹 CIF
        episodicMemoryService.recordSuccessfulCase(
                tenantId,
                "sess_1",
                "采购 2000 片 550W 单晶硅光伏组件报 CIF 鹿特丹海运费",
                "单价 0.115 USD/W，40HQ 运费 2150 美元",
                List.of("calculate_tier_price", "estimate_freight_cif"),
                "DEAL_CLOSED",
                126500.0,
                Map.of("category", "solar_panel")
        );

        // 案例 2: 逆变器 迪拜 FOB
        episodicMemoryService.recordSuccessfulCase(
                tenantId,
                "sess_2",
                "采购 50 台三相并网逆变器 100kW FOB 宁波",
                "逆变器单台 3200 美元，FOB 出口报关",
                List.of("calculate_tier_price", "customs_data_lookup"),
                "UPVOTE",
                160000.0,
                Map.of("category", "inverter")
        );

        // 新客户询盘：非常接近案例 1
        String newInquiry = "采购 2000 片 550W 单晶硅光伏组件报 CIF 鹿特丹海运费";
        List<EpisodicMemoryService.EpisodicCaseHit> hits = episodicMemoryService.recallSimilarCases(
                tenantId, newInquiry, 2, 0.50
        );

        assertFalse(hits.isEmpty(), "应至少召回相关案例");
        EpisodicMemoryService.EpisodicCaseHit topHit = hits.get(0);
        assertTrue(topHit.getSimilarity() > 0.90, "完全相同的语义应具备极高的余弦相似度");
        assertEquals("DEAL_CLOSED", topHit.getEpisodicCase().getRating());
        assertTrue(topHit.getEpisodicCase().getToolChain().contains("estimate_freight_cif"));
    }

    @Test
    @DisplayName("测试生成 Carl 标准 Few-Shot 提示词 Markdown 块")
    void testFormatAsFewShotPrompt() {
        episodicMemoryService.recordSuccessfulCase(
                tenantId,
                "sess_1",
                "5000 片 580W 组件发往汉堡 CIF",
                "阶梯价格核算 + 汉堡 40HQ 订舱",
                List.of("calculate_tier_price", "estimate_freight_cif"),
                "DEAL_CLOSED",
                290000.0,
                Map.of()
        );

        List<EpisodicMemoryService.EpisodicCaseHit> hits = episodicMemoryService.recallSimilarCases(
                tenantId, "5000 片 580W 组件发往汉堡 CIF", 1, 0.50
        );

        String fewShotBlock = episodicMemoryService.formatAsFewShotPrompt(hits);
        assertNotNull(fewShotBlock);
        assertTrue(fewShotBlock.contains("【历史高分成交案例库 (Few-Shot Exemplars - 规划与工具链参考)】"));
        assertTrue(fewShotBlock.contains("5000 片 580W 组件发往汉堡 CIF"));
        assertTrue(fewShotBlock.contains("calculate_tier_price -> estimate_freight_cif"));
        assertTrue(fewShotBlock.contains("$290,000.00"));
    }

    @Test
    @DisplayName("测试余弦相似度数学计算与归一化")
    void testCosineSimilarityMath() {
        float[] v1 = EpisodicMemoryService.generateDeterministicVector("测试太阳能光伏组件");
        float[] v2 = EpisodicMemoryService.generateDeterministicVector("测试太阳能光伏组件");
        float[] v3 = EpisodicMemoryService.generateDeterministicVector("完全不相关的红酒西餐厅牛排");

        double simIdentical = EpisodicMemoryService.computeCosineSimilarity(v1, v2);
        double simDiff = EpisodicMemoryService.computeCosineSimilarity(v1, v3);

        assertEquals(1.0, simIdentical, 0.0001, "相同向量余弦相似度应为 1.0");
        assertTrue(simDiff < simIdentical, "不同向量余弦相似度必须显著小于相同向量");
    }
}
