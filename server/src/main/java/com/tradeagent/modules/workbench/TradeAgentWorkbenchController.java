package com.tradeagent.modules.workbench;

import com.tradeagent.core.common.ApiResponse;
import com.tradeagent.core.conversation.ConversationContextManager;
import com.tradeagent.core.conversation.CustomerProfile;
import com.tradeagent.core.model.DynamicModelFactory;
import com.tradeagent.core.model.ModelConfig;
import com.tradeagent.core.planner.PlanAndExecuteOrchestrator;
import com.tradeagent.modules.ai.market.MarketAnalysisReportGenerator;
import com.tradeagent.modules.feishu.FeishuBitableService;
import com.tradeagent.modules.feishu.FeishuDocsSyncService;
import com.tradeagent.modules.outreach.CampaignSagaOrchestrator;
import com.tradeagent.modules.outreach.CampaignStatus;
import com.tradeagent.modules.prospect.NaturalLanguageTaskParser;
import com.tradeagent.modules.prospect.ProspectCollectorRegistry;
import com.tradeagent.modules.prospect.ProspectLead;
import com.tradeagent.modules.prospect.ProspectTaskParam;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * B2B 外贸智能体统一工作台与测试控制中心 (Trade Agent Workbench Controller)
 * 
 * 集中暴露 9 大核心 Agent 架构能力，供 Vue3 前端看板与第三方平台调用：
 * 1. 动态模型配置热切换与缓存驱逐 (Dynamic Model Switch)
 * 2. 客户全景画像实时查询 (Customer Profile Query)
 * 3. 自然语言多维意图与约束解析 (NL Task Parsing)
 * 4. SPI 动态拓客多通道采集 (Multi-Channel Prospect Collection)
 * 5. 营销触达分布式 Saga 编排与配额门禁 (Campaign Saga Outreach)
 * 6. N+4 并发市场研报生成与证据白名单校验 (Market Report Generation)
 * 7. Plan-and-Execute 深度长文创作编排 (Article Planner)
 * 8. 飞书多维表格 Bitable 变更 Webhook 异步回写 (Feishu Bitable Webhook)
 * 9. 飞书云文档知识库同步与 AST 切片 (Feishu Docs Sync)
 */
@Slf4j
@RestController
@RequestMapping("/api/trade")
@CrossOrigin(origins = "*")
public class TradeAgentWorkbenchController {

    @Resource
    private DynamicModelFactory modelFactory;

    @Resource
    private ConversationContextManager contextManager;

    @Resource
    private NaturalLanguageTaskParser taskParser;

    @Resource
    private ProspectCollectorRegistry collectorRegistry;

    @Resource
    private CampaignSagaOrchestrator sagaOrchestrator;

    @Resource
    private MarketAnalysisReportGenerator reportGenerator;

    @Resource
    private PlanAndExecuteOrchestrator articleOrchestrator;

    @Resource
    private FeishuBitableService feishuBitableService;

    @Resource
    private FeishuDocsSyncService feishuDocsSyncService;

    /**
     * 1. 动态模型切换与主动热加载
     */
    @PostMapping("/model/switch")
    public ApiResponse<String> switchModel(@RequestParam("tenantId") Long tenantId,
                                          @RequestBody ModelConfig config) {
        log.info("【工作台】租户 {} 申请动态切换模型: {}/{}", tenantId, config.getProvider(), config.getModelName());
        modelFactory.evictTenantModel(tenantId);
        boolean probeSuccess = modelFactory.warmUpModel(tenantId, config);
        if (probeSuccess) {
            return ApiResponse.success("模型切换并预热探活成功: " + config.getModelName());
        } else {
            return ApiResponse.error(500, "模型已注册但探活失败，请检查 BaseUrl 与 API-Key");
        }
    }

    /**
     * 2. 查询当前会话的 B2B 客户全景画像
     */
    @GetMapping("/profile/{conversationId}")
    public ApiResponse<CustomerProfile> getCustomerProfile(@PathVariable("conversationId") Long conversationId) {
        CustomerProfile profile = contextManager.getProfile(conversationId);
        return ApiResponse.success(profile);
    }

    /**
     * 3. 自然语言拓客任务 4 级编译解析
     */
    @PostMapping("/prospect/parse")
    public ApiResponse<ProspectTaskParam> parseProspectTask(
            @RequestParam(value = "tenantId", defaultValue = "1001") Long tenantId,
            @RequestBody Map<String, String> payload) {
        String prompt = payload.getOrDefault("prompt", "");
        org.springframework.ai.chat.model.ChatModel model = modelFactory.getModel(tenantId);
        ProspectTaskParam param = taskParser.parseInstruction(prompt, model);
        return ApiResponse.success(param);
    }

