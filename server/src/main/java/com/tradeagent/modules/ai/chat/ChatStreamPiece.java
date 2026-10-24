package com.tradeagent.modules.ai.chat;

/**
 * 流式输出的一个小片段。
 *
 * @param kind "thought"（推理模型的思考链 reasoning_content）或 "content"（正文）
 * @param text 增量文本
 */
public record ChatStreamPiece(String kind, String text) {
    public static ChatStreamPiece thought(String text) {
        return new ChatStreamPiece("thought", text);
    }

    public static ChatStreamPiece content(String text) {
        return new ChatStreamPiece("content", text);
    }
}
