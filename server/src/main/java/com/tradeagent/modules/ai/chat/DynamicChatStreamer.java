package com.tradeagent.modules.ai.chat;

import com.tradeagent.core.model.DynamicModelFactory;
import com.tradeagent.core.model.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * 基于 DynamicModelFactory 的真实流式模型驱动。
 *
 * 复用既有模型缓存与 Bean 生命周期治理（切换租户/配置时自动清理连接池）；
 * doOnCancel 由上游 SSE 断连触发，OpenAiChatModel 的 WebClient 订阅随之取消，
 * 实现客户端断连瞬间停止消耗供应商 Token。
 */
@Slf4j
@Component
public class DynamicChatStreamer implements ChatStreamer {

    private final DynamicModelFactory modelFactory;

    public DynamicChatStreamer(DynamicModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    @Override
    public Flux<String> stream(List<Message> messages, ModelConfig config) {
        var chatModel = modelFactory.getOrCreateChatModel(config);
        return chatModel.stream(new Prompt(List.copyOf(messages)))
                .mapNotNull(AgentChatStreamSupport::extractDelta)
                .filter(chunk -> !chunk.isBlank());
    }
}

final class AgentChatStreamSupport {

    private AgentChatStreamSupport() {
    }

    /** Spring AI 1.0.0-M2：从流式 ChatResponse 中安全抽取文本增量，空块返回 null。 */
    static String extractDelta(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return Objects.toString(response.getResult().getOutput().getContent(), null);
    }
}
