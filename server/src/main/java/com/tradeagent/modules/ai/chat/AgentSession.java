package com.tradeagent.modules.ai.chat;

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
 * AI 智能体会话实体类 (对应 MySQL 数据库表 agent_session)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_session")
public class AgentSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话唯一ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户ID (默认 1001) */
    private String tenantId;

    /** 会话标题 (首条消息动态提取) */
    private String title;

    /** 绑定智能体ID (如 commerce-agent, knowledge-agent) */
    private String agentId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除: 0未删除 1已删除 */
    @TableLogic
    private Integer deleted;
}
