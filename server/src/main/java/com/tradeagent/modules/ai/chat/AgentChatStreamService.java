package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.common.TokenEstimator;
import com.tradeagent.core.dag.ReActStep;
import com.tradeagent.modules.ai.modelconfig.TenantModelConfigService;
import com.tradeagent.core.model.ModelConfig;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import com.tradeagent.modules.ai.tools.TurnTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端真实流式对话编排（"收取用户消息 → 自动回复"的完整闭环）。
 *
 * 链路：用户消息已由会话接口落库 → 本服务 prepare() 执行意图路由/整单预检/只读工具执行
 * → 组装三区隔离 Prompt（ZONE1 注入工具执行事实与演示数据纪律）→ 服务端调用大模型流式生成
 * → SSE 推送（meta/delta/done/error + 15s 心跳）→ 助手回复自动落库（含路由与遥测元数据）。
 *
 * 断连止损：客户端断开时 doOnCancel 触发，上游模型订阅被取消停止烧 Token，
 * 已收到的部分内容异步落库，绝不静默丢弃。
 */
@Slf4j
@Service
public class AgentChatStreamService {

    /** 心跳间隔：防网关 504，同时证明连接存活 */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    /** 注入 ZONE1 的工具事实与遥测文本上限 */
    private static final int MAX_TOOL_FACTS_CHARS = 4000;
    /** meta 事件负载上限 */
    private static final int MAX_META_CHARS = 12000;

    private final AgentContextService contextService;
    private final AgentSessionService sessionService;
    private final ChatStreamer streamer;
    private final TenantModelConfigService modelConfigs;
    private final ObjectMapper json;

    public AgentChatStreamService(AgentContextService contextService, AgentSessionService sessionService,
                                  ChatStreamer streamer, TenantModelConfigService modelConfigs, ObjectMapper json) {
        this.contextService = contextService;
        this.sessionService = sessionService;
        this.streamer = streamer;
        this.modelConfigs = modelConfigs;
        this.json = json;
    }

