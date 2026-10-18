package com.tradeagent.modules.ai.rag.kb;

import com.tradeagent.core.common.ApiResponse;
import com.tradeagent.modules.ai.rag.DocumentChunk;
import com.tradeagent.modules.ai.rag.benchmark.RagBenchmarkReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 工业级两阶段知识库 REST API 控制器 (Knowledge Base Controller)
 * 
 * 涵盖：
 * 1. 离线入库管理 (文档列表、上传切片入库、切片透视、文档删除)
 * 2. 在线 RAG 问答 (纯切片倒排检索、大模型结合切片生成、切片引用溯源)
 * 3. 真实大模型 AI 出题评测 (大模型阅读真实切片逆向出题，绝非正则模板，逐题检验召回率)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb")
@CrossOrigin(origins = "*")
@Tag(name = "工业级两阶段知识库与在线问答", description = "提供知识库离线入库管理、切片溯源在线问答与真实大模型 AI 出题评测")
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    /**
     * 1. 获取所有已入库文档列表
     */
    @GetMapping("/documents")
    @Operation(summary = "获取知识库所有已入库文档列表")
    public ApiResponse<List<KnowledgeDocument>> getDocuments() {
        return ApiResponse.success(knowledgeBaseService.getAllDocuments());
    }

    /**
     * 2. 上传文档并执行 AST 结构化切片与离线入库
     */
    @PostMapping("/documents/upload")
    @Operation(summary = "上传文档执行 AST 切片并离线入库")
    public ApiResponse<KnowledgeDocument> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "上传文件不可为空");
        }
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.txt";
        log.info("【API】收到知识库文档上传: {}, 大小: {} bytes", fileName, file.getSize());

        try {
            KnowledgeDocument doc = knowledgeBaseService.ingestDocument(fileName, file.getBytes(), file.getContentType());
            return ApiResponse.success(doc);
        } catch (Exception e) {
            log.error("【API】知识库文档上传入库失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "入库失败: " + e.getMessage());
        }
    }

    /**
     * 3. 查看指定文档的所有真实切片列表 (切片透视)
     */
    @GetMapping("/documents/{docId}/chunks")
    @Operation(summary = "获取指定文档的所有真实切片明细")
    public ApiResponse<List<DocumentChunk>> getDocumentChunks(@PathVariable("docId") String docId) {
        List<DocumentChunk> chunks = knowledgeBaseService.getChunksByDocId(docId);
        return ApiResponse.success(chunks);
    }

    /**
     * 4. 删除指定文档及其所有切片与倒排索引
     */
    @DeleteMapping("/documents/{docId}")
    @Operation(summary = "从知识库中删除文档及切片")
    public ApiResponse<Boolean> deleteDocument(@PathVariable("docId") String docId) {
        boolean success = knowledgeBaseService.deleteDocument(docId);
        return ApiResponse.success(success);
    }

    @Data
    public static class RetrieveRequest {
        private String question;
        private String docId;
        private int topK = 4;
    }

    /**
     * 5. 仅执行切片倒排检索 (返回命中的切片引用)
     */
    @PostMapping("/qa/retrieve")
    @Operation(summary = "执行 BM25 切片检索")
    public ApiResponse<List<KnowledgeBaseService.RetrievalCitation>> retrieveChunks(@RequestBody RetrieveRequest req) {
        List<KnowledgeBaseService.RetrievalCitation> citations =
                knowledgeBaseService.retrieveCitations(req.getQuestion(), req.getDocId(), req.getTopK());
        return ApiResponse.success(citations);
    }

    @Data
    public static class OnlineQaRequest {
        private String question;
        private String docId;
        private int topK = 4;
        private String apiKey;
        private String baseUrl;
        private String modelName;
    }

    /**
     * 6. 阶段二：在线 RAG 问答 (真实切片检索 + 真实大模型生成 + 切片引用溯源)
     */
    @PostMapping("/qa/ask")
    @Operation(summary = "在线 RAG 问答，返回大模型回答与切片引用")
    public ApiResponse<KnowledgeBaseService.OnlineQaResponse> askQuestion(@RequestBody OnlineQaRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            return ApiResponse.error(400, "提问内容不可为空");
        }
        try {
            KnowledgeBaseService.OnlineQaResponse response = knowledgeBaseService.onlineRAGQuery(
                    req.getQuestion(),
                    req.getDocId(),
                    req.getTopK(),
                    req.getApiKey(),
                    req.getBaseUrl(),
                    req.getModelName()
            );
            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("【API】在线 RAG 问答失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "问答生成失败: " + e.getMessage());
        }
    }

    @Data
    public static class AiBenchmarkRequest {
        private String docId;
        private int count = 10;
        private String apiKey;
        private String baseUrl;
        private String modelName;
    }

    /**
     * 7. 阶段三：真实 AI 大模型出题与基准评测 (调用 LLM 深度阅读切片出题，拒绝纯正则模板)
     */
    @PostMapping("/benchmark/ai-run")
    @Operation(summary = "真实 AI 大模型阅读切片并生成考题评测")
    public ApiResponse<RagBenchmarkReport> runAiBenchmark(@RequestBody AiBenchmarkRequest req) {
        log.info("【API】收到真实 AI 大模型出题评测请求, docId: {}, count: {}, model: {}",
                req.getDocId(), req.getCount(), req.getModelName());
        try {
            RagBenchmarkReport report = knowledgeBaseService.runAiSyntheticBenchmark(
                    req.getDocId(),
                    req.getCount(),
                    req.getApiKey(),
                    req.getBaseUrl(),
                    req.getModelName()
            );
            return ApiResponse.success(report);
        } catch (Exception e) {
            log.error("【API】真实 AI 出题评测失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "AI 出题评测失败: " + e.getMessage());
        }
    }

    /**
     * 8. 获取最新一次 AI 评测的持久化报告
     */
    @GetMapping("/benchmark/latest")
    @Operation(summary = "获取最新生成的评测报告")
    public ApiResponse<RagBenchmarkReport> getLatestBenchmark() {
        RagBenchmarkReport report = knowledgeBaseService.getLatestBenchmarkReport();
        return ApiResponse.success(report);
    }
}
