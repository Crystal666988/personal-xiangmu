package com.tradeagent.modules.ai.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 跨会话客户画像实体记忆 (Entity Memory)
 *
 * 对应 MySQL 数据表: crm_buyer_profile
 * 遵循 Carl (youngyangyang04/llm-master) 工业级规范：
 * 1. 【只填空不覆盖原则 (Fill-Blank-Only)】：AI 从多轮对话提炼时，仅当字段为 NULL 时填充，已有值坚决不被静默覆盖；
 * 2. 【人工加锁不可篡改 (Locked Fields Immutability)】：业务员人工核准确认过的字段写入 lockedFieldsJson，AI 提取引擎永久加锁保护。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("crm_buyer_profile")
public class CrmBuyerProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 唯一主键ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户ID (默认 1001) */
    private String tenantId;

    /** 买家/客户标识 */
    private String buyerId;

    /** 联系人姓名 */
    private String buyerName;

    /** 企业全称 */
    private String companyName;

    /** 目标国家/地区 (如 Germany, Netherlands, UAE) */
    private String country;

    /** 常用目的港 (如 Hamburg, Rotterdam, Dubai) */
    private String destinationPort;

    /** 常用贸易术语 (CIF, FOB, CFR, EXW, DDP) */
    private String preferredIncoterm;

    /** 核心意向采购产品 (如 550W 单晶硅组件) */
    private String targetProduct;

    /** 意向采购规模 (如 2000片, 2个40HQ) */
    private String targetVolume;

    /** 价格敏感度 (HIGH, MEDIUM, LOW) */
    private String priceSensitivity;

    /**
     * 人工确认加锁字段 JSON 列表 (例如: ["destinationPort", "preferredIncoterm"])
     * 被加锁的字段，AI 提取更新绝对不可篡改覆盖
     */
    private String lockedFieldsJson;

    /**
     * 动态扩展属性 JSON Map
     */
    private String extraAttributesJson;

    /** 最新提取来源的会话ID */
    private String sourceSessionId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除: 0未删除 1已删除 */
    @TableLogic
    private Integer deleted;
}
