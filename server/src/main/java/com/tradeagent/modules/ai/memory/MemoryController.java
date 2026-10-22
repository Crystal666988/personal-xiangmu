package com.tradeagent.modules.ai.memory;

import com.tradeagent.core.common.ApiResponse;
import com.tradeagent.modules.ai.memory.entity.CrmBuyerProfile;
import com.tradeagent.modules.ai.memory.episodic.EpisodicCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复合记忆体系 REST 控制器 (Memory Controller)
 *
 * 对标 Carl (youngyangyang04/llm-master) 记忆分层体系：
 * 1. 实体画像记忆 (Entity Memory)：只填空不覆盖、人工加锁保护；
 * 2. 情景向量记忆 (Episodic Memory)：高分成交案例沉淀、Top-K 余弦相似度召回与少样本 Prompt 生成。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/memory")
@CrossOrigin(origins = "*")
@Tag(name = "AI 复合记忆体系管理", description = "提供买家画像实体记忆(只填空不覆盖+加锁)与高分成交情景向量记忆")
public class MemoryController {

    private final EntityMemoryService entityMemoryService;
    private final EpisodicMemoryService episodicMemoryService;

    @Autowired
    public MemoryController(EntityMemoryService entityMemoryService,
                            EpisodicMemoryService episodicMemoryService) {
        this.entityMemoryService = entityMemoryService;
        this.episodicMemoryService = episodicMemoryService;
    }

    // ==================== Entity Memory (实体画像记忆) ====================

    @GetMapping("/entity/{buyerId}")
    @Operation(summary = "查询买家实体画像记忆")
    public ApiResponse<CrmBuyerProfile> getBuyerProfile(
            @RequestHeader(value = "tenant-id", defaultValue = "1001") String tenantId,
            @PathVariable("buyerId") String buyerId
    ) {
        CrmBuyerProfile profile = entityMemoryService.getProfile(tenantId, buyerId);
        return ApiResponse.success(profile);
    }

    @Data
    public static class ExtractAndMergeRequest {
        private String text;
        private String sessionId;
    }

    @PostMapping("/entity/{buyerId}/extract-and-merge")
    @Operation(summary = "从对话文本提取实体并按「只填空不覆盖」与「加锁保护」合并画像")
    public ApiResponse<EntityMemoryService.MergeResult> extractAndMerge(
            @RequestHeader(value = "tenant-id", defaultValue = "1001") String tenantId,
            @PathVariable("buyerId") String buyerId,
            @RequestBody ExtractAndMergeRequest request
    ) {
        CrmBuyerProfile candidate = entityMemoryService.extractCandidatesFromText(request.getText());
        EntityMemoryService.MergeResult result = entityMemoryService.mergeExtractedProfile(
                tenantId, buyerId, candidate, request.getSessionId()
        );
        return ApiResponse.success(result);
    }

    @Data
    public static class LockFieldsRequest {
        private Set<String> fieldNames;
    }

    @PostMapping("/entity/{buyerId}/lock")
    @Operation(summary = "人工核准并为买家画像特定字段加锁（加锁后 AI 绝对不可修改）")
    public ApiResponse<CrmBuyerProfile> lockFields(
            @RequestHeader(value = "tenant-id", defaultValue = "1001") String tenantId,
            @PathVariable("buyerId") String buyerId,
            @RequestBody LockFieldsRequest request
    ) {
        CrmBuyerProfile updated = entityMemoryService.lockFields(tenantId, buyerId, request.getFieldNames());
        return ApiResponse.success(updated);
    }

    @PostMapping("/entity/{buyerId}/unlock")
    @Operation(summary = "解除买家画像特定字段的锁定状态")
    public ApiResponse<CrmBuyerProfile> unlockFields(
            @RequestHeader(value = "tenant-id", defaultValue = "1001") String tenantId,
            @PathVariable("buyerId") String buyerId,
            @RequestBody LockFieldsRequest request
    ) {
        CrmBuyerProfile updated = entityMemoryService.unlockFields(tenantId, buyerId, request.getFieldNames());
        return ApiResponse.success(updated);
    }

    @PostMapping("/entity/manual-update")
    @Operation(summary = "业务员手动修改买家画像（可选择是否自动加锁被编辑字段）")
    public ApiResponse<CrmBuyerProfile> manualUpdate(
            @RequestBody CrmBuyerProfile profile,
            @RequestParam(value = "autoLock", defaultValue = "true") boolean autoLock
    ) {
        CrmBuyerProfile updated = entityMemoryService.manualUpdateByHuman(profile, autoLock);
        return ApiResponse.success(updated);
    }

    // ==================== Episodic Memory (情景向量记忆) ====================

    @Data
    public static class RecordEpisodeRequest {
        private String sessionId;
        private String userInquiry;
        private String solutionSummary;
        private List<String> toolChain;
        private String rating; // DEAL_CLOSED, UPVOTE
        private Double dealAmount;
        private Map<String, Object> metadata;
    }

    @PostMapping("/episodic/record")
    @Operation(summary = "沉淀高价值成功交易或好评案例到情景向量记忆库")
    public ApiResponse<EpisodicCase> recordEpisode(
            @RequestHeader(value = "tenant-id", defaultValue = "1001") String tenantId,
            @RequestBody RecordEpisodeRequest request
    ) {
        EpisodicCase episodicCase = episodicMemoryService.recordSuccessfulCase(
                tenantId,
                request.getSessionId(),
                request.getUserInquiry(),
                request.getSolutionSummary(),
                request.getToolChain(),
                request.getRating(),
                request.getDealAmount(),
                request.getMetadata()
        );
        return ApiResponse.success(episodicCase);
    }

    @GetMapping("/episodic/recall")
    @Operation(summary = "根据新询盘语义检索最贴近的历史高分成交案例 (Top-K)")
    public ApiResponse<List<EpisodicMemoryService.EpisodicCaseHit>> recallEpisodes(
            @RequestHeader(value = "tenant-id", defaultValue = "1001") String tenantId,
            @RequestParam("query") String query,
            @RequestParam(value = "topK", defaultValue = "3") int topK,
            @RequestParam(value = "minSimilarity", defaultValue = "0.60") double minSimilarity
    ) {
        List<EpisodicMemoryService.EpisodicCaseHit> hits = episodicMemoryService.recallSimilarCases(
                tenantId, query, topK, minSimilarity
        );
        return ApiResponse.success(hits);
    }

    @GetMapping("/episodic/few-shot-prompt")
    @Operation(summary = "检索并直接生成可注入 Prompt 的少样本案例示范 Markdown 块")
    public ApiResponse<String> getFewShotPrompt(
            @RequestHeader(value = "tenant-id", defaultValue = "1001") String tenantId,
            @RequestParam("query") String query,
            @RequestParam(value = "topK", defaultValue = "3") int topK
    ) {
        List<EpisodicMemoryService.EpisodicCaseHit> hits = episodicMemoryService.recallSimilarCases(
                tenantId, query, topK, 0.50
        );
        String prompt = episodicMemoryService.formatAsFewShotPrompt(hits);
        return ApiResponse.success(prompt);
    }
}
