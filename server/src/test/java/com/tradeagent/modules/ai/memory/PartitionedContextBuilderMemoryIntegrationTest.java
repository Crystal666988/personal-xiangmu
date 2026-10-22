package com.tradeagent.modules.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.chat.PartitionedContextBuilder;
import com.tradeagent.modules.ai.memory.entity.CrmBuyerProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task 6: 三层上下文隔离与复合记忆集成测试")
class PartitionedContextBuilderMemoryIntegrationTest {

    private PartitionedContextBuilder builder;
    private EntityMemoryService entityMemoryService;
    private EpisodicMemoryService episodicMemoryService;

    @BeforeEach
    void setUp() {
        ObjectMapper json = new ObjectMapper();
        builder = new PartitionedContextBuilder(json);
        entityMemoryService = new EntityMemoryService(null, json);
        episodicMemoryService = new EpisodicMemoryService(null, json);
    }

    @Test
    @DisplayName("测试将 Episodic Few-Shot 融入 ZONE 1，并将 Entity Profile 融入 ZONE 2")
    void testMemoryInjectionIntoZones() {
        // 1. 模拟买家画像 (Entity Memory)
        CrmBuyerProfile buyer = CrmBuyerProfile.builder()
                .buyerName("Hans Schmidt")
                .companyName("Hamburg Solar Tech GmbH")
                .country("Germany")
                .destinationPort("Hamburg")
                .preferredIncoterm("CIF")
                .targetProduct("580W 单晶硅组件")
                .lockedFieldsJson("[\"destinationPort\"]")
                .build();
        String entityContext = entityMemoryService.formatAsPromptContext(buyer);

        // 2. 模拟历史成交情景少样本 (Episodic Memory)
        episodicMemoryService.recordSuccessfulCase(
                "1001",
                "sess_hist",
                "采购 3000 片 580W 组件发汉堡 CIF",
                "阶梯单价 0.112 USD/W，汉堡 CIF 运费 2200 USD",
                List.of("calculate_tier_price", "estimate_freight_cif"),
                "DEAL_CLOSED",
                194880.0,
                Map.of()
        );
        var hits = episodicMemoryService.recallSimilarCases("1001", "采购 3000 片 580W 组件发汉堡 CIF", 1, 0.50);
        String episodicFewShot = episodicMemoryService.formatAsFewShotPrompt(hits);

        // 3. 构建隔离上下文
        PartitionedContextBuilder.PartitionedPrompt prompt = builder.build(
                "你是一名外贸商务专家，严格执行合规报价纪律。",
                "请为我采购 2000 片 580W 组件报 CIF 价格",
                List.of(),
                "",
                List.of(),
                null,
                Map.of(),
                episodicFewShot,
                entityContext
        );

        assertNotNull(prompt);
        // 验证 ZONE 1: 包含系统提示词 + 历史高分案例 Few-Shot
        assertTrue(prompt.zone1SystemPrompt().contains("你是一名外贸商务专家"));
        assertTrue(prompt.zone1SystemPrompt().contains("【历史高分成交案例库 (Few-Shot Exemplars - 规划与工具链参考)】"));
        assertTrue(prompt.zone1SystemPrompt().contains("calculate_tier_price -> estimate_freight_cif"));

        // 验证 ZONE 2: 包含用户不可篡改诉求 + 买家实体画像记忆
        assertTrue(prompt.zone2ImmutableGoal().contains("请为我采购 2000 片 580W 组件报 CIF 价格"));
        assertTrue(prompt.zone2ImmutableGoal().contains("【买家实体画像持久记忆 (Entity Memory - 跨会话一致认知)】"));
        assertTrue(prompt.zone2ImmutableGoal().contains("Hamburg Solar Tech GmbH"));
        assertTrue(prompt.zone2ImmutableGoal().contains("习惯指定目的港: Hamburg [人工已核准锁定]"));

        // 验证全景视图 toFullView 结构
        String fullView = prompt.toFullView();
        assertTrue(fullView.contains("[ZONE 1: SYSTEM_PROMPT]"));
        assertTrue(fullView.contains("[ZONE 2: USER_IMMUTABLE_GOAL]"));
        assertTrue(fullView.contains("[ZONE 3: SCRATCHPAD_WORKING]"));
    }
}
