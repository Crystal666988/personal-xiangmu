package com.tradeagent.modules.ai.skills;

import com.tradeagent.modules.ai.tools.ToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 智能体 Skill 技能定义与展现模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {
    private String id;
    private String name;
    private String category;
    private String icon;
    private String description;
    private String systemPrompt;
    private List<String> toolIds;
    private List<ToolDefinition> boundTools;
    private String status; // ACTIVE / INACTIVE
    private List<String> tags;
    private int invocationCount;
    private double successRate;
    private double avgLatencyMs;
    private Map<String, Object> sampleInput;
}
