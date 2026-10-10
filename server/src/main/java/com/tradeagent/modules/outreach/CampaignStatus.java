package com.tradeagent.modules.outreach;

import lombok.Getter;

/**
 * 营销活动状态机枚举 (Saga 事务状态流转)
 */
@Getter
public enum CampaignStatus {

    /** 草稿待编辑 */
    DRAFT("草稿"),

    /** 人机预演待审核 (Human-in-the-loop 门禁) */
    PENDING_REVIEW("待人工确认"),

    /** 派发中 (预扣配额已完成，正在 3 线程配额闸门内发送) */
    DISPATCHING("发送中"),

    /** 派发全量成功 */
    SUCCESS("已完成"),

    /** 触发 Saga 逆向补偿 (正在执行配额退还) */
    COMPENSATING("正在逆向退还配额"),

    /** 最终失败标记 */
    FAILED("发送失败");

    private final String description;

    CampaignStatus(String description) {
        this.description = description;
    }
}
