package com.tradeagent.modules.ai.intent.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 路由评测第一版数据集：常见问题 + 边界场景。
 *
 * 设计原则：
 * 1. expectedTools 是“理想最小工具集合”，不要求覆盖工具内部的所有附加调用；
 * 2. expectedParams 只声明关键参数，未声明的参数不参与评分；
 * 3. CRM / VISION / 网站地图等工具目前在 ToolRegistry 中可能还没有执行器，
 *    评测会把这些期望工具单独标记为“工具缺口”，而不是直接算作路由错误。
 */
public class RoutingEvalDataset {

    public static List<RoutingEvalCase> getCommonCases() {
        List<RoutingEvalCase> list = new ArrayList<>();

        // ==========================================
        // 1. 通用闲聊：不应挂载任何业务工具
        // ==========================================
        list.add(c("RT-GEN-001", "CHITCHAT",
                "你好，请问你是谁？", null,
                "GENERAL", List.of(), Map.of(),
                "普通问候，不应触发任何外贸工具"));

        list.add(c("RT-GEN-002", "CHITCHAT",
                "今天深圳天气怎么样？", null,
                "GENERAL", List.of(), Map.of(),
                "天气闲聊，不应因为出现地点名词而触发拓客工具"));

        list.add(c("RT-GEN-003", "CHITCHAT",
                "帮我写一首关于秋天的诗", null,
                "GENERAL", List.of(), Map.of(),
                "内容生成类闲聊，不应触发业务工具"));

        list.add(c("RT-GEN-004", "CHITCHAT",
                "用 Java 写一个快速排序", null,
                "GENERAL", List.of(), Map.of(),
                "通用编程问答，不应因为出现 Java 而触发知识库工具"));

        // ==========================================
        // 2. 商贸报价：应只调用当前问题真正需要的工具
        // ==========================================
        list.add(c("RT-COMM-001", "SINGLE_DOMAIN",
                "580W 组件采购 2000 套，FOB 单价和总货值是多少？", null,
                "COMMERCE", List.of("calculate_tier_price"),
                Map.of("productModel", "580W", "quantity", 2000),
                "只问价格与货值，应只调阶梯报价，不应额外调运费和汇率"));

        list.add(c("RT-COMM-002", "SINGLE_DOMAIN",
                "查一下今天美元兑人民币汇率", null,
                "COMMERCE", List.of("currency_exchange_rate"),
                Map.of("baseCurrency", "USD", "targetCurrency", "CNY"),
                "纯汇率查询，应只调汇率工具"));

        list.add(c("RT-COMM-003", "SINGLE_DOMAIN",
                "3 个 40HQ 发往迪拜杰贝阿里港，海运费多少？", null,
                "COMMERCE", List.of("estimate_freight_cif"),
                Map.of("destPort", "Dubai (Jebel Ali)"),
                "纯海运费查询，应只调运费工具；3 个柜不是 3 套货"));

        list.add(c("RT-COMM-004", "SINGLE_DOMAIN",
                "采购 5000 套 580W 组件，阶梯单价是多少？", null,
                "COMMERCE", List.of("calculate_tier_price"),
                Map.of("productModel", "580W", "quantity", 5000),
                "只问阶梯单价，应只调阶梯报价"));

        list.add(c("RT-COMM-005", "SINGLE_DOMAIN",
                "把 125,000 美元按今天汇率折算成人民币", null,
                "COMMERCE", List.of("currency_exchange_rate"),
                Map.of("baseCurrency", "USD", "targetCurrency", "CNY"),
                "纯汇率换算，应只调汇率工具；金额不是采购数量"));

        list.add(c("RT-COMM-006", "TRICKY",
                "发往阿布扎比港 2 个高柜海运费", null,
                "COMMERCE", List.of("estimate_freight_cif"),
                Map.of("destPort", "Abu Dhabi"),
                "阿布扎比不在当前港口白名单，验证是否会错误回退到迪拜"));

        list.add(c("RT-COMM-007", "TRICKY",
                "580W 采购 2000 套", null,
                "COMMERCE", List.of("calculate_tier_price"),
                Map.of("productModel", "580W", "quantity", 2000),
                "数字顺序是 580W 在前、2000 套在后，验证数量抽取不会取成 580"));

        // ==========================================
        // 3. 知识库：技术参数、认证、标准
        // ==========================================
        list.add(c("RT-KNOW-001", "SINGLE_DOMAIN",
                "TOPCon 580W 首年衰减率是多少？", null,
                "KNOWLEDGE", List.of("rag_vector_search"), Map.of(),
                "技术参数问答，应只调知识库检索"));

        list.add(c("RT-KNOW-002", "SINGLE_DOMAIN",
                "出口沙特需要 SASO 认证吗？", null,
                "KNOWLEDGE", List.of("rag_vector_search"), Map.of(),
                "认证合规问答，应只调知识库检索"));

        list.add(c("RT-KNOW-003", "TRICKY",
                "40HQ 集装箱的标准内径尺寸和容积", null,
                "KNOWLEDGE", List.of("rag_vector_search"), Map.of(),
                "尺寸标准属于知识库；验证不会被 COMMERCE 的 40HQ 误判为报价"));

        list.add(c("RT-KNOW-004", "TRICKY",
                "580W 组件的开路电压和工作电流是多少？", null,
                "KNOWLEDGE", List.of("rag_vector_search"), Map.of(),
                "开路电压/工作电流是典型技术参数，验证 KNOWLEDGE 词表覆盖"));

        list.add(c("RT-KNOW-005", "SINGLE_DOMAIN",
                "分析一下这份技术文档", "spec.pdf",
                "KNOWLEDGE", List.of("rag_vector_search"), Map.of(),
                "附件后缀 .pdf 应触发知识库路由"));

        // ==========================================
        // 4. 海关与拓客
        // ==========================================
        list.add(c("RT-PROS-001", "SINGLE_DOMAIN",
                "查询海关 HS 8541.4300 在鹿特丹的进口采购商", null,
                "PROSPECT", List.of("customs_data_lookup"),
                Map.of("hsCode", "8541.4300"),
                "HS 编码与采购商查询，应调海关工具且保留 HS 编码"));

        list.add(c("RT-PROS-002", "SINGLE_DOMAIN",
                "查询 Al-Futtaim 的海关提单记录", null,
                "PROSPECT", List.of("customs_data_lookup"),
                Map.of("companyName", "Al-Futtaim Solar Energy Solutions LLC"),
                "指定公司名的提单查询，应保留公司名"));

        list.add(c("RT-PROS-003", "SINGLE_DOMAIN",
                "帮我查一下 ACME Solar 的官网和地址", null,
                "PROSPECT", List.of("website_deep_scanner", "google_maps_radar"),
                Map.of("companyName", "ACME Solar"),
                "官网与地址查询，应调网站扫描和地图工具（当前可能尚未实现）"));

        // ==========================================
        // 5. CRM：客户、商机、跟进
        // ==========================================
        list.add(c("RT-CRM-001", "SINGLE_DOMAIN",
                "在 CRM 中新建商机并推进到商务谈判阶段", null,
                "CRM", List.of("opportunity_stage_shift"), Map.of(),
                "CRM 写操作，应调商机阶段更新工具（当前可能尚未实现）"));

        list.add(c("RT-CRM-002", "SINGLE_DOMAIN",
                "为销售创建下周二下午的客户跟进日程", null,
                "CRM", List.of("schedule_follow_up"), Map.of(),
                "CRM 日程操作，应调跟进日程工具（当前可能尚未实现）"));

        list.add(c("RT-CRM-003", "SINGLE_DOMAIN",
                "更新客户画像中的年度采购预算", null,
                "CRM", List.of("update_customer_profile"), Map.of(),
                "CRM 画像更新，应调客户画像工具（当前可能尚未实现）"));

        // ==========================================
        // 6. 视觉单据
        // ==========================================
        list.add(c("RT-VIS-001", "SINGLE_DOMAIN",
                "识别这张装箱单截图中的明细", null,
                "VISION", List.of("vision_multimodal_ocr"), Map.of(),
                "图片 OCR 抽取，应调视觉 OCR 工具（当前可能尚未实现）"));

        list.add(c("RT-VIS-002", "TRICKY",
                "看一下图纸里的表格参数", null,
                "VISION", List.of("ast_spec_lookup"), Map.of(),
                "图纸参数应走 VISION；验证不会被 KNOWLEDGE 的“参数”误判为知识库"));

        // ==========================================
        // 7. 复合意图：必须同时命中多个领域和多个工具
        // ==========================================
        list.add(c("RT-COMP-001", "COMPOSITE",
                "查 580W 组件开路电压，并计算 2000 套到鹿特丹 CIF 总价", null,
                "COMPOSITE", List.of("rag_vector_search", "calculate_tier_price", "estimate_freight_cif"),
                Map.of("productModel", "580W", "quantity", 2000, "destPort", "Rotterdam, Netherlands"),
                "技术参数 + 报价 + 运费，验证知识库词表与复合路由"));

        list.add(c("RT-COMP-002", "COMPOSITE",
                "客户问 580W 价格和认证", null,
                "COMPOSITE", List.of("calculate_tier_price", "rag_vector_search"),
                Map.of("productModel", "580W"),
                "价格 + 认证；这里的“客户”只是语境，不应触发 CRM 工具"));

        list.add(c("RT-COMP-003", "COMPOSITE",
                "查询海关提单，并更新 CRM 商机阶段", null,
                "COMPOSITE", List.of("customs_data_lookup", "opportunity_stage_shift"), Map.of(),
                "海关查询 + CRM 写操作，验证 COMPOSITE 下 CRM 工具是否真正执行"));

        list.add(c("RT-COMP-004", "COMPOSITE",
                "报价 2000 套到迪拜，并在 CRM 中记录商机", null,
                "COMPOSITE", List.of("calculate_tier_price", "estimate_freight_cif", "opportunity_stage_shift"),
                Map.of("quantity", 2000, "destPort", "Dubai (Jebel Ali)"),
                "报价 + 运费 + CRM 商机记录，验证商业与 CRM 工具聚合"));

        return list;
    }

    private static RoutingEvalCase c(String id, String category, String query, String attachmentName,
                                     String expectedDomain, List<String> expectedTools,
                                     Map<String, Object> expectedParams, String explanation) {
        return RoutingEvalCase.builder()
                .id(id)
                .category(category)
                .query(query)
                .attachmentName(attachmentName)
                .expectedDomain(expectedDomain)
                .expectedTools(expectedTools)
                .expectedParams(expectedParams)
                .explanation(explanation)
                .build();
    }
}
