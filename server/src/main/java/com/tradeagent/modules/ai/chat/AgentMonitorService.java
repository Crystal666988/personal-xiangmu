package com.tradeagent.modules.ai.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.chat.AgentMessage;
import com.tradeagent.modules.ai.chat.AgentSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Agent 执行监控服务：为独立「执行监控」页面提供每轮对话的
 * 意图路由分析与工具调用耗时统计。
 *
 * 数据源：agent_message.routing（JSON 元数据，由对话流式链路写入）。
 * 旧数据缺少 routing 字段或分析字段的行会被安全跳过/降级展示。
 */
@Slf4j
@Service
public class AgentMonitorService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AgentMessageMapper messageMapper;
    private final AgentSessionMapper sessionMapper;
    private final ObjectMapper json = new ObjectMapper();

    public AgentMonitorService(AgentMessageMapper messageMapper, AgentSessionMapper sessionMapper) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
    }

    public record TraceRow(String toolId, String toolName, String status, long latencyMs) {}

    public record MonitorRow(String sessionId, String sessionTitle, String messageId, String time,
                             String query, String routingStatus,
                             String domain, String domainName, String reason,
                             int prunedToolsCount, int activeToolsCount,
                             int toolsCalledCount, long totalLatencyMs, List<TraceRow> traces) {}

    public List<MonitorRow> listRecent(String tenantId, int limit) {
        List<AgentSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<AgentSession>()
                .eq(AgentSession::getTenantId, tenantId));
        if (sessions.isEmpty()) return List.of();

        Map<String, String> titles = new HashMap<>();
        sessions.forEach(s -> titles.put(s.getId(), s.getTitle()));

        List<AgentMessage> assistantMessages = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .in(AgentMessage::getSessionId, titles.keySet())
                .eq(AgentMessage::getRole, "assistant")
                .isNotNull(AgentMessage::getRouting)
                .ne(AgentMessage::getRouting, "")
                .orderByDesc(AgentMessage::getCreateTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        if (assistantMessages.isEmpty()) return List.of();

        Map<String, List<AgentMessage>> userMessagesBySession = new HashMap<>();
        messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                        .in(AgentMessage::getSessionId, titles.keySet())
                        .eq(AgentMessage::getRole, "user")
                        .orderByAsc(AgentMessage::getCreateTime))
                .forEach(m -> userMessagesBySession.computeIfAbsent(m.getSessionId(), k -> new ArrayList<>()).add(m));

        List<MonitorRow> rows = new ArrayList<>();
        for (AgentMessage assistant : assistantMessages) {
            String query = findPrecedingQuery(userMessagesBySession.get(assistant.getSessionId()), assistant);
            rows.add(toRow(assistant, titles.get(assistant.getSessionId()), query));
        }
        return rows;
    }

    private MonitorRow toRow(AgentMessage assistant, String sessionTitle, String query) {
        String routingJson = assistant.getRouting();
        String routingStatus = "";
        String domain = "";
        String domainName = "";
        String reason = "";
        int pruned = 0;
        int activeCount = 0;
        int toolsCalled = 0;
        long totalLatency = 0;
        List<TraceRow> traces = new ArrayList<>();

        try {
            JsonNode root = json.readTree(routingJson);
            routingStatus = root.path("routingStatus").asText("");
            JsonNode routingInfo = root.path("routing");
            domain = routingInfo.path("domain").asText("");
            domainName = routingInfo.path("domainName").asText("");
            reason = routingInfo.path("reason").asText("");
            pruned = routingInfo.path("prunedToolsCount").asInt(0);
            activeCount = routingInfo.path("activeToolsCount").asInt(0);
            JsonNode telemetry = root.path("telemetry");
            toolsCalled = telemetry.path("toolsCalledCount").asInt(0);
            totalLatency = telemetry.path("totalLatencyMs").asLong(0);
            for (JsonNode trace : telemetry.path("traces")) {
                traces.add(new TraceRow(
                        trace.path("toolId").asText(""),
                        trace.path("toolName").asText(""),
                        trace.path("status").asText(""),
                        trace.path("latencyMs").asLong(0)));
            }
        } catch (Exception e) {
            log.warn("【执行监控】路由元数据解析跳过: {}", e.getMessage());
        }

        return new MonitorRow(
                assistant.getSessionId(),
                sessionTitle,
                assistant.getId(),
                assistant.getCreateTime() != null ? assistant.getCreateTime().format(TIME_FMT) : "",
                query,
                routingStatus,
                domain, domainName, reason, pruned, activeCount,
                toolsCalled, totalLatency, traces);
    }

    /** 找该助手消息之前最近的一条用户提问。 */
    private String findPrecedingQuery(List<AgentMessage> userMessages, AgentMessage assistant) {
        if (userMessages == null) return "";
        String best = "";
        for (AgentMessage user : userMessages) {
            if (assistant.getCreateTime() != null && user.getCreateTime() != null
                    && user.getCreateTime().isAfter(assistant.getCreateTime())) break;
            best = Objects.toString(user.getContent(), "");
        }
        return best;
    }
}
