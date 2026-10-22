package com.tradeagent.modules.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.memory.entity.CrmBuyerProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task 6: 实体画像记忆服务单元测试 (只填空不覆盖与人工加锁保护)")
class EntityMemoryServiceTest {

    private EntityMemoryService entityMemoryService;
    private final String tenantId = "test_tenant";
    private final String buyerId = "buyer_hamburg_solar";

    @BeforeEach
    void setUp() {
        entityMemoryService = new EntityMemoryService(null, new ObjectMapper());
    }

    @Test
    @DisplayName("测试从非结构化文本中正确提取外贸实体候选属性")
    void testExtractCandidatesFromText() {
        String inquiry = "我们是德国汉堡的采购商，需要采购 2000 片 580W 单晶硅光伏组件，要求走 CIF 汉堡港，给我们核算一下阶梯折扣底线单价。";
        CrmBuyerProfile candidate = entityMemoryService.extractCandidatesFromText(inquiry);

        assertNotNull(candidate);
        assertEquals("汉堡", candidate.getDestinationPort());
        assertEquals("CIF", candidate.getPreferredIncoterm());
        assertTrue(candidate.getTargetProduct().contains("580W") && candidate.getTargetProduct().contains("组件"));
        assertEquals("2000片", candidate.getTargetVolume());
        assertEquals("德国", candidate.getCountry());
        assertEquals("HIGH", candidate.getPriceSensitivity());
    }

    @Test
    @DisplayName("测试初次空白画像融合：所有候选字段均成功填空")
    void testInitialProfileFill() {
        CrmBuyerProfile candidate = CrmBuyerProfile.builder()
                .destinationPort("Hamburg")
                .preferredIncoterm("CIF")
                .targetProduct("550W 单晶硅组件")
                .targetVolume("2000片")
                .country("Germany")
                .priceSensitivity("HIGH")
                .build();

        EntityMemoryService.MergeResult result = entityMemoryService.mergeExtractedProfile(
                tenantId, buyerId, candidate, "sess_init"
        );

        assertNotNull(result.profile());
        assertEquals(6, result.filledFields().size());
        assertTrue(result.skippedFields().isEmpty());

        assertEquals("Hamburg", result.profile().getDestinationPort());
        assertEquals("CIF", result.profile().getPreferredIncoterm());
        assertEquals("550W 单晶硅组件", result.profile().getTargetProduct());
    }

    @Test
    @DisplayName("测试核心原则【只填空不覆盖】：已存在港口 Hamburg 时，新提取到 Rotterdam 严禁覆盖原值")
    void testFillBlankOnlyPolicyPreservesExistingValues() {
        // 1. 初次填空，设定目的港为 Hamburg
        CrmBuyerProfile firstCandidate = CrmBuyerProfile.builder()
                .destinationPort("Hamburg")
                .preferredIncoterm("CIF")
                .build();
        entityMemoryService.mergeExtractedProfile(tenantId, buyerId, firstCandidate, "sess_1");

        // 2. 第二轮对话中提到了另一港口 Rotterdam 与新产品 550W
        CrmBuyerProfile secondCandidate = CrmBuyerProfile.builder()
                .destinationPort("Rotterdam")
                .targetProduct("550W 组件") // 这个字段之前为空
                .build();

        EntityMemoryService.MergeResult secondResult = entityMemoryService.mergeExtractedProfile(
                tenantId, buyerId, secondCandidate, "sess_2"
        );

        // 验证：destinationPort 被跳过并保持 Hamburg；原本为空的 targetProduct 被成功填空
        assertEquals("Hamburg", secondResult.profile().getDestinationPort(), "已存在港口绝对不可被 AI 提取覆盖");
        assertEquals("550W 组件", secondResult.profile().getTargetProduct(), "原本为空的字段应成功填空");
        assertTrue(secondResult.skippedFields().contains("destinationPort(ALREADY_EXISTS)"));
        assertTrue(secondResult.filledFields().contains("targetProduct"));
    }

    @Test
    @DisplayName("测试核心原则【人工加锁保护】：人工锁定 destinationPort 后，AI 提取绝对不可篡改")
    void testHumanLockPreventsAiOverwriting() {
        // 1. 人工将 destinationPort 加锁
        entityMemoryService.lockFields(tenantId, buyerId, Set.of("destinationPort"));
        CrmBuyerProfile profile = entityMemoryService.getProfile(tenantId, buyerId);
        assertTrue(entityMemoryService.isFieldLocked(profile, "destinationPort"));

        // 2. AI 提取试图写入 destinationPort = Dubai
        CrmBuyerProfile aiCandidate = CrmBuyerProfile.builder()
                .destinationPort("Dubai")
                .preferredIncoterm("FOB")
                .build();

        EntityMemoryService.MergeResult mergeResult = entityMemoryService.mergeExtractedProfile(
                tenantId, buyerId, aiCandidate, "sess_ai"
        );

        // 验证：destinationPort 处于加锁状态被跳过，preferredIncoterm 未加锁且为空则被正常填入
        assertNull(mergeResult.profile().getDestinationPort(), "加锁且为空的字段不受 AI 覆写");
        assertTrue(mergeResult.skippedFields().contains("destinationPort(LOCKED)"));
        assertEquals("FOB", mergeResult.profile().getPreferredIncoterm());
    }

    @Test
    @DisplayName("测试人工业务员手动修改与自动锁定功能")
    void testManualUpdateByHumanWithAutoLock() {
        CrmBuyerProfile manualData = CrmBuyerProfile.builder()
                .tenantId(tenantId)
                .buyerId(buyerId)
                .destinationPort("Rotterdam")
                .preferredIncoterm("CIF")
                .buyerName("Alex Muller")
                .companyName("Solar Europe BV")
                .build();

        CrmBuyerProfile updated = entityMemoryService.manualUpdateByHuman(manualData, true);

        assertEquals("Alex Muller", updated.getBuyerName());
        assertEquals("Solar Europe BV", updated.getCompanyName());
        assertEquals("Rotterdam", updated.getDestinationPort());
        assertTrue(entityMemoryService.isFieldLocked(updated, "destinationPort"));
        assertTrue(entityMemoryService.isFieldLocked(updated, "preferredIncoterm"));

        // 测试格式化为 Prompt 上下文
        String prompt = entityMemoryService.formatAsPromptContext(updated);
        assertTrue(prompt.contains("Solar Europe BV"));
        assertTrue(prompt.contains("Alex Muller"));
        assertTrue(prompt.contains("Rotterdam"));
        assertTrue(prompt.contains("[人工已核准锁定]"));
    }

    @Test
    @DisplayName("测试人工解锁流程")
    void testUnlockFields() {
        entityMemoryService.lockFields(tenantId, buyerId, Set.of("country", "destinationPort"));
        CrmBuyerProfile profile = entityMemoryService.getProfile(tenantId, buyerId);
        assertTrue(entityMemoryService.isFieldLocked(profile, "country"));
        assertTrue(entityMemoryService.isFieldLocked(profile, "destinationPort"));

        entityMemoryService.unlockFields(tenantId, buyerId, Set.of("country"));
        CrmBuyerProfile after = entityMemoryService.getProfile(tenantId, buyerId);
        assertFalse(entityMemoryService.isFieldLocked(after, "country"));
        assertTrue(entityMemoryService.isFieldLocked(after, "destinationPort"));
    }
}
