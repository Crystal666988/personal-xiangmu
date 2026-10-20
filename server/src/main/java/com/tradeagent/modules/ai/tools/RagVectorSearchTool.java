package com.tradeagent.modules.ai.tools;

import com.tradeagent.modules.ai.rag.kb.KnowledgeBaseService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实工具：知识库切片检索器 (rag_vector_search)
 * 领域：KNOWLEDGE (知识库)
 * 真实调用后端已建立的倒排索引与 BM25 检索引擎，返回命中切片与相似度得分！
 */
@Component
public class RagVectorSearchTool implements IToolHandler {

    private final KnowledgeBaseService knowledgeBaseService;

    public RagVectorSearchTool(@Lazy KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("query", Map.of("type", "string", "description", "知识库检索提问语句或技术关键词", "required", true));
        schema.put("topK", Map.of("type", "integer", "description", "召回最相关切片数量 (默认 3)", "required", false));
        schema.put("docId", Map.of("type", "string", "description", "指定文档ID (留空则在全量知识库检索)", "required", false));

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("query", "TOPCon 580W 组件质保与衰减率参数");
        example.put("topK", 3);

        return ToolDefinition.builder()
                .id("rag_vector_search")
                .name("工业知识库切片检索器")
                .domain("KNOWLEDGE")
                .description("在离线已切片的 Markdown/PDF 规范文档中，基于倒排索引与 BM25 相似度召回最相关的技术切片")
                .parameterSchema(schema)
                .exampleQuery(example)
                .build();
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String query = params != null && params.get("query") != null ? params.get("query").toString() : "太阳能光伏规范手册";
        int topK = 3;
        if (params != null && params.get("topK") != null) {
            try {
                topK = Integer.parseInt(params.get("topK").toString());
            } catch (Exception ignored) {}
        }
        String docId = params != null && params.get("docId") != null ? params.get("docId").toString() : null;

        // 真实调用知识库切片检索
        List<KnowledgeBaseService.RetrievalCitation> citations = knowledgeBaseService.retrieveCitations(query, docId, topK);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("retrievedCount", citations.size());
        result.put("citations", citations);

        return result;
    }
}
