package com.tradeagent.core.conversation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨模型会话上下文交接管理器 (Context Handover Manager)
 * 
 * 核心技术机制：
 * 1. 【格式协议解耦】：无论底层切到 DeepSeek 还是豆包，历史消息一律收敛为标准 List<Message> 协议；
 * 2. 【事实锚定置顶】：将提炼后的客户画像 (CustomerProfile) 作为固定前缀注入，防止新模型丢失谈判关键信息；
 * 3. 【滑动窗口角色重放】：将前一个模型的历史回复转化为标准 AssistantMessage，使新模型天然继承前序记忆；
 * 4. 【身份全平台统一锁定】：统一下发企业销售代表角色设定，确保切换模型后人设与语气不发生突变。
 */
@Slf4j
@Component
public class ConversationContextManager {

    /**
     * 默认滑动窗口大小 (保留最近 10 条消息，平衡上下文连贯性与 Token 成本)
     */
    private static final int DEFAULT_WINDOW_SIZE = 10;

    /**
     * 基础系统销售人设提示词
     */
    private static final String BASE_SYSTEM_PROMPT = 
            "你是一名资深的外贸 B2B 销售与商务代表，代表中国高端制造业进行海外客商接待。\n" +
            "沟通原则：\n" +
            "1. 商务专业、语气自信热情、符合国际商贸礼仪；\n" +
            "2. 基于已确认的客户档案信息进行精准回应，禁止捏造未核实的数据或虚假交付周期；\n" +
            "3. 如涉及具体价格与规格，以系统检索到的确认资料为准。\n";

    /**
     * 为新模型构建平滑交接后的完整 Prompt
     *
     * @param conversationId 会话ID
     * @param profile 客户画像 (事实档案)
     * @param rawHistory 历史原始消息列表
     * @param currentUserInput 客户当前输入的新消息
     * @return 组装好、可直接喂给任意模型的标准 Prompt
     */
    public Prompt buildHandoverPrompt(Long conversationId, 
                                      CustomerProfile profile, 
                                      List<Message> rawHistory, 
                                      String currentUserInput) {
        log.info("【上下文交接器】正在为会话 {} 组装跨模型交接上下文...", conversationId);

        List<Message> handoverMessages = new ArrayList<>();

        // 1. 组装复合系统消息 (System Prompt + 客户画像事实)
        StringBuilder systemText = new StringBuilder(BASE_SYSTEM_PROMPT);
        if (profile != null) {
            systemText.append("\n").append(profile.toPromptContext());
        }
        handoverMessages.add(new SystemMessage(systemText.toString()));

        // 2. 滑动窗口截断：只取最近 N 条历史记录 (避免历史太长撑爆 Token)
        if (rawHistory != null && !rawHistory.isEmpty()) {
            int fromIndex = Math.max(0, rawHistory.size() - DEFAULT_WINDOW_SIZE);
            List<Message> windowSlice = rawHistory.subList(fromIndex, rawHistory.size());
            handoverMessages.addAll(windowSlice);
            log.info("【上下文交接器】已继承前序历史消息 {} 条 (滑动窗口截断)", windowSlice.size());
        }

        // 3. 追加客户当前提出的最新问题
        if (currentUserInput != null && !currentUserInput.isBlank()) {
            handoverMessages.add(new UserMessage(currentUserInput));
        }

        log.info("【上下文交接器】平滑交接完成，总共组装消息块: {} 个，准备投递至新模型", handoverMessages.size());
        return new Prompt(handoverMessages);
    }

    private final java.util.Map<Long, CustomerProfile> profileMap = new java.util.concurrent.ConcurrentHashMap<>();

    public CustomerProfile getProfile(Long conversationId) {
        return profileMap.computeIfAbsent(conversationId, id -> CustomerProfile.builder()
                .companyName("Nordic Solar Solutions GmbH")
                .country("德国 · 汉堡")
                .tradeTerms("CIF Hamburg")
                .paymentTerms("T/T 30% deposit, 70% against B/L")
                .requiredCertificates("CE, TUV, RoHS")
                .purchaseVolume("500 PCS / 12 TEU")
                .build());
    }

    public void updateProfile(Long conversationId, CustomerProfile profile) {
        if (profile != null) {
            profileMap.put(conversationId, profile);
        }
    }
}
