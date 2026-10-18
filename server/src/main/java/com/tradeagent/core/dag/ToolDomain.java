package com.tradeagent.core.dag;

import lombok.Getter;

/**
 * 工具资产四大领域分类 (对齐 MCP 协议规范，用于意图动态路由裁剪)
 */
@Getter
public enum ToolDomain {

    VISION("vision", "多模态视觉与单据识别"),
    COMPOSITE("composite", "复合意图协同"),

    /**
     * 1. 知识与资料域：查产品库、规格书、外贸常见问题、CE/UL/TUV 认证文档
     */
    KNOWLEDGE("knowledge", "产品参数规格、质检认证与企业知识库检索"),

    /**
     * 2. 商贸与报价域：实时阶梯报价、CIF/FOB 海运费核算、生成 PI 预结单据、查交期
     */
    COMMERCE("commerce", "阶梯价格计算、海运港口运费核算与商贸单据生成"),

    /**
     * 3. 拓客与背调域：Google Maps 企业位置反查、海外官网深扫、海关进出口额查询
     */
    PROSPECT("prospect", "海外企业背景调查、地图定位反查与海关贸易额检索"),

    /**
     * 4. 客户与跟进域：客户资料档案、更新意向评级、商机阶段变更、预约跟进日程
     */
    CRM("crm", "客户关系档案管理、意向评级更新与销售跟进日程"),

    /**
     * 通用兜底
     */
    GENERAL("general", "通用商贸答疑与日常礼貌会话");

    private final String code;
    private final String description;

    ToolDomain(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
