package com.tradeagent.modules.ai.chat;

import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 服务端真实 SSE 流式对话端点（README "SSE 实时流式对话" 的真实实现）。
 *
 * 请求前置条件：用户消息已通过 `POST /api/v1/agent/sessions/{sessionId}/messages` 落库。
 * 事件协议：
 * - `meta`  路由状态、工具遥测摘要、ReAct 步骤骨架（前端时间轴渲染）；
 * - `delta` 文本增量（打字机）；
 * - `error` 中断告警（其后仍会有 done 收尾）；
 * - `done`  终态：助手消息是否落库及其 ID；
 * - 注释帧 `: ping` 每 15s 一次防网关断连。
 * 响应头 `X-Accel-Buffering: no` 穿透 Nginx 缓冲；客户端断开即时取消上游模型订阅止损 Token。
 */
@RestController
@RequestMapping("/api/v1/agent/chat")
public class AgentChatStreamController {

    private final AgentChatStreamService streamService;

    public AgentChatStreamController(AgentChatStreamService streamService) {
        this.streamService = streamService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> stream(
            @RequestParam(value = "tenantId", defaultValue = "1001") String tenantId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("messageId") String messageId,
            @RequestBody(required = false) RoutingModelConfig config) {

        Flux<ServerSentEvent<String>> events = streamService.stream(tenantId, sessionId, messageId, config);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Accel-Buffering", "no");
        headers.add("Cache-Control", "no-cache");
        return ResponseEntity.ok().headers(headers).body(events);
    }
}
