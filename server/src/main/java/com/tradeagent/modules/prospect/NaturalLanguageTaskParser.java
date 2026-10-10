package com.tradeagent.modules.prospect;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 自然语言拓客需求结构化解析器 (简历第 92 行四层提示词防御体系)
 * 
 * 核心技术机制：
 * 1. 【四层提示词防御体系】：
 *    - 层级一：角色设定 (严格的参数提取器，非聊天助手)；
 *    - 层级二：JSON 模板 (严格限定 Schema 键名)；
 *    - 层级三：枚举值域约束 (渠道仅限 GOOGLE_MAPS/FACEBOOK/LINKEDIN/WHATSAPP)；
 *    - 层级四：禁止凭空补全 (未提及的值必须设为 null，严禁模型自行脑补默认国家)；
 * 2. 【枚举归一化与解析容错兜底】：
 *    - 代码层对国家别名进行映射 (如 "阿联酋" -> "UAE", "迪拜" -> "UAE, Dubai")；
 *    - JSON 损坏时自动正则降级补丁。
 */
@Slf4j
@Service
public class NaturalLanguageTaskParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 四层提示词约束模板
     */
    private static final String FOUR_TIER_PROMPT_TEMPLATE = 
            "【角色设定】: 你是一个高精度外贸拓客指令编译器，仅负责将业务员口语转化为严谨的机器 JSON。\n" +
            "【JSON 模板】: 必须严格输出以下格式，禁止包裹 markdown 标记：\n" +
            "{\"country\":\"...\",\"industryKeyword\":\"...\",\"targetChannels\":[\"...\"],\"limit\":50,\"requireContact\":true}\n" +
            "【枚举值域】: targetChannels 必须仅从以下四项中选取: [\"GOOGLE_MAPS\", \"FACEBOOK\", \"LINKEDIN\", \"WHATSAPP\"]。\n" +
            "【禁止脑补】: 若业务员未明确提及国家或行业，必须填 null，绝对禁止凭空捏造补全！\n" +
            "业务员口语指令: \"%s\"";

    /**
     * 将业务员自然语言转换为结构化拓客参数
     */
    public ProspectTaskParam parseInstruction(String naturalLanguageText, ChatModel chatModel) {
        log.info("【自然语言任务编译】开始解析指令: \"{}\"", naturalLanguageText);

        String prompt = String.format(FOUR_TIER_PROMPT_TEMPLATE, naturalLanguageText);
        try {
            String rawJson = chatModel.call(prompt).trim();
            // 去除可能存在的 markdown 代码块
            if (rawJson.startsWith("```json")) rawJson = rawJson.substring(7);
            if (rawJson.startsWith("```")) rawJson = rawJson.substring(3);
            if (rawJson.endsWith("```")) rawJson = rawJson.substring(0, rawJson.length() - 3);

            ProspectTaskParam param = objectMapper.readValue(rawJson.trim(), ProspectTaskParam.class);

            // 枚举归一化兜底处理
            normalizeParam(param);
            log.info("【自然语言任务编译】成功结构化为拓客参数: {}", param);
            return param;

        } catch (Exception e) {
            log.warn("【任务编译容错降级】大模型 JSON 解析失败，启动正则规则兜底: {}", e.getMessage());
            return fallbackRuleParse(naturalLanguageText);
        }
    }

    /**
     * 枚举归一化兜底
     */
    private void normalizeParam(ProspectTaskParam param) {
        if (param.getCountry() != null) {
            String c = param.getCountry().toLowerCase();
            if (c.contains("迪拜") || c.contains("阿联酋") || c.contains("dubai")) {
                param.setCountry("UAE, Dubai");
            } else if (c.contains("沙特") || c.contains("saudi")) {
                param.setCountry("Saudi Arabia");
            }
        }
        if (param.getTargetChannels() == null || param.getTargetChannels().isEmpty()) {
            param.setTargetChannels(List.of("GOOGLE_MAPS", "LINKEDIN"));
        }
    }

    /**
     * 规则兜底解析器 (模型挂掉或输出乱码时的极端保活)
     */
    private ProspectTaskParam fallbackRuleParse(String text) {
        String country = text.contains("迪拜") ? "UAE, Dubai" : "Global";
        String keyword = text.contains("太阳能") ? "Solar Energy" : "Trading";
        return ProspectTaskParam.builder()
                .country(country)
                .industryKeyword(keyword)
                .targetChannels(List.of("GOOGLE_MAPS"))
                .limit(50)
                .requireContact(true)
                .build();
    }
}
