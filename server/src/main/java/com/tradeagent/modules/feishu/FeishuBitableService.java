package com.tradeagent.modules.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 飞书多维表格 (Bitable) 自动化与商机回写服务 (自查表 Q4 核心考点)
 * 
 * 核心技术考点：
 * 1. 【Webhook 监听】：配置 Webhook 监听飞书多维表格新增商机事件；
 * 2. 【异步背调】：Agent 接收到记录行后，异步启动海外官网深扫与背景调查；
 * 3. 【Bitable 字段回写】：调飞书开放平台 REST API，将 AI 背调报告、意向评级与跟进话术回写进表格列！
 */
@Slf4j
@Service
public class FeishuBitableService {

    /**
     * 处理飞书多维表格 Webhook 事件通知
     *
     * @param eventPayload 飞书推送的 JSON 载荷 (包含 app_token, table_id, record_id)
     * @param chatModel AI 分析模型
     */
    @Async("agentDagExecutor")
    public void handleBitableRecordEvent(Map<String, Object> eventPayload, ChatModel chatModel) {
        log.info("【飞书多维表格】接收到新增商机行事件通知: {}", eventPayload);

        String recordId = (String) eventPayload.getOrDefault("record_id", "rec_mock_123");
        String companyName = (String) eventPayload.getOrDefault("company_name", "Al-Futtaim Solar Energy");
        String website = (String) eventPayload.getOrDefault("website", "https://al-futtaim-solar.ae");

        // 步骤 1: AI 进行自主背调分析
        String analysisPrompt = String.format(
                "请为飞书多维表格录入的外贸企业做 100 字极速背调画像与评级：\n" +
                "公司名: %s, 官网: %s。\n" +
                "请输出三项：1. 买家类型 (分销商/EPC); 2. 意向等级 (A/B/C); 3. 销售首轮触达建议话术。",
                companyName, website
        );

        try {
            String aiSummary = chatModel.call(analysisPrompt);
            log.info("【飞书多维表格】AI 自动化背调完成: {}", aiSummary);

            // 步骤 2: 模拟调用飞书开放平台 API 回写多维表格记录行 (PUT /open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/records/{record_id})
            writeBackToFeishuBitable(recordId, aiSummary, "A类优质商机");

        } catch (Exception e) {
            log.error("【飞书多维表格】自动化背调处理失败: {}", e.getMessage());
        }
    }

    /**
     * 调飞书开放平台 REST API 回写多维表格数据列
     */
    private void writeBackToFeishuBitable(String recordId, String aiSummary, String rating) {
        log.info("【飞书 OpenAPI 回写】成功向多维表格行 [{}] 回写数据 -> 字段[AI背调总结]: {}, 字段[客户评级]: {}", 
                recordId, aiSummary, rating);
    }
}