    /**
     * 4. 执行 SPI 拓客通道采集
     */
    @PostMapping("/prospect/collect")
    public ApiResponse<List<ProspectLead>> collectProspects(
            @RequestParam("channel") String channel,
            @RequestBody ProspectTaskParam param) {
        com.tradeagent.modules.prospect.ProspectCollectorProvider provider = collectorRegistry.getProvider(channel);
        if (provider != null) {
            List<ProspectLead> leads = provider.collect(param);
            return ApiResponse.success(leads);
        }
        return ApiResponse.success(java.util.Collections.emptyList());
    }

    /**
     * 5. 执行营销触达 Saga 编排（Semaphore 3 门禁与违禁词过滤）
     */
    @PostMapping("/campaign/execute")
    public ApiResponse<CampaignStatus> executeCampaign(
            @RequestParam(value = "campaignId", defaultValue = "1001") Long campaignId,
            @RequestParam("tenantId") Long tenantId,
            @RequestParam(value = "emailContent", defaultValue = "Hello, welcome to our products.") String emailContent,
            @RequestParam(value = "requiredQuota", defaultValue = "10") int requiredQuota) {
        CampaignStatus status = sagaOrchestrator.executeCampaign(campaignId, tenantId, emailContent, requiredQuota);
        return ApiResponse.success(status);
    }

    /**
     * 6. N+4 并发市场研报生成（证据白名单校验与段落隔离）
     */
    @PostMapping("/report/generate")
    public ApiResponse<Map<String, String>> generateMarketReport(
            @RequestParam("tenantId") Long tenantId,
            @RequestBody MarketReportRequest request) {
        org.springframework.ai.chat.model.ChatModel model = modelFactory.getModel(tenantId);
        Map<String, String> report = reportGenerator.generateNPlus4Report(
                request.getTargetCountry(),
                request.getTargetProduct(),
                request.getEvidences(),
                model
        );
        return ApiResponse.success(report);
    }

    /**
     * 7. Plan-and-Execute 深度长文创作
     */
    @PostMapping("/article/generate")
    public ApiResponse<String> generateArticle(
            @RequestParam(value = "tenantId", defaultValue = "1001") Long tenantId,
            @RequestBody ArticleGenerateRequest request) {
        org.springframework.ai.chat.model.ChatModel model = modelFactory.getModel(tenantId);
        String taskId = java.util.UUID.randomUUID().toString();
        String article = articleOrchestrator.executeLongArticlePlan(taskId, request.getTopic(), model, model);
        return ApiResponse.success(article);
    }

    /**
     * 8. 飞书多维表格 (Bitable) Webhook 变更处理
     */
    @PostMapping("/feishu/bitable/webhook")
    public ApiResponse<String> handleFeishuBitableWebhook(
            @RequestParam(value = "tenantId", defaultValue = "1001") Long tenantId,
            @RequestBody FeishuBitableWebhookRequest req) {
        org.springframework.ai.chat.model.ChatModel model = modelFactory.getModel(tenantId);
        Map<String, Object> payload = Map.of(
                "record_id", req.getRecordId() != null ? req.getRecordId() : "rec_mock_123",
                "company_name", req.getCompanyName() != null ? req.getCompanyName() : "Al-Futtaim Solar Energy",
                "website", req.getWebsite() != null ? req.getWebsite() : "https://al-futtaim-solar.ae"
        );
        feishuBitableService.handleBitableRecordEvent(payload, model);
        return ApiResponse.success("飞书多维表格 Webhook 已接收，异步背调与回写任务已触发");
    }

    /**
     * 9. 飞书云文档知识库同步
     */
    @PostMapping("/feishu/docs/sync")
    public ApiResponse<String> syncFeishuDocs(
            @RequestParam("documentToken") String documentToken,
            @RequestParam(value = "documentId", defaultValue = "1001") Long documentId) {
        int count = feishuDocsSyncService.syncFeishuDocumentToRag(documentToken, documentId);
        return ApiResponse.success("飞书文档同步并切片成功，生成 AST 切片数: " + count);
    }

    // --- DTO 请求对象 ---

    @Data
    public static class MarketReportRequest {
        private String targetCountry;
        private String targetProduct;
        private Map<String, String> evidences;
    }

    @Data
    public static class ArticleGenerateRequest {
        private String topic;
        private String targetAudience;
        private String coreProduct;
    }

    @Data
    public static class FeishuBitableWebhookRequest {
        private String recordId;
        private String companyName;
        private String website;
    }
}
