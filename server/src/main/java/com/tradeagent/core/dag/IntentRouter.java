package com.tradeagent.core.dag;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** 后端统一意图路由：多领域取并集，工具按 ID 排序。 */
@Component
public class IntentRouter {
    private static final Map<ToolDomain, Pattern> RULES = Map.of(
            ToolDomain.COMMERCE, Pattern.compile("(?i)(\\b(price|quotation|quote|cif|fob|pi|freight|cost)\\b|报价|价格|运费|单价|货柜|账期|多少钱|美元)"),
            ToolDomain.KNOWLEDGE, Pattern.compile("(?i)(\\b(specification|datasheet|cert|ce|ul|tuv|rohs|warranty|catalog)\\b|参数|规格|认证|证书|质保|手册|说明书|文档|简历|技术栈|项目|架构|并发|方案|建议|怎么办|如何|分析|优化|\\.(pdf|txt|md|docx?|csv|json)$)"),
            ToolDomain.PROSPECT, Pattern.compile("(?i)(\\b(company|website|address|customs|background|maps)\\b|官网|背景|海关|税号|背调|地址|提单)"),
            ToolDomain.CRM, Pattern.compile("(?i)(\\b(profile|follow up|schedule|stage|crm)\\b|档案|跟进|意向|预约|联系人|建档|客户)"),
            ToolDomain.VISION, Pattern.compile("(?i)(图片|截图|拍照|单据|识别|看图|ocr|图纸|扫描件|讲了啥|讲了什么|\\.(png|jpe?g|webp)$)"));

    public record Tool(String id, String name, String domain, String desc) {}
    public record Routing(String domain, String domainName, String icon, String color,
                          String reason, List<Tool> activeTools, int prunedToolsCount) {}

    // 仅登记元数据；接入真实执行器后才可下发 function schema。
    private static final List<Tool> TOOLS = List.of(
            new Tool("calculate_tier_price", "阶梯价格计算器", "COMMERCE", "根据采购数量计算阶梯折扣与单价"),
            new Tool("estimate_freight_cif", "CIF/FOB 海运费核算", "COMMERCE", "按港口与柜型核算运费与保险"),
            new Tool("generate_proforma_invoice", "PI 单据生成", "COMMERCE", "根据订单要素生成预结单据"),
            new Tool("currency_exchange_rate", "外汇牌价查询", "COMMERCE", "查询即期汇率"),
            new Tool("vision_multimodal_ocr", "视觉 OCR 抽取", "VISION", "提取图像和单据中的文字"),
            new Tool("visual_key_verifier", "凭证格式核查", "VISION", "核查凭证编号与印章格式"),
            new Tool("ast_spec_lookup", "图纸表格要素提取", "VISION", "提取图纸参数与表格信息"),
            new Tool("rag_vector_search", "知识库检索", "KNOWLEDGE", "检索产品手册、规格与认证资料"),
            new Tool("cert_compliance_check", "认证匹配", "KNOWLEDGE", "核查目标国准入认证"),
            new Tool("google_maps_radar", "企业地图查询", "PROSPECT", "查询企业位置"),
            new Tool("customs_data_lookup", "海关提单检索", "PROSPECT", "查询历史进出口提单"),
            new Tool("website_deep_scanner", "企业官网检索", "PROSPECT", "查询官网介绍与联系方式"),
            new Tool("update_customer_profile", "客户画像更新", "CRM", "更新已确认的客户业务事实"),
            new Tool("schedule_follow_up", "跟进日程", "CRM", "创建跟进任务"),
            new Tool("opportunity_stage_shift", "商机阶段更新", "CRM", "更新商机阶段"));

    public ToolDomain routeDomain(String input) {
        return ToolDomain.valueOf(route(input, null).domain());
    }

    public Routing route(String input, String attachmentName) {
        String text = Objects.toString(input, "") + " " + Objects.toString(attachmentName, "");
        EnumSet<ToolDomain> domains = EnumSet.noneOf(ToolDomain.class);
        RULES.forEach((domain, pattern) -> { if (pattern.matcher(text.trim()).find()) domains.add(domain); });
        ToolDomain primary = domains.isEmpty() ? ToolDomain.GENERAL
                : domains.size() > 1 ? ToolDomain.COMPOSITE : domains.iterator().next();
        List<Tool> selected = TOOLS.stream()
                .filter(t -> domains.contains(ToolDomain.valueOf(t.domain())))
                .sorted(Comparator.comparing(Tool::id)).toList();
        return new Routing(primary.name(), primary.getDescription(), "Connection", "#409eff",
                domains.isEmpty() ? "通用对话，无需挂载业务工具" : "命中领域：" + domains,
                selected, TOOLS.size() - selected.size());
    }

    public String describeTools(Routing routing) {
        if (routing.activeTools().isEmpty()) return null;
        return "以下仅为本轮匹配的工具目录，尚未接入自动执行，不能声称已调用：\n"
                + String.join("\n", routing.activeTools().stream()
                .map(t -> t.id() + " | " + t.name() + " | " + t.desc()).toList());
    }
}
