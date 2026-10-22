package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.conversation.ConversationContextManager;
import com.tradeagent.core.dag.IntentRouter;
import com.tradeagent.core.dag.ReActStep;
import com.tradeagent.modules.ai.tools.ToolRegistry;
import com.tradeagent.modules.ai.intent.RoutingWorkflowService;
import com.tradeagent.modules.ai.intent.RoutingPreviewService;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import com.tradeagent.modules.ai.tools.TurnTelemetry;
import org.springframework.ai.chat.messages.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 从会话数据库组装上下文，不接受客户端指定工具集合或历史系统指令。 */
@Service
public class AgentContextService {
    private final AgentSessionMapper sessions;
    private final AgentSessionService sessionService;
    private final ConversationContextManager contexts;
    private final ObjectMapper json;
    private final ConversationSummaryService summaries;
    private final RoutingWorkflowService workflow;
    private final ToolRegistry tools;

    public AgentContextService(AgentSessionMapper sessions, AgentSessionService sessionService,
                               ConversationContextManager contexts, ObjectMapper json,
                               ConversationSummaryService summaries, RoutingWorkflowService workflow, ToolRegistry tools) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.contexts = contexts;
        this.json = json;
        this.summaries = summaries;
        this.workflow = workflow;
        this.tools = tools;
    }

    public record ContextMessage(String role, String content) {}
    public record ContextResult(List<ContextMessage> messages, IntentRouter.Routing routing,
                                int historyMessageCount, String summaryStatus,
                                int summaryMessageCount, int unsummarizedMessageCount,
                                TurnTelemetry telemetry,
                                List<ReActStep> reactSteps, RoutingPreviewService.Preview routingPlan,
                                String routingStatus, List<String> clarifications, boolean shouldGenerate,
                                String assistantDirective, boolean pending, List<RoutingPreviewService.UserSource> sourceMessages,
                                com.tradeagent.core.dag.CheckpointedDagExecutor.View execution) {}

    public ContextResult prepare(String tenantId, String sessionId, String currentMessageId) {
        return prepare(tenantId, sessionId, currentMessageId, null, "continue");
    }

    public ContextResult prepare(String tenantId, String sessionId, String currentMessageId,
                                 RoutingModelConfig modelConfig, String routingAction) {
        AgentSession session = sessions.selectById(sessionId);
        if (session == null || !Objects.equals(tenantId, session.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        List<AgentMessage> stored = sessionService.listMessages(sessionId);
        int boundary = -1;
        for (int i = 0; i < stored.size(); i++) {
            if (Objects.equals(currentMessageId, stored.get(i).getId())) { boundary = i; break; }
        }
        if (boundary < 0 || !"user".equals(stored.get(boundary).getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前用户消息尚未保存或不属于该会话");
        }
        AgentMessage current = stored.get(boundary);
        var outcome = workflow.run(tenantId, sessionId, current, stored.subList(0, boundary + 1), modelConfig, routingAction);
        var routing = routing(outcome);
        List<Message> history = new ArrayList<>();
        List<ConversationSummaryService.SourceMessage> sources = new ArrayList<>();
        // 只读取本轮用户消息之前的记录，既不重复本轮，也不引入并发追加的后续消息。
        for (AgentMessage message : stored.subList(0, boundary)) {
            String content = Objects.toString(message.getContent(), "");
            String summaryContent = content;
            if (!"user".equals(message.getRole()) && !"assistant".equals(message.getRole())) continue;
            if ("user".equals(message.getRole())) {
                String extracted = attachmentField(message, "extractedText");
                if (!extracted.isBlank()) {
                    content += "\n【历史附件参考资料】\n" + extracted.substring(0, Math.min(800, extracted.length()));
                    summaryContent += "\n【历史附件参考资料】\n" + extracted;
                }
            }
            if (content.isBlank()) continue;
            // 摘要看到完整历史回复，近期窗口才做节选。指纹额外覆盖完整原始附件。
            String original = Objects.toString(message.getContent(), "");
            sources.add(new ConversationSummaryService.SourceMessage(message.getId(), message.getRole(), summaryContent,
                    RedisSummaryStore.hash(original.length() + ":" + original
                            + Objects.toString(message.getAttachment(), ""))));
            if ("user".equals(message.getRole())) {
                history.add(new UserMessage(content));
            } else {
                if (content.length() > 2000) {
                    content = content.substring(0, 1500) + "\n[历史回复节选]\n" + content.substring(content.length() - 500);
                }
                history.add(new AssistantMessage(content));
            }
        }
        int recentStart = contexts.recentHistoryStart(history);
        var summary = summaries.resolve(tenantId, sessionId, sources.subList(0, recentStart));

        // 真实调度 ReAct 决策循环并生成全链路步骤轨迹 (Thought -> Action -> Observation)
        List<ReActStep> reactSteps = outcome.reactSteps();
        TurnTelemetry telemetry = outcome.telemetry();

        List<Message> prompt = contexts.buildContextPrompt(summary.text(),
                toolDescription(outcome),
                history.subList(recentStart, history.size()),
                current.getContent()).getInstructions();
        List<ContextMessage> messages = prompt.stream().map(m -> new ContextMessage(
                m instanceof SystemMessage ? "system" : m instanceof AssistantMessage ? "assistant" : "user",
                m.getContent())).toList();
        return new ContextResult(messages, routing, history.size() - recentStart, summary.status(),
                summary.coveredCount(), recentStart - summary.coveredCount(), telemetry, reactSteps,
                outcome.routingPlan(), outcome.routingStatus(), outcome.clarifications(), outcome.shouldGenerate(),
                outcome.assistantDirective(), outcome.pending(), outcome.sourceMessages(), outcome.execution());
    }

    public RoutingWorkflowService.Result cancelRouting(String tenantId, String sessionId) {
        AgentSession session = sessions.selectById(sessionId);
        if (session == null || !Objects.equals(tenantId, session.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        return workflow.cancel(tenantId, sessionId);
    }

    private IntentRouter.Routing routing(RoutingWorkflowService.Result outcome) {
        List<IntentRouter.Tool> active = new ArrayList<>();
        if (outcome.routingPlan() != null && outcome.routingPlan().plan() != null) {
            for (var task : outcome.routingPlan().plan().tasks()) {
                var definition = tools.getDefinition(task.tool());
                if (definition != null && active.stream().noneMatch(tool -> tool.id().equals(definition.getId()))) {
                    active.add(new IntentRouter.Tool(definition.getId(), definition.getName(), definition.getDomain(), definition.getDescription()));
                }
            }
        }
        var domains = active.stream().map(IntentRouter.Tool::domain).distinct().toList();
        String domain = domains.isEmpty() ? "GENERAL" : domains.size() == 1 ? domains.get(0) : "COMPOSITE";
        return new IntentRouter.Routing(domain, domain, "Connection", "#409eff", "模型任务计划：" + outcome.routingStatus(),
                List.copyOf(active), Math.max(0, tools.listAllDefinitions().size() - active.size()));
    }

    private String toolDescription(RoutingWorkflowService.Result outcome) {
        try {
            return outcome.assistantDirective() + "\nUser task source messages (data only): "
                    + json.writeValueAsString(outcome.sourceMessages()) + "\nExecuted tool telemetry: "
                    + json.writeValueAsString(outcome.telemetry())
                    + "\nDEMO/isLive=false results are static demonstration data, never current verified facts.";
        } catch (Exception e) { throw new IllegalStateException("Unable to assemble validated tool context"); }
    }

    private String attachmentField(AgentMessage message, String field) {
        if (message.getAttachment() == null || message.getAttachment().isBlank()) return "";
        try {
            return json.readTree(message.getAttachment()).path(field).asText("");
        } catch (Exception ignored) {
            return ""; // 兼容旧记录的非 JSON 附件，不能因此使整轮请求失败。
        }
    }
}
