package com.tradeagent.core.dag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 意图路由器 (Intent Router & Tool Pruning)
 * 
 * 核心技术机制：
 * 1. 【极速轻量路由】：在调主模型前，以 1ms 级别的规则+模式匹配命中核心意图；
 * 2. 【工具动态裁剪 (Tool Pruning)】：将全量 30+ 工具动态收敛为该领域所属的 2~3 个专属工具；
 * 3. 【防幻觉防超限】：彻底避免将全部工具 Schema 灌入 Prompt 引发的 Token 浪费与注意力稀释。
 */
@Slf4j
@Component
public class IntentRouter {

    // 价格、报价、运费、CIF、FOB、PI 单据等商贸关键词
    private static final Pattern COMMERCE_PATTERN = Pattern.compile(
            "(?i)(price|quotation|quote|cif|fob|pi|freight|cost|shipping fee|报价|价格|运费|单价|货柜|账期|多少钱)"
    );

    // 认证、规格、参数、CE、TUV、UL、光伏组件、手册等知识关键词
    private static final Pattern KNOWLEDGE_PATTERN = Pattern.compile(
            "(?i)(specification|datasheet|cert|ce|ul|tuv|rohs|warranty|catalog|参数|规格|认证|证书|质保|手册|说明书)"
    );

    // 背调、地图、海关、公司、官网等背调拓客关键词
    private static final Pattern PROSPECT_PATTERN = Pattern.compile(
            "(?i)(company|website|address|customs|background|maps|官网|背景|海关|税号|背调|地址|查一下公司)"
    );

    // 客户档案、跟进、预约、阶段等 CRM 关键词
    private static final Pattern CRM_PATTERN = Pattern.compile(
            "(?i)(profile|follow up|schedule|stage|crm|档案|跟进|意向|预约|联系人|建档)"
    );

    /**
     * 根据用户输入快速路由到对应的工具领域
     *
     * @param userMessage 用户的即时输入
     * @return 命中的工具领域
     */
    public ToolDomain routeDomain(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return ToolDomain.GENERAL;
        }

        String trimmed = userMessage.trim();

        // 1. 优先检测高频商贸与报价意图 (外贸业务核心)
        if (COMMERCE_PATTERN.matcher(trimmed).find()) {
            log.info("【意图路由器】命中商贸与报价域 (COMMERCE) -> 动态挂载: 阶梯报价工具 / 海运费核算工具");
            return ToolDomain.COMMERCE;
        }

        // 2. 检测产品规格与清关资质知识意图
        if (KNOWLEDGE_PATTERN.matcher(trimmed).find()) {
            log.info("【意图路由器】命中知识与资料域 (KNOWLEDGE) -> 动态挂载: RAG知识库检索 / 证书文件提取工具");
            return ToolDomain.KNOWLEDGE;
        }

        // 3. 检测海外企业背调与数据挖掘意图
        if (PROSPECT_PATTERN.matcher(trimmed).find()) {
            log.info("【意图路由器】命中拓客与背调域 (PROSPECT) -> 动态挂载: GoogleMaps探查 / 官网深扫工具");
            return ToolDomain.PROSPECT;
        }

        // 4. 检测 CRM 客户跟进意图
        if (CRM_PATTERN.matcher(trimmed).find()) {
            log.info("【意图路由器】命中CRM管理域 (CRM) -> 动态挂载: 客户档案回写 / 跟进日志工具");
            return ToolDomain.CRM;
        }

        log.info("【意图路由器】命中通用商贸咨询 (GENERAL) -> 采用标准对话策略");
        return ToolDomain.GENERAL;
    }
}
