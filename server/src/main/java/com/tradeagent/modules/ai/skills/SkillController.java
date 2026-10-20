package com.tradeagent.modules.ai.skills;

import com.tradeagent.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 智能体 Skill 技能中心 REST 控制器
 */
@Slf4j
@Tag(name = "智能体技能中心")
@RestController
@RequestMapping("/api/v1/agent/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 1. 获取所有可用 Skill 列表 (含绑定工具详情与指标)
     */
    @GetMapping("/list")
    @Operation(summary = "获取所有智能体技能列表")
    public ApiResponse<List<SkillDefinition>> listSkills() {
        return ApiResponse.success(skillService.listAllSkills());
    }

    /**
     * 2. 启用/停用某个 Skill
     */
    @PostMapping("/{id}/toggle")
    @Operation(summary = "切换技能启用/停用状态")
    public ApiResponse<Boolean> toggleSkill(@PathVariable("id") String id) {
        boolean ok = skillService.toggleSkill(id);
        return ApiResponse.success(ok);
    }

    @Data
    public static class SkillExecuteRequest {
        private Map<String, Object> params;
    }

    /**
     * 3. 单独触发/调试某个 Skill
     */
    @PostMapping("/{id}/execute")
    @Operation(summary = "单技能触发调试运行")
    public ApiResponse<SkillService.SkillExecutionResult> executeSkill(
            @PathVariable("id") String id,
            @RequestBody(required = false) SkillExecuteRequest request
    ) {
        Map<String, Object> params = request != null && request.getParams() != null ?
                request.getParams() : Map.of();
        SkillService.SkillExecutionResult res = skillService.executeSkill(id, params);
        return ApiResponse.success(res);
    }
}
