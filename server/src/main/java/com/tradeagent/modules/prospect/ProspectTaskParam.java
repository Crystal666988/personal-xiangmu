package com.tradeagent.modules.prospect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 结构化拓客任务执行参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProspectTaskParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标国家/地区 (标准化国家码或标准名称，如 "UAE", "Saudi Arabia") */
    private String country;

    /** 核心行业与业务关键词 (如 "Solar EPC Contractor", "Inverter Distributor") */
    private String industryKeyword;

    /** 目标采集渠道列表 (如 ["GOOGLE_MAPS", "LINKEDIN"]) */
    private List<String> targetChannels;

    /** 期望采集最大数量限制 */
    @Builder.Default
    private Integer limit = 50;

    /** 是否要求必须包含有效联系电话或邮箱 */
    @Builder.Default
    private Boolean requireContact = true;
}