    /**
     * @param tenantId      租户 ID
     * @param sessionId     会话 ID
     * @param messageId     已落库的当前用户消息 ID
     * @param routingConfig 前端传入的模型配置（仅用于本次调用创建后端模型，不落库不下发他人）
     */
    public Flux<ServerSentEvent<String>> stream(String tenantId, String sessionId, String messageId,
                                                RoutingModelConfig routingConfig) {
        return Flux.merge(deferredBody(tenantId, sessionId, messageId, routingConfig), heartbeat())
                .takeUntil(event -> "done".equals(event.event()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ServerSentEvent<String>> deferredBody(String tenantId, String sessionId, String messageId,
                                                       RoutingModelConfig routingConfig) {
        return Flux.defer(() -> {
            // 前端不再携带 API Key：在进入规划管线前解析租户"使用中"的数据库配置
            RoutingModelConfig effectiveConfig = routingConfig != null
                    && routingConfig.getApiKey() != null && !routingConfig.getApiKey().isBlank()
                    ? routingConfig
                    : modelConfigs.resolveActive(tenantId).orElse(null);

            AgentContextService.ContextResult context;
            try {
                context = contextService.prepare(tenantId, sessionId, messageId, effectiveConfig, "continue");
            } catch (Exception e) {
                log.warn("【SSE 对话】prepare 失败: {}", e.getMessage());
                return Flux.just(event("error", "任务准备失败：" + e.getMessage()), earlyDone());
            }

            AtomicBoolean persisted = new AtomicBoolean(false);
            String meta = buildMeta(context);

            // 追问/等待审批：不调用大模型，直接把后端裁决话术作为自动回复流式下发并落库
            if (context.pending() || !context.shouldGenerate()) {
                String directive = Objects.toString(context.assistantDirective(), "请补充明确的任务信息后继续。");
                return Flux.concat(
                        Mono.just(event("meta", meta)),
                        Flux.just(event("delta", directive)),
                        persistAndDone(tenantId, sessionId, directive, context, true, persisted));
            }

            // 正常生成：服务端调用大模型流式输出
            List<Message> prompt = assemblePrompt(context);
            int promptTokensEstimate = 0;
            for (Message message : prompt) {
                promptTokensEstimate += TokenEstimator.estimateTokens(message.getContent());
            }
            log.info("【SSE 对话】本轮 Prompt 消息 {} 条, 估算上下文 Token ≈ {}", prompt.size(), promptTokensEstimate);
            ModelConfig modelConfig;
            try {
                modelConfig = toModelConfig(tenantId, effectiveConfig);
            } catch (Exception e) {
                return Flux.just(event("error", e.getMessage()), earlyDone());
            }

            StringBuilder buffer = new StringBuilder();
            Flux<ServerSentEvent<String>> body = streamer.stream(prompt, modelConfig)
                    .doOnNext(buffer::append)
                    .map(this::deltaEvent)
                    .onErrorResume(e -> {
                        log.warn("【SSE 对话】模型流式输出异常: {}", e.getMessage());
                        return Mono.just(event("error", "模型输出中断：" + e.getMessage()));
                    })
                    .doOnCancel(() -> {
                        log.info("【Token止损防御】客户端断连，上游模型订阅已取消；已收内容 {} 字符异步落库", buffer.length());
                        Mono.fromRunnable(() -> saveOnce(tenantId, sessionId, buffer.toString(), context, false, persisted))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();
                    })
                    .concatWith(Mono.defer(() -> persistAndDone(tenantId, sessionId, buffer.toString(), context, false, persisted)));

            return Flux.concat(Mono.just(event("meta", meta)), body);
        });
    }

    private Flux<ServerSentEvent<String>> heartbeat() {
        return Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<String>builder().comment("ping").build());
    }

    private Mono<ServerSentEvent<String>> persistAndDone(String tenantId, String sessionId, String content,
                                                         AgentContextService.ContextResult context,
                                                         boolean clarification, AtomicBoolean persisted) {
        return Mono.fromCallable(() -> {
            String savedId;
            try {
                savedId = saveOnce(tenantId, sessionId, content, context, clarification, persisted);
            } catch (Exception e) {
                savedId = null;
            }
            return event("done", json.writeValueAsString(Map.of(
                    "saved", savedId != null,
                    "assistantMessageId", Objects.toString(savedId, ""),
                    "routingStatus", Objects.toString(context.routingStatus(), ""))));
        }).onErrorResume(e -> Mono.just(event("done", "{\"saved\":false,\"routingStatus\":\"ERROR\"}")));
    }

    /** 早期失败（prepare/模型配置）也必须以 done 收尾，保证 SSE 流确定终止 */
    private ServerSentEvent<String> earlyDone() {
        return event("done", "{\"saved\":false,\"routingStatus\":\"ERROR\"}");
    }

    /** 幂等落库：正常完成、流中断、断连三条路径只允许保存一次 */
    private String saveOnce(String tenantId, String sessionId, String content, 
                            AgentContextService.ContextResult context,
                            boolean clarification, AtomicBoolean persisted) {
        if (!persisted.compareAndSet(false, true)) return null;
        if (content == null || content.isBlank()) return null;
        try {
            AgentMessage message = AgentMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(content)
                    .thought(buildThought(context, clarification))
                    .thoughtDuration(0)
                    .routing(buildRoutingMetadata(context))
                    .attachment(null)
                    .time(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                    .build();
            AgentMessage saved = sessionService.saveMessage(message);
            log.info("【SSE 对话】助手回复已落库 {}, 会话 {}, 字符数 {}", saved.getId(), sessionId, content.length());
            return saved.getId();
        } catch (Exception e) {
            log.warn("【SSE 对话】助手回复落库失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 以三区隔离 Prompt 为骨架（任务五结构真正上生产）：
     * ZONE1 追加工具执行事实与演示数据纪律；ZONE2 目标锚定；ZONE3 轻量工作区；其后是历史窗口与当前问题。
     */
    private List<Message> assemblePrompt(AgentContextService.ContextResult context) {
        List<Message> messages = new ArrayList<>(context.partitionedPrompt().structuredMessages());
        String toolFacts = buildToolFacts(context);
        if (!toolFacts.isBlank() && !messages.isEmpty() && messages.get(0) instanceof SystemMessage zone1) {
            messages.set(0, new SystemMessage(zone1.getContent() + "\n\n" + toolFacts));
        }
        return messages;
    }

    /** 工具执行事实：后端裁决话术 + 遥测 JSON（截断），并强制 DEMO 数据纪律 */
    private String buildToolFacts(AgentContextService.ContextResult context) {
        try {
            StringBuilder sb = new StringBuilder();
            if (context.assistantDirective() != null && !context.assistantDirective().isBlank()) {
                sb.append(context.assistantDirective().trim()).append('\n');
            }
            TurnTelemetry telemetry = context.telemetry();
            if (telemetry != null && telemetry.getToolsCalledCount() > 0) {
                String telemetryJson = json.writeValueAsString(telemetry);
                if (telemetryJson.length() > MAX_TOOL_FACTS_CHARS) {
                    telemetryJson = telemetryJson.substring(0, MAX_TOOL_FACTS_CHARS) + "...(truncated)";
                }
                sb.append("Executed tool telemetry (real call results): ").append(telemetryJson).append('\n');
            }
            sb.append("DEMO/isLive=false results are static demonstration data, never current verified facts. "
                    + "Never invent prices, freight rates, exchange rates or customs records.");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildMeta(AgentContextService.ContextResult context) {
        try {
            List<Map<String, Object>> traces = new ArrayList<>();
            TurnTelemetry telemetry = context.telemetry();
            if (telemetry != null && telemetry.getTraces() != null) {
                for (var trace : telemetry.getTraces()) {
                    traces.add(Map.of("toolId", Objects.toString(trace.getToolId(), ""),
                            "toolName", Objects.toString(trace.getToolName(), ""),
                            "status", Objects.toString(trace.getStatus(), ""),
                            "latencyMs", trace.getLatencyMs()));
                }
            }
            List<Map<String, Object>> steps = new ArrayList<>();
            if (context.reactSteps() != null) {
                for (ReActStep step : context.reactSteps()) {
                    steps.add(Map.of("stepIndex", step.getStepIndex(),
                            "type", step.getType() != null ? step.getType().name() : "",
                            "toolId", Objects.toString(step.getToolId(), ""),
                            "toolName", Objects.toString(step.getToolName(), ""),
                            "status", Objects.toString(step.getStatus(), ""),
                            "durationMs", step.getDurationMs()));
                }
            }
            String meta = json.writeValueAsString(Map.of(
                    "routingStatus", Objects.toString(context.routingStatus(), ""),
                    "shouldGenerate", context.shouldGenerate(),
                    "pending", context.pending(),
                    "assistantDirective", Objects.toString(context.assistantDirective(), ""),
                    "clarifications", context.clarifications() != null ? context.clarifications() : List.of(),
                    "toolsCalledCount", telemetry != null ? telemetry.getToolsCalledCount() : 0,
                    "totalLatencyMs", telemetry != null ? telemetry.getTotalLatencyMs() : 0,
                    "traces", traces,
                    "reactSteps", steps));
            return meta.length() > MAX_META_CHARS ? meta.substring(0, MAX_META_CHARS) + "...(truncated)" : meta;
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildThought(AgentContextService.ContextResult context, boolean clarification) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧭 【后端自动回复 · 路由与工具遥测】\n");
        sb.append("- 路由状态: ").append(Objects.toString(context.routingStatus(), "")).append('\n');
        TurnTelemetry telemetry = context.telemetry();
        if (telemetry != null && telemetry.getToolsCalledCount() > 0) {
            sb.append("- 已执行工具: ").append(telemetry.getToolsCalledCount())
                    .append(" 个, 总耗时 ").append(telemetry.getTotalLatencyMs()).append(" ms\n");
            if (telemetry.getTraces() != null) {
                for (var trace : telemetry.getTraces()) {
                    String name = Objects.toString(trace.getToolName(), "");
                    String id = Objects.toString(trace.getToolId(), "");
                    sb.append("  * ").append(name.isBlank() ? id : name + " (" + id + ")")
                            .append(" -> ").append(Objects.toString(trace.getStatus(), ""))
                            .append(" (").append(trace.getLatencyMs()).append("ms)\n");
                }
            }
        } else {
            sb.append("- 本轮未调用业务工具\n");
        }
        if (clarification && context.clarifications() != null && !context.clarifications().isEmpty()) {
            sb.append("- 待补充信息:\n");
            for (String question : context.clarifications()) {
                sb.append("  · ").append(question).append('\n');
            }
        }
        return sb.toString();
    }

    /** 会话消息路由元数据：前端历史时间轴可复用（reactSteps 精简为步骤骨架，不含大体积输出） */
    private String buildRoutingMetadata(AgentContextService.ContextResult context) {
        try {
            List<Map<String, Object>> steps = new ArrayList<>();
            if (context.reactSteps() != null) {
                for (ReActStep step : context.reactSteps()) {
                    steps.add(Map.of("stepIndex", step.getStepIndex(),
                            "type", step.getType() != null ? step.getType().name() : "",
                            "toolId", Objects.toString(step.getToolId(), ""),
                            "toolName", Objects.toString(step.getToolName(), ""),
                            "status", Objects.toString(step.getStatus(), ""),
                            "durationMs", step.getDurationMs(),
                            "summary", Objects.toString(step.getSummary(), Objects.toString(step.getThoughtText(), ""))));
                }
            }
            TurnTelemetry telemetry = context.telemetry();
            List<Map<String, Object>> traceRows = new ArrayList<>();
            if (telemetry != null && telemetry.getTraces() != null) {
                for (var trace : telemetry.getTraces()) {
                    traceRows.add(Map.of("toolId", Objects.toString(trace.getToolId(), ""),
                            "toolName", Objects.toString(trace.getToolName(), ""),
                            "status", Objects.toString(trace.getStatus(), ""),
                            "latencyMs", trace.getLatencyMs()));
                }
            }
            return json.writeValueAsString(Map.of(
                    "routingStatus", Objects.toString(context.routingStatus(), ""),
                    "telemetry", Map.of(
                            "toolsCalledCount", telemetry != null ? telemetry.getToolsCalledCount() : 0,
                            "totalLatencyMs", telemetry != null ? telemetry.getTotalLatencyMs() : 0,
                            "traces", traceRows),
                    "reactSteps", steps));
        } catch (Exception e) {
            return null;
        }
    }

    private ModelConfig toModelConfig(String tenantId, RoutingModelConfig routingConfig) {
        RoutingModelConfig effective = routingConfig;
        // 前端不再携带 API Key：未传配置时由服务端解析租户"使用中"的数据库配置
        if (effective == null || effective.getApiKey() == null || effective.getApiKey().isBlank()) {
            effective = modelConfigs.resolveActive(tenantId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "当前租户尚未配置任何模型（请在「AI 模型热插拔中心」新增并启用一份配置）"));
        }
        return ModelConfig.builder()
                .tenantId(Long.valueOf(tenantId))
                .usageType("chat")
                .provider(effective.getProvider() != null && !effective.getProvider().isBlank()
                        ? effective.getProvider() : "openai")
                .baseUrl(effective.getBaseUrl())
                .apiKey(effective.getApiKey())
                .modelName(effective.getModelName())
                .build();
    }

    private static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }

    /** delta 负载 JSON 编码：文本增量可含换行/前导空格，JSON 化后前端解析零歧义 */
    private ServerSentEvent<String> deltaEvent(String chunk) {
        String payload;
        try {
            payload = json.writeValueAsString(chunk);
        } catch (Exception e) {
            payload = "\"" + chunk.replace("\"", "'").replace("\n", " ") + "\"";
        }
        return event("delta", payload);
    }
}
