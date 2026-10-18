package com.tradeagent.modules.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.conversation.ConversationContextManager;
import com.tradeagent.core.dag.IntentRouter;
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
    private final IntentRouter router;
    private final ObjectMapper json;
    private final ConversationSummaryService summaries;

    public AgentContextService(AgentSessionMapper sessions, AgentSessionService sessionService,
                               ConversationContextManager contexts, IntentRouter router, ObjectMapper json,
                               ConversationSummaryService summaries) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.contexts = contexts;
        this.router = router;
        this.json = json;
        this.summaries = summaries;
    }

    public record ContextMessage(String role, String content) {}
    public record ContextResult(List<ContextMessage> messages, IntentRouter.Routing routing,
                                int historyMessageCount, String summaryStatus,
                                int summaryMessageCount, int unsummarizedMessageCount) {}

    public ContextResult prepare(String tenantId, String sessionId, String currentMessageId) {
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
        String attachmentName = attachmentField(current, "name");
        var routing = router.route(current.getContent(), attachmentName);
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
        List<Message> prompt = contexts.buildContextPrompt(summary.text(), router.describeTools(routing),
                history.subList(recentStart, history.size()),
                current.getContent()).getInstructions();
        List<ContextMessage> messages = prompt.stream().map(m -> new ContextMessage(
                m instanceof SystemMessage ? "system" : m instanceof AssistantMessage ? "assistant" : "user",
                m.getContent())).toList();
        return new ContextResult(messages, routing, history.size() - recentStart, summary.status(),
                summary.coveredCount(), recentStart - summary.coveredCount());
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
