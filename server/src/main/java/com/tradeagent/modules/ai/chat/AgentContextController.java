package com.tradeagent.modules.ai.chat;

import com.tradeagent.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent/sessions")
public class AgentContextController {
    private final AgentContextService contexts;

    public AgentContextController(AgentContextService contexts) { this.contexts = contexts; }

    public record ContextRequest(@NotBlank String tenantId, @NotBlank String currentMessageId) {}

    @PostMapping("/{sessionId}/context")
    public ApiResponse<AgentContextService.ContextResult> prepare(
            @PathVariable("sessionId") String sessionId, @Valid @RequestBody ContextRequest request) {
        // 当前项目尚未接入真实租户认证；接入后 tenantId 必须取自登录身份。
        return ApiResponse.success(contexts.prepare(request.tenantId(), sessionId, request.currentMessageId()));
    }
}
