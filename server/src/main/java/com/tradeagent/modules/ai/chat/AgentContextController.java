package com.tradeagent.modules.ai.chat;

import com.tradeagent.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import com.tradeagent.modules.ai.intent.RoutingWorkflowService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent/sessions")
public class AgentContextController {
    private final AgentContextService contexts;

    public AgentContextController(AgentContextService contexts) { this.contexts = contexts; }

    public record ContextRequest(@NotBlank String tenantId, @NotBlank String currentMessageId,
                                 RoutingModelConfig modelConfig, String routingAction) {}
    public record CancelRequest(@NotBlank String tenantId) {}

    @PostMapping("/{sessionId}/context")
    public ApiResponse<AgentContextService.ContextResult> prepare(
            @PathVariable("sessionId") String sessionId, @Valid @RequestBody ContextRequest request) {
        // 当前项目尚未接入真实租户认证；接入后 tenantId 必须取自登录身份。
        return ApiResponse.success(contexts.prepare(request.tenantId(), sessionId, request.currentMessageId(),
                request.modelConfig(), request.routingAction() == null ? "continue" : request.routingAction()));
    }

    @PostMapping("/{sessionId}/routing/cancel")
    public ApiResponse<RoutingWorkflowService.Result> cancel(@PathVariable("sessionId") String sessionId,
                                                            @Valid @RequestBody CancelRequest request) {
        return ApiResponse.success(contexts.cancelRouting(request.tenantId(), sessionId));
    }
}
