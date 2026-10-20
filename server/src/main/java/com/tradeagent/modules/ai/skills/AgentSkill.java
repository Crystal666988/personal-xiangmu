package com.tradeagent.modules.ai.skills;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 智能体 Skill 技能实体 (对应 MySQL agent_skill 表)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_skill")
public class AgentSkill implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String category;
    private String icon;
    private String description;
    private String systemPrompt;
    private String toolIdsJson; // 绑定的原子工具 ID 列表 (JSON)
    private String status;      // ACTIVE / INACTIVE
    private Integer invocationCount;
    private Integer successCount;
    private Double avgLatencyMs;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
