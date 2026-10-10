package com.tradeagent.core.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 专业级外贸 B2B 客户全维画像实体 (Cross-Border B2B Customer Profile)
 * 
 * 覆盖四大核心维度：
 * 1. 基础企业与决策人身份 (公司、职位、联系方式、客户性质)
 * 2. 核心商贸采购需求 (意向品类、货柜量、预算、期望贸易术语 FOB/CIF)
 * 3. 供应链资质与实力评估 (进口经验、目标市场、清关认证 CE/UL/TUV)
 * 4. 意向评级与风控状态 (意向等级 A/B/C/D、谈判阶段、风险提示)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 1. 企业与决策人基础身份 ====================
    /** 客户公司名称 (e.g. "Al-Futtaim Solar Energy LLC") */
    private String companyName;

    /** 联系人姓名 (e.g. "Tariq Mansour") */
    private String contactName;

    /** 职位 / 决策角色 (e.g. "采购总监 Procurement Director", "技术工程师", "老板") */
    private String jobTitle;

    /** 所在国家与城市 (e.g. "阿联酋 · 迪拜 (UAE, Dubai)") */
    private String country;

    /** 官方网址 / 社媒主页 (e.g. "https://www.example-solar.ae") */
    private String website;

    /** WhatsApp 账号 / 电话 (带国际区号) */
    private String phoneOrWhatsApp;

    /** 业务邮箱 */
    private String email;

    /** 客户类型 (个人买家 / 品牌分销商 / EPC工程总包 / 零售商 / 贸易商) */
    private String customerType;

    // ==================== 2. 核心商贸与采购需求 ====================
    /** 目标需求产品与具体规格 (e.g. "550W N型TOPCon 单晶硅光伏组件") */
    private String targetProduct;

    /** 采购数量 / 货柜规格 (e.g. "首单 2 个 40HQ 高柜, 约 1.2MW") */
    private String purchaseVolume;

    /** 预估预算金额 (e.g. "约 12 万美元 (USD)") */
    private String budgetAmount;

    /** 期望交期 / 项目上线时间 (e.g. "2026年第四季度前到港") */
    private String expectedDeliveryTime;

    /** 期望贸易条款 (FOB 上海, CIF 迪拜港, EXW 工厂交货, DDP) */
    private String tradeTerms;

    /** 期望付款方式 / 账期 (30% T/T 订金, 即期不可撤销信用证 L/C at sight, OA) */
    private String paymentTerms;

    // ==================== 3. 供应链资质与实力背调 ====================
    /** 进口经验与资质 (有成熟自主清关能力 / 首次进口需协助 / 依赖当地代理) */
    private String importExperience;

    /** 目标销售市场 (本地工程自用 / 转口贸易中东及北非) */
    private String targetMarket;

    /** 所需强制认证标准 (CE, UL, TUV, RoHS, CB, IEC61215) */
    private String requiredCertificates;

    /** 企业规模与年营业额 (员工 50-100 人, 年营业额约 500 万美元) */
    private String companyScale;

    // ==================== 4. 销售漏斗意向评级与风控 ====================
    /** 意向评级 (A类: 明确需求近期下单 / B类: 持续比价 / C类: 泛询价 / D类: 垃圾线索) */
    @Builder.Default
    private String intentLevel = "B";

    /** 当前商机跟进阶段 (初步询盘 / 明确规格 / 寄送样品 / 商务报价 / 账期谈判 / 赢单) */
    @Builder.Default
    private String tradeStage = "初步询盘";

    /** 风险预警提示 (如: 无企业域名个人邮箱需防骗、未明确付款条款、当地政局外汇管制) */
    private String riskHints;

    /**
     * 将全维画像格式化为不可篡改的高浓度 Prompt 上下文
     */
    public String toPromptContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("【已核实的海外买家高精度档案事实(系统锁定，严禁违背或擅自覆盖)】\n");
        sb.append(String.format("1. 买家企业: %s | 联系人: %s (%s) | 地区: %s\n",
                val(companyName, "待确认"), val(contactName, "客户"), val(jobTitle, "业务联系人"), val(country, "未知")));
        sb.append(String.format("2. 买家性质: %s | 规模: %s | 官网/邮箱: %s / %s\n",
                val(customerType, "企业采购商"), val(companyScale, "未提供"), val(website, "--"), val(email, "--")));
        sb.append(String.format("3. 核心需求: 【产品: %s】 【规格体量: %s】 【预算: %s】\n",
                val(targetProduct, "沟通中"), val(purchaseVolume, "待确认"), val(budgetAmount, "未明确")));
        sb.append(String.format("4. 商务条款: 贸易术语=%s | 账期方式=%s | 期望交期=%s\n",
                val(tradeTerms, "FOB"), val(paymentTerms, "T/T"), val(expectedDeliveryTime, "待商定")));
        sb.append(String.format("5. 资质与认证: 进口能力=%s | 必备认证=%s\n",
                val(importExperience, "有进口经验"), val(requiredCertificates, "按常规国际标准")));
        sb.append(String.format("6. 商机跟进: 阶段=%s | 意向等级=%s | 风险提示=%s\n",
                val(tradeStage, "初步沟通"), val(intentLevel, "B"), val(riskHints, "暂无异常")));
        return sb.toString();
    }

    private String val(String str, String def) {
        return (str != null && !str.isBlank()) ? str : def;
    }
}
