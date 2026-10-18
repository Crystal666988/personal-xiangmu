package com.tradeagent.modules.ai.chat;

import com.tradeagent.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 智能体会话与历史消息 REST 控制器 (持久化至 MySQL 数据库)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/sessions")
@CrossOrigin(origins = "*")
@Tag(name = "AI 智能体会话与消息数据库持久化", description = "提供 MySQL 级多会话管理与消息记录查询、落库服务")
public class AgentSessionController {

    @Resource
    private AgentSessionService sessionService;

    @GetMapping
    @Operation(summary = "获取租户历史会话列表")
    public ApiResponse<List<AgentSession>> listSessions(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId
    ) {
        return ApiResponse.success(sessionService.listSessions(tenantId));
    }

    @PostMapping
    @Operation(summary = "创建或保存会话")
    public ApiResponse<AgentSession> createSession(@RequestBody AgentSession session) {
        return ApiResponse.success(sessionService.saveOrCreateSession(session));
    }

    @PutMapping("/{sessionId}")
    @Operation(summary = "更新会话标题")
    public ApiResponse<Void> updateSessionTitle(
            @PathVariable("sessionId") String sessionId,
            @RequestParam("title") String title
    ) {
        sessionService.updateSessionTitle(sessionId, title);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "删除会话及其历史消息")
    public ApiResponse<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
        sessionService.deleteSession(sessionId);
        return ApiResponse.ok();
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(summary = "获取指定会话的全部历史消息")
    public ApiResponse<List<AgentMessage>> listMessages(@PathVariable("sessionId") String sessionId) {
        return ApiResponse.success(sessionService.listMessages(sessionId));
    }

    @PostMapping("/{sessionId}/messages")
    @Operation(summary = "追加并保存一条对话消息到数据库")
    public ApiResponse<AgentMessage> saveMessage(
            @PathVariable("sessionId") String sessionId,
            @RequestBody AgentMessage message
    ) {
        message.setSessionId(sessionId);
        return ApiResponse.success(sessionService.saveMessage(message));
    }

    @DeleteMapping("/{sessionId}/messages")
    @Operation(summary = "清空会话全部历史消息")
    public ApiResponse<Void> clearMessages(@PathVariable("sessionId") String sessionId) {
        sessionService.clearMessages(sessionId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{sessionId}/messages/{messageId}")
    @Operation(summary = "删除单条对话消息")
    public ApiResponse<Void> deleteMessage(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("messageId") String messageId
    ) {
        sessionService.deleteMessage(sessionId, messageId);
        return ApiResponse.ok();
    }

    @PostMapping("/{sessionId}/messages/batch-delete")
    @Operation(summary = "批量删除消息 (删除一轮对话)")
    public ApiResponse<Void> deleteMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestBody List<String> messageIds
    ) {
        sessionService.deleteMessages(sessionId, messageIds);
        return ApiResponse.ok();
    }
}
