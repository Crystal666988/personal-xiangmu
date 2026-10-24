package com.tradeagent.modules.ai.chat;

import com.tradeagent.core.common.ApiResponse;
import com.tradeagent.core.dag.*;
import com.tradeagent.core.dag.model.AgentExecutionCheckpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import com.tradeagent.modules.ai.modelconfig.TenantModelConfigService;

/** Same tenant/session ownership boundary as AgentContextController. Replace tenantId with auth principal when auth lands. */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentExecutionController {
    private final AgentSessionMapper sessions;
    private final AgentSessionService messages;
    private final ExecutionCheckpointService checkpoints;
    private final CheckpointedDagExecutor executor;
    private final HitlApprovalService approvals;
    private final TenantModelConfigService modelConfigs;
    public AgentExecutionController(AgentSessionMapper sessions, AgentSessionService messages, ExecutionCheckpointService checkpoints,
                                    CheckpointedDagExecutor executor, HitlApprovalService approvals, TenantModelConfigService modelConfigs) {
        this.sessions = sessions; this.messages = messages; this.checkpoints = checkpoints;
        this.executor = executor; this.approvals = approvals; this.modelConfigs = modelConfigs;
    }
    public record DecisionRequest(@NotBlank String tenantId, @NotBlank String sessionId, @NotBlank String taskId,
                                  @NotNull HitlApprovalService.Action action, @Size(max = 32) Map<String, Object> overrideParams,
                                  RoutingModelConfig modelConfig) {}
    public record ResumeRequest(@NotBlank String tenantId, @NotBlank String sessionId,
                                @Size(max = 32) Map<String, Object> injectedSlots, RoutingModelConfig modelConfig) {}

    /** 前端不再携带 API Key：未传配置时由服务端解析租户"使用中"的数据库配置。 */
    private RoutingModelConfig resolve(String tenantId, RoutingModelConfig config) {
        return config != null ? config : modelConfigs.resolveActive(tenantId).orElse(null);
    }

    @PostMapping("/hitl/{approvalId}/decision")
    public ApiResponse<CheckpointedDagExecutor.View> decide(@PathVariable("approvalId") String approvalId,
                                                           @Valid @RequestBody DecisionRequest request) {
        requireSession(request.tenantId(), request.sessionId());
        var cp = checkpoints.getCheckpoint(request.tenantId(), request.sessionId(), request.taskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "审批不存在或已过期"));
        // Rejection always remains possible even if the source message has been deleted.
        if (request.action() != HitlApprovalService.Action.REJECT) requireSources(cp);
        return ApiResponse.success(approvals.decide(request.tenantId(), request.sessionId(), request.taskId(), approvalId,
                request.action(), request.overrideParams(), resolve(request.tenantId(), request.modelConfig())));
    }
    @GetMapping("/sessions/{sessionId}/execution")
    public ApiResponse<CheckpointedDagExecutor.View> active(@PathVariable("sessionId") String session, @RequestParam("tenantId") String tenant) {
        requireSession(tenant, session);
        return ApiResponse.success(checkpoints.active(tenant, session).map(executor::view).orElse(null));
    }
    @GetMapping("/checkpoints/{checkpointId}")
    public ApiResponse<CheckpointedDagExecutor.View> get(@PathVariable("checkpointId") String checkpointId,
                                                       @RequestParam("tenantId") String tenant, @RequestParam("sessionId") String session) {
        requireSession(tenant, session);
        return ApiResponse.success(executor.view(checkpoints.byId(tenant, session, checkpointId)));
    }
    @PostMapping("/checkpoints/{checkpointId}/resume")
    public ApiResponse<CheckpointedDagExecutor.View> resume(@PathVariable("checkpointId") String checkpointId,
                                                          @Valid @RequestBody ResumeRequest request) {
        requireSession(request.tenantId(), request.sessionId());
        var cp = checkpoints.byId(request.tenantId(), request.sessionId(), checkpointId);
        if (!Set.of("COMPENSATION_REQUIRED", "COMPENSATING", "COMPENSATION_FAILED", "REJECTED").contains(cp.getState().getStatus().name()))
            requireSources(cp);
        return ApiResponse.success(checkpoints.resumeExecution(request.tenantId(), request.sessionId(), checkpointId, request.injectedSlots(),
                resolve(request.tenantId(), request.modelConfig())));
    }
    @PostMapping("/checkpoints/{checkpointId}/cancel")
    public ApiResponse<CheckpointedDagExecutor.View> cancel(@PathVariable("checkpointId") String checkpointId,
                                                          @Valid @RequestBody ResumeRequest request) {
        requireSession(request.tenantId(), request.sessionId());
        return ApiResponse.success(executor.cancel(request.tenantId(), request.sessionId(), checkpointId));
    }
    private void requireSession(String tenant, String sessionId) {
        var session = sessions.selectById(sessionId);
        if (session == null || !Objects.equals(tenant, session.getTenantId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
    }
    private void requireSources(AgentExecutionCheckpoint cp) {
        var stored = messages.listMessages(cp.getSessionId());
        for (var source : cp.getState().getSourceMessages()) {
            if (stored.stream().noneMatch(m -> source.id().equals(m.getId()) && "user".equals(m.getRole())
                    && cp.getSessionId().equals(m.getSessionId()) && Objects.equals(source.content(), m.getContent())))
                throw ExecutionCheckpointService.conflict("任务原始消息已更改或删除，禁止继续执行；仍可拒绝审批");
        }
    }
}
