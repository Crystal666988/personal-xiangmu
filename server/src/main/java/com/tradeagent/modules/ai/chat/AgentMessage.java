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
 * AI 智能体对话消息实体类 (对应 MySQL 数据库表 agent_message)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_message")
public class AgentMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息唯一ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属会话ID */
    private String sessionId;

    /** 发送方: user 或 assistant */
    private String role;

    /** 正式文本回答内容 (Markdown 渲染文本) */
    private String content;

    /** CoT 思考推导链 (含意图路由与大模型思维链) */
    private String thought;

    /** 思考用时 (秒) */
    private Integer thoughtDuration;

    /** 意图路由与工具剪枝元数据 JSON */
    private String routing;

    /** 附件与 OSS 访问 URL 元数据 JSON */
    private String attachment;

    /** 展示时间 (如 10:45) */
    private String time;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 逻辑删除: 0未删除 1已删除 */
    @TableLogic
    private Integer deleted;
}
