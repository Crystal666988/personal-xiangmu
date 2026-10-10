package com.tradeagent.core.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 结构化客户画像实体 (跨模型交接时永不丢失的硬事实)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户国家 / 地区 (e.g. "阿联酋·迪拜")
     */
    private String country;

    /**
     * 目标意向产品 (e.g. "550W 单晶硅太阳能电池板")
     */
    private String targetProduct;

    /**
     * 预计采购量 / 预算 (e.g. "2个高柜 (40HQ), 预算约 6 万美元")
     */
    private String budgetOrQuantity;

    /**
     * 当前跟进阶段 (e.g. "询价中", "已寄样", "谈账期")
     */
    private String tradeStage;

    /**
     * 将画像转化为模型 Prompt 能够理解的高浓度摘要
     */
    public String toPromptContext() {
        return String.format(
            "【已确认的客户档案事实(禁止篡改)】\n" +
            "- 客户地区: %s\n" +
            "- 需求产品: %s\n" +
            "- 采购规模: %s\n" +
            "- 当前谈判阶段: %s\n",
            country != null ? country : "未知",
            targetProduct != null ? targetProduct : "沟通中",
            budgetOrQuantity != null ? budgetOrQuantity : "待确认",
            tradeStage != null ? tradeStage : "初次询盘"
        );
    }
}
