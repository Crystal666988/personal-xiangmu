package com.tradeagent.modules.ai.chat;

import org.springframework.ai.chat.messages.Message;
import com.tradeagent.core.model.ModelConfig;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 服务端流式对话抽象：把组装好的 Spring AI 消息序列交给底层模型，吐出文本增量。
 * 独立成接口是为了离线单元测试可以注入假模型，不触网。
 */
public interface ChatStreamer {

    /**
     * @param messages 完整 Prompt（ZONE1/ZONE2/ZONE3 + 历史窗口 + 当前问题）
     * @param config   租户绑定的模型配置（后端持有，绝不下发前端）
     * @return 按到达顺序的片段流：kind=thought（推理思考链）或 content（正文）
     */
    Flux<ChatStreamPiece> stream(List<Message> messages, ModelConfig config);
}
