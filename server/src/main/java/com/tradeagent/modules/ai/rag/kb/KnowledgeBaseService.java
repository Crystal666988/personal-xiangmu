package com.tradeagent.modules.ai.rag.kb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.oss.AliyunOssService;
import com.tradeagent.modules.ai.rag.AstMarkdownChunker;
import com.tradeagent.modules.ai.rag.DocumentChunk;
import com.tradeagent.modules.ai.rag.benchmark.InMemoryChunkRetriever;
import com.tradeagent.modules.ai.rag.benchmark.RagBenchmarkReport;
import com.tradeagent.modules.ai.rag.benchmark.RagBenchmarkService;
import com.tradeagent.modules.ai.vision.DocumentParseResult;
import com.tradeagent.modules.ai.vision.PdfVisionFallbackService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工业级两阶段知识库服务引擎 (Two-Stage Knowledge Base Service)
 * 
 * 阶段一：离线入库 (Offline Ingestion & Chunk Store)
 *   - 文档解析 (PDF/MD/TXT/DOCX/OCR)
 *   - AST 结构化切片与 H1~H6 标题面包屑继承
 *   - 内存倒排索引构建与持久化
 * 
 * 阶段二：在线问答 (Online RAG QA)
 *   - 基于 BM25 / 倒排索引召回 Top-K 切片
 *   - 上下文注入并调用真实大模型生成回答
 *   - 切片溯源引用标注 (Citations / Sources)
 * 
 * 阶段三：真实 AI 大模型出题与基准评测 (Real LLM Synthetic Evaluation)
 *   - 绝非静态正则模板！真实调用大模型深度阅读切片并逆向出题
 *   - 真实输出单跳、多跳、表格、对抗四大象限考题与黄金标准答案
 *   - 自动化逐题检索比对 Top-4 召回率与 Bad-Case 溯源
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    @Resource
    private AstMarkdownChunker astMarkdownChunker;

    @Resource
    private PdfVisionFallbackService pdfVisionFallbackService;

    @Resource
    private AliyunOssService aliyunOssService;

    private static final String DEFAULT_DASHSCOPE_KEY = "REDACTED";
    private static final String DEFAULT_DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen-plus";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** 存储所有入库文档: docId -> KnowledgeDocument */
    private final Map<String, KnowledgeDocument> documentRepo = new ConcurrentHashMap<>();

    /** 存储各文档切片: docId -> List<DocumentChunk> */
    private final Map<String, List<DocumentChunk>> documentChunksMap = new ConcurrentHashMap<>();

    /** 全局与单文档检索器缓存 */
    private volatile InMemoryChunkRetriever globalRetriever;
    private final Map<String, InMemoryChunkRetriever> docRetrieverMap = new ConcurrentHashMap<>();

    /** 最近一次评测生成的完整报告 (内存持久化保存，避免刷新或切页面丢失) */
    private volatile RagBenchmarkReport latestBenchmarkReport;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalCitation {
        private String chunkId;
        private String docId;
        private String docName;
        private String breadcrumb;
        private String chunkType;
        private double score;
        private String snippet;
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OnlineQaResponse {
        private String question;
        private String answer;
        private List<RetrievalCitation> citations;
        private long latencyMs;
        private String modelUsed;
    }

    /**
     * 系统启动时初始化默认标准外贸规格知识库文档
     */
    @PostConstruct
    public void initDefaultKnowledgeBase() {
        if (!documentRepo.isEmpty()) return;

        String docId = "doc_default_foreign_trade";
        String content = RagBenchmarkService.GOLDEN_FOREIGN_TRADE_DOC;
        List<DocumentChunk> chunks = astMarkdownChunker.split(1001L, content);

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(docId)
                .name("工业出海太阳能光伏与外贸供应链标准规范手册.md")
                .size(content.getBytes(StandardCharsets.UTF_8).length)
                .type("MARKDOWN")
                .chunkCount(chunks.size())
                .uploadTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .status("INDEXED")
                .ossUrl(null)
                .rawContent(content)
                .build();

        documentRepo.put(docId, doc);
        documentChunksMap.put(docId, chunks);
        rebuildRetrievers();
        log.info("【知识库初始化】已成功载入系统预设外贸标准知识库，包含 {} 个切片", chunks.size());
    }

    /**
     * 获取全部已入库文档列表
     */
    public List<KnowledgeDocument> getAllDocuments() {
        return new ArrayList<>(documentRepo.values());
    }

    /**
     * 获取单个文档信息
     */
    public KnowledgeDocument getDocument(String docId) {
        return documentRepo.get(docId);
    }

    /**
     * 获取指定文档的所有切片
     */
    public List<DocumentChunk> getChunksByDocId(String docId) {
        return documentChunksMap.getOrDefault(docId, Collections.emptyList());
    }

    /**
     * 获取全部知识库的所有切片
     */
    public List<DocumentChunk> getAllChunks() {
        List<DocumentChunk> all = new ArrayList<>();
        for (List<DocumentChunk> list : documentChunksMap.values()) {
            all.addAll(list);
        }
        return all;
    }

    /**
     * 上传并离线入库文档 (支持 PDF / Markdown / TXT / DOCX / JSON 等)
     */
    public KnowledgeDocument ingestDocument(String fileName, byte[] fileBytes, String contentType) throws Exception {
        long startMs = System.currentTimeMillis();
        String docId = "doc_" + System.currentTimeMillis();
        String lowerName = fileName.toLowerCase();

        // 1. 同步上传至阿里云 OSS 归档 (如有配置)
        String ossUrl = null;
        try {
            ossUrl = aliyunOssService.uploadFile(fileName, fileBytes, contentType);
        } catch (Exception e) {
            log.warn("【离线入库】OSS 上传跳过或异常: {}", e.getMessage());
        }

        // 2. 提取原始文本
        String rawContent;
        String docType = "TEXT";
        if (lowerName.endsWith(".pdf")) {
            docType = "PDF";
            DocumentParseResult parseResult = pdfVisionFallbackService.parsePdfPipeline(fileName, fileBytes, true, null);
            rawContent = parseResult.getRawMarkdown();
            if (rawContent == null || rawContent.isBlank()) {
                throw new IllegalArgumentException("PDF 文本层提取为空，无法解析有效文本");
            }
        } else {
            rawContent = new String(fileBytes, StandardCharsets.UTF_8);
            if (lowerName.endsWith(".md")) docType = "MARKDOWN";
            else if (lowerName.endsWith(".json")) docType = "JSON";
        }

        // 3. 执行真实 AST 语法树切片 (保留标题面包屑与表格完整性)
        List<DocumentChunk> chunks = astMarkdownChunker.split(System.currentTimeMillis(), rawContent);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("未能从文档中提取到有效切片，请确认文档内容非空");
        }

        // 4. 持久化到知识库存储并重建倒排索引
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(docId)
                .name(fileName)
                .size(fileBytes.length)
                .type(docType)
                .chunkCount(chunks.size())
                .uploadTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .status("INDEXED")
                .ossUrl(ossUrl)
                .rawContent(rawContent)
                .build();

        documentRepo.put(docId, doc);
        documentChunksMap.put(docId, chunks);
        rebuildRetrievers();

        log.info("【离线入库成功】文档: 《{}》, ID: {}, 切片数: {}, 耗时: {}ms",
                fileName, docId, chunks.size(), System.currentTimeMillis() - startMs);

        return doc;
    }

    /**
     * 删除指定文档及对应切片与索引
     */
    public boolean deleteDocument(String docId) {
        KnowledgeDocument removed = documentRepo.remove(docId);
        documentChunksMap.remove(docId);
        docRetrieverMap.remove(docId);
        rebuildRetrievers();
        log.info("【知识库删除】已删除文档: {}", docId);
        return removed != null;
    }

    /**
     * 重建倒排索引检索器
     */
    private synchronized void rebuildRetrievers() {
        List<DocumentChunk> all = getAllChunks();
        this.globalRetriever = new InMemoryChunkRetriever(all);

        docRetrieverMap.clear();
        for (Map.Entry<String, List<DocumentChunk>> entry : documentChunksMap.entrySet()) {
            docRetrieverMap.put(entry.getKey(), new InMemoryChunkRetriever(entry.getValue()));
        }
    }

    /**
     * 执行切片检索
     */
    public List<RetrievalCitation> retrieveCitations(String query, String docId, int topK) {
        InMemoryChunkRetriever retriever;
        if (docId != null && !docId.isBlank() && !"all".equalsIgnoreCase(docId)) {
            retriever = docRetrieverMap.get(docId);
            if (retriever == null) {
                List<DocumentChunk> chunks = documentChunksMap.get(docId);
                retriever = new InMemoryChunkRetriever(chunks != null ? chunks : Collections.emptyList());
                docRetrieverMap.put(docId, retriever);
            }
        } else {
            if (globalRetriever == null) rebuildRetrievers();
            retriever = globalRetriever;
        }

        List<InMemoryChunkRetriever.ScoredChunk> scoredChunks = retriever.retrieve(query, topK > 0 ? topK : 4);
        List<RetrievalCitation> citations = new ArrayList<>();

        for (InMemoryChunkRetriever.ScoredChunk sc : scoredChunks) {
            DocumentChunk c = sc.getChunk();
            String snippet = c.getContent();
            if (snippet != null && snippet.length() > 220) {
                snippet = snippet.substring(0, 220) + "...";
            }

            // 查找所属文档名
            String docName = "知识库文档";
            for (Map.Entry<String, List<DocumentChunk>> entry : documentChunksMap.entrySet()) {
                if (entry.getValue().stream().anyMatch(chunk -> chunk.getChunkId().equals(c.getChunkId()))) {
                    KnowledgeDocument d = documentRepo.get(entry.getKey());
                    if (d != null) docName = d.getName();
                    break;
                }
            }

            citations.add(RetrievalCitation.builder()
                    .chunkId(c.getChunkId())
                    .docId(docId)
                    .docName(docName)
                    .breadcrumb(c.getBreadcrumbPath() != null ? c.getBreadcrumbPath() : "正文切片")
                    .chunkType(c.getChunkType() != null ? c.getChunkType() : "TEXT")
                    .score(Math.round(sc.getScore() * 100.0) / 100.0)
                    .snippet(snippet)
                    .content(c.getContent())
                    .build());
        }

        return citations;
    }

    /**
     * 阶段二：在线 RAG 问答 (检索真实切片 + 注入真实上下文 + 调用大模型生成)
     */
    public OnlineQaResponse onlineRAGQuery(String question, String docId, int topK,
                                          String apiKey, String baseUrl, String modelName) throws Exception {
        long start = System.currentTimeMillis();

        // 1. 真实倒排切片检索
        List<RetrievalCitation> citations = retrieveCitations(question, docId, topK > 0 ? topK : 4);

        // 2. 组装 RAG 上下文提示词
        StringBuilder contextBuilder = new StringBuilder();
        if (citations.isEmpty()) {
            contextBuilder.append("（注意：在知识库中未检索到高度相关的切片，请根据通用常识谨慎回答，并提示用户可能需要扩充知识库内容）\n");
        } else {
            for (int i = 0; i < citations.size(); i++) {
                RetrievalCitation cite = citations.get(i);
                contextBuilder.append(String.format("【参考切片 %d】（来源: 《%s》 > %s | 切片ID: %s | 相关度: %.2f）\n%s\n\n",
                        i + 1, cite.getDocName(), cite.getBreadcrumb(), cite.getChunkId(), cite.getScore(), cite.getContent()));
            }
        }

        String systemPrompt = """
你是一名严谨的工业知识库问答专家。
请严格基于给定的【参考切片】内容回答用户的提问，做到实事求是、详尽条理、准确严密。
规则：
1. 如果参考切片中包含问题的答案，请结合切片中的数据、技术参数或业务规范条理化作答；
2. 如果参考切片中完全未提及问题相关信息，请明确诚实说明“参考切片中未包含相关信息”，切勿凭空捏造虚假参数；
3. 回答时在关键论断后可标注引用的切片来源。
""";

        String userPrompt = String.format("【检索到的参考切片】：\n%s\n---\n【用户的提问】：\n%s",
                contextBuilder, question);

        // 3. 调用真实大模型生成回答
        String[] resolved = resolveLlmConfig(apiKey, baseUrl, modelName);
        String effectiveApiKey = resolved[0];
        String effectiveBaseUrl = resolved[1];
        String effectiveModel = resolved[2];

        String answer = callLlmChat(effectiveBaseUrl, effectiveApiKey, effectiveModel, systemPrompt, userPrompt);
        long latency = System.currentTimeMillis() - start;

        return OnlineQaResponse.builder()
                .question(question)
                .answer(answer)
                .citations(citations)
                .latencyMs(latency)
                .modelUsed(effectiveModel)
                .build();
    }

    /**
     * 阶段三：真实 AI 大模型阅读切片并逆向出题与基准评测 (Real LLM Synthetic Benchmark)
     * 
     * 1. 抽取文档切片输入给大模型；
     * 2. 大模型深度理解切片，生成四象限考题 (单跳/多跳/表格/对抗) 及黄金标准答案 (Ground Truth)；
     * 3. 逐题在切片索引中检索，计算 Top-4 召回率、Bad-Case、对抗防御率；
     * 4. 返回包含每道 AI 真题、标准答案与命中对比的详尽报告。
     */
    public RagBenchmarkReport runAiSyntheticBenchmark(String docId, int targetCount,
                                                     String apiKey, String baseUrl, String modelName) throws Exception {
        long startMs = System.currentTimeMillis();

        List<DocumentChunk> chunks = (docId != null && !docId.isBlank() && !"all".equalsIgnoreCase(docId))
                ? getChunksByDocId(docId)
                : getAllChunks();

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("当前知识库中没有切片，无法执行评测");
        }

        int count = targetCount > 0 ? targetCount : 10;
        log.info("【真实 AI 出题评测】开始调用大模型阅读切片并生成 {} 道真题...", count);

        // 1. 准备切片样本喂给大模型 (工业级分层抽样：当切片过多时，兼顾表格、各章节首尾，保证全局覆盖)
        List<DocumentChunk> sampledChunks = sampleRepresentativeChunks(chunks, 16);
        StringBuilder chunkContext = new StringBuilder();
        for (DocumentChunk c : sampledChunks) {
            String snippet = c.getContent();
            if (snippet.length() > 350) snippet = snippet.substring(0, 350) + "...";
            chunkContext.append(String.format("### 切片ID: %s\n- 章节路径: %s\n- 类型: %s\n- 内容:\n%s\n\n",
                    c.getChunkId(), c.getBreadcrumbPath(), c.getChunkType(), snippet));
        }

        // 2. 组装 AI 出题提示词 (明确四大象限与 JSON 规范)
        String systemPrompt = """
你是一名权威的 RAG 知识库检索评测专家。你的任务是根据提供的真实知识库切片，生成高质量、高区分度的考题数据集。
必须严格覆盖四大象限题型：
1. SINGLE_HOP (单跳事实)：依据单个切片的明确定义、指标、参数提问；
2. MULTI_HOP (跨段推理)：结合多个切片的事实做对比或综合提问；
3. TABLE_VISION (表格提取)：针对表格中的行、列交叉数据、数值换算提问；
4. ADVERSARIAL (对抗诱导)：提问切片中完全未提及的内容，诱导模型判断边界（此时 expectedChunkId 填 "NONE", isAdversarial 填 true）。

请严格以 JSON 数组格式返回，不要有任何多余问候或解释：
[
  {
    "category": "SINGLE_HOP",
    "categoryName": "单跳事实",
    "question": "具体的考题内容",
    "groundTruthAnswer": "基于切片给出的准确标准参考答案",
    "expectedChunkId": "该题所依据切片的完整ID",
    "expectedKeyword": "核心判据关键词",
    "isAdversarial": false
  }
]
""";

        String userPrompt = String.format("请仔细阅读以下真实切片，生成 %d 道 RAG 评测考题：\n\n%s", count, chunkContext);

        String[] resolved = resolveLlmConfig(apiKey, baseUrl, modelName);
        String effectiveApiKey = resolved[0];
        String effectiveBaseUrl = resolved[1];
        String effectiveModel = resolved[2];

        String llmRawOutput = callLlmChat(effectiveBaseUrl, effectiveApiKey, effectiveModel, systemPrompt, userPrompt);
        log.info("【真实 AI 出题】大模型返回考题响应字符数: {}", llmRawOutput.length());

        // 3. 解析大模型返回的 JSON 考题列表
        List<RagBenchmarkReport.QuestionEvaluationResult> questionResults = parseAiGeneratedQuestions(llmRawOutput);
        if (questionResults.isEmpty()) {
            throw new RuntimeException("大模型未返回符合格式的考题 JSON，请重试！");
        }

        // 4. 构建当前切片检索器，逐题执行真实检索评测
        InMemoryChunkRetriever retriever = new InMemoryChunkRetriever(chunks);
        int hitCount = 0;
        int badCaseCount = 0;
        int adversarialPassed = 0;
        int totalAdversarial = 0;
        List<RagBenchmarkReport.BadCaseDetail> sampleBadCases = new ArrayList<>();

        Map<String, int[]> categoryStats = new HashMap<>(); // category -> [total, hits]

        long retrievalStart = System.currentTimeMillis();
        for (RagBenchmarkReport.QuestionEvaluationResult q : questionResults) {
            String cat = q.getCategory() != null ? q.getCategory() : "SINGLE_HOP";
            categoryStats.putIfAbsent(cat, new int[]{0, 0});
            categoryStats.get(cat)[0]++;

            if (q.isAdversarial()) {
                totalAdversarial++;
                // 对抗题：检验检索得分是否低或不包含目标切片
                List<InMemoryChunkRetriever.ScoredChunk> topResults = retriever.retrieve(q.getQuestion(), 4);
                boolean lowScoreOrEmpty = topResults.isEmpty() || topResults.get(0).getScore() < 2.0;
                if (lowScoreOrEmpty) {
                    adversarialPassed++;
                    q.setHit(true);
                    categoryStats.get(cat)[1]++;
                } else {
                    q.setHit(false);
                    q.setBadCaseReason("对抗性诱导题产生虚假召回，首位切片得分: " + Math.round(topResults.get(0).getScore() * 10.0) / 10.0);
                }
            } else {
                // 正常题：检索 Top-4，检验是否召回期望切片或关键词
                List<InMemoryChunkRetriever.ScoredChunk> topResults = retriever.retrieve(q.getQuestion(), 4);
                List<String> recalledIds = topResults.stream().map(sc -> sc.getChunk().getChunkId()).collect(Collectors.toList());
                q.setRetrievedChunkIds(recalledIds);

                boolean matchedChunk = false;
                if (q.getExpectedChunkId() != null && !q.getExpectedChunkId().isBlank()) {
                    String[] expectedArr = q.getExpectedChunkId().split("[,，\\s]+");
                    for (String exp : expectedArr) {
                        String cleanExp = exp.trim();
                        if (!cleanExp.isBlank() && recalledIds.contains(cleanExp)) {
                            matchedChunk = true;
                            break;
                        }
                    }
                }

                boolean matchedKeyword = false;
                if (!matchedChunk && q.getExpectedKeyword() != null && !q.getExpectedKeyword().isBlank()) {
                    String[] kwArr = q.getExpectedKeyword().split("[|｜,，]+");
                    for (String kw : kwArr) {
                        String cleanKw = kw.trim();
                        if (cleanKw.length() >= 2) {
                            for (InMemoryChunkRetriever.ScoredChunk sc : topResults) {
                                if (sc.getChunk().getContent() != null && sc.getChunk().getContent().contains(cleanKw)) {
                                    matchedKeyword = true;
                                    break;
                                }
                            }
                        }
                        if (matchedKeyword) break;
                    }
                }

                if (matchedChunk || matchedKeyword) {
                    hitCount++;
                    q.setHit(true);
                    categoryStats.get(cat)[1]++;
                    if (!topResults.isEmpty()) {
                        String s = topResults.get(0).getChunk().getContent();
                        q.setRetrievedSnippet(s.length() > 150 ? s.substring(0, 150) + "..." : s);
                    }
                } else {
                    badCaseCount++;
                    q.setHit(false);
                    String reason = "Top-4 未命中期望切片 [" + q.getExpectedChunkId() + "]，实际召回: " + recalledIds;
                    q.setBadCaseReason(reason);
                    if (sampleBadCases.size() < 6) {
                        sampleBadCases.add(RagBenchmarkReport.BadCaseDetail.builder()
                                .questionId(q.getId())
                                .question(q.getQuestion())
                                .category(q.getCategoryName())
                                .reason(reason)
                                .optimizationAction("建议通过跨切片父子面包屑丰富切片上下文，或在检索前引入 HyDE / Query Rewrite 扩展同义词")
                                .build());
                    }
                }
            }
        }
        long retrievalCost = System.currentTimeMillis() - retrievalStart;
        double avgLatency = (double) retrievalCost / questionResults.size();

        // 5. 聚合各象限指标
        Map<String, RagBenchmarkReport.CategoryMetric> metricMap = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : categoryStats.entrySet()) {
            String cat = e.getKey();
            int tot = e.getValue()[0];
            int hits = e.getValue()[1];
            double rate = tot > 0 ? Math.round((double) hits / tot * 1000.0) / 10.0 : 0.0;
            String name = switch (cat) {
                case "SINGLE_HOP" -> "单跳事实事实问答";
                case "MULTI_HOP" -> "跨切片多跳综合推理";
                case "TABLE_VISION" -> "表格与数据提取问答";
                case "ADVERSARIAL" -> "边界对抗诱导与拒答";
                default -> cat;
            };
            metricMap.put(cat, RagBenchmarkReport.CategoryMetric.builder()
                    .categoryName(name)
                    .total(tot)
                    .hits(hits)
                    .hitRate(rate)
                    .badCases(tot - hits)
                    .build());
        }

        double totalRecall = Math.round((double) hitCount / Math.max(1, questionResults.size() - totalAdversarial) * 1000.0) / 10.0;
        double badRate = Math.round((double) badCaseCount / Math.max(1, questionResults.size() - totalAdversarial) * 1000.0) / 10.0;
        double defenseRate = totalAdversarial > 0 ? Math.round((double) adversarialPassed / totalAdversarial * 1000.0) / 10.0 : 100.0;

        // 6. 生成切片前置快照
        List<RagBenchmarkReport.ChunkPreview> chunkPreviews = chunks.stream().limit(8).map(c -> {
            String snip = c.getContent();
            if (snip.length() > 180) snip = snip.substring(0, 180) + "...";
            return RagBenchmarkReport.ChunkPreview.builder()
                    .chunkId(c.getChunkId())
                    .breadcrumb(c.getBreadcrumbPath() != null ? c.getBreadcrumbPath() : "正文")
                    .chunkType(c.getChunkType() != null ? c.getChunkType() : "TEXT")
                    .charLength(c.getContent() != null ? c.getContent().length() : 0)
                    .snippet(snip)
                    .build();
        }).collect(Collectors.toList());

        RagBenchmarkReport report = RagBenchmarkReport.builder()
                .benchmarkId("AI_BENCHMARK_" + System.currentTimeMillis())
                .totalChunksIndexed(chunks.size())
                .chunkPreviews(chunkPreviews)
                .totalQuestions(questionResults.size())
                .hitCount(hitCount)
                .recallRate(totalRecall)
                .badCaseCount(badCaseCount)
                .badCaseRate(badRate)
                .adversarialRejectionRate(defenseRate)
                .avgRetrievalLatencyMs(Math.round(avgLatency * 100.0) / 100.0)
                .categoryMetrics(metricMap)
                .sampleBadCases(sampleBadCases)
                .questionResults(questionResults)
                .build();

        this.latestBenchmarkReport = report;
        return report;
    }

    /**
     * 获取最新生成的评测报告 (如已生成)
     */
    public RagBenchmarkReport getLatestBenchmarkReport() {
        return this.latestBenchmarkReport;
    }

    /**
     * 解析大模型生成的考题 JSON (增强容错：支持去除寒暄前缀、尾注、Markdown代码块、多ID数组)
     */
    private List<RagBenchmarkReport.QuestionEvaluationResult> parseAiGeneratedQuestions(String rawOutput) {
        List<RagBenchmarkReport.QuestionEvaluationResult> list = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return list;

        try {
            String json = rawOutput.trim();

            // 1. 如果大模型输出了 ```json ... ```，先定位代码块
            int codeBlockStart = json.indexOf("```");
            if (codeBlockStart != -1) {
                int contentStart = json.indexOf('\n', codeBlockStart);
                if (contentStart != -1) {
                    int codeBlockEnd = json.lastIndexOf("```");
                    if (codeBlockEnd > contentStart) {
                        json = json.substring(contentStart + 1, codeBlockEnd).trim();
                    }
                }
            }

            // 2. 无论是否有寒暄客套话，智能截取最外层的 JSON 数组 [ ... ]
            int firstBracket = json.indexOf('[');
            int lastBracket = json.lastIndexOf(']');
            if (firstBracket != -1 && lastBracket != -1 && lastBracket > firstBracket) {
                json = json.substring(firstBracket, lastBracket + 1).trim();
            }

            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                int index = 1;
                for (JsonNode item : root) {
                    String cat = item.path("category").asText("SINGLE_HOP");
                    String catName = item.path("categoryName").asText(switch (cat) {
                        case "MULTI_HOP" -> "跨段推理";
                        case "TABLE_VISION" -> "表格提取";
                        case "ADVERSARIAL" -> "对抗诱导";
                        default -> "单跳事实";
                    });

                    boolean isAdv = item.path("isAdversarial").asBoolean(false) || "ADVERSARIAL".equalsIgnoreCase(cat);

                    // 兼容 expectedChunkId 为单一字符串或数组 (如 ["chunk_12", "chunk_14"])
                    String expectedChunkId = "NONE";
                    JsonNode chunkNode = item.path("expectedChunkId");
                    if (chunkNode.isArray()) {
                        List<String> ids = new ArrayList<>();
                        for (JsonNode idNode : chunkNode) {
                            if (!idNode.asText().isBlank()) ids.add(idNode.asText());
                        }
                        expectedChunkId = String.join(", ", ids);
                    } else if (!chunkNode.isMissingNode() && !chunkNode.asText().isBlank()) {
                        expectedChunkId = chunkNode.asText();
                    }

                    list.add(RagBenchmarkReport.QuestionEvaluationResult.builder()
                            .id("AI_Q_" + index++)
                            .question(item.path("question").asText())
                            .category(cat)
                            .categoryName(catName)
                            .groundTruthAnswer(item.path("groundTruthAnswer").asText("待比对切片"))
                            .expectedChunkId(expectedChunkId)
                            .expectedKeyword(item.path("expectedKeyword").asText(""))
                            .isAdversarial(isAdv)
                            .retrievedChunkIds(new ArrayList<>())
                            .isHit(false)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("【AI考题解析异常】JSON 解析失败: {}, 原始输出: {}", e.getMessage(), rawOutput);
        }
        return list;
    }

    /**
     * 底层调用 OpenAI / 阿里云 DashScope 兼容接口
     */
    private String callLlmChat(String baseUrl, String apiKey, String modelName, String systemPrompt, String userPrompt) throws Exception {
        String cleanUrl = baseUrl.trim();
        if (cleanUrl.endsWith("/")) cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
        String endpoint = cleanUrl.endsWith("/chat/completions") ? cleanUrl : cleanUrl + "/chat/completions";

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", modelName);
        payload.put("temperature", 0.3);

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        payload.put("messages", messages);

        String jsonBody = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("大模型调用失败 HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
            throw new RuntimeException("大模型返回内容为空");
        }
        return contentNode.asText();
    }

    /**
     * 智能解析并归一化模型参数，避免厂商模型与 BaseUrl 不匹配 (例如 DeepSeek 调 Qwen 报 404)
     */
    private String[] resolveLlmConfig(String apiKey, String baseUrl, String modelName) {
        String key = (apiKey != null && !apiKey.isBlank() && !apiKey.contains("placeholder")) ? apiKey.trim() : null;
        String url = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : null;
        String model = (modelName != null && !modelName.isBlank()) ? modelName.trim() : null;

        if (key == null) {
            // 用户没有输入有效密钥，统一使用系统内置的 DashScope 通义千问通道
            return new String[]{DEFAULT_DASHSCOPE_KEY, DEFAULT_DASHSCOPE_BASE_URL, DEFAULT_MODEL};
        }

        // 自动适配厂商与模型名
        if (url != null && url.contains("deepseek")) {
            if (model == null || model.toLowerCase().contains("qwen")) {
                model = "deepseek-chat";
            }
        } else if (url != null && (url.contains("dashscope") || url.contains("aliyuncs"))) {
            if (model == null || model.toLowerCase().contains("deepseek")) {
                model = "qwen-plus";
            }
        }
        if (url == null) url = DEFAULT_DASHSCOPE_BASE_URL;
        if (model == null) model = DEFAULT_MODEL;

        return new String[]{key, url, model};
    }

    /**
     * 工业级分层均匀抽样：当切片数量很多（成百上千）时，如何兼顾 Token 上限与出题覆盖率？
     * 1. 优先保留所有表格类型切片（TABLE），因为表格切片信息密度极高，非常适合出数值型考题；
     * 2. 剩余配额采用等步长跨章节抽样（Step Sampling），均匀覆盖文档的前、中、后各个章节。
     */
    private List<DocumentChunk> sampleRepresentativeChunks(List<DocumentChunk> chunks, int maxCount) {
        if (chunks == null || chunks.size() <= maxCount) {
            return chunks != null ? chunks : Collections.emptyList();
        }

        List<DocumentChunk> selected = new ArrayList<>();
        // 1. 优先提取表格切片 (最多占 4 个名额)
        List<DocumentChunk> tableChunks = chunks.stream()
                .filter(c -> "TABLE".equalsIgnoreCase(c.getChunkType()))
                .limit(Math.min(4, maxCount / 2))
                .toList();
        selected.addAll(tableChunks);

        // 2. 剩余切片按跨度步长均匀采样，覆盖整个文档树
        int remainingSlots = maxCount - selected.size();
        List<DocumentChunk> nonTableChunks = chunks.stream()
                .filter(c -> !"TABLE".equalsIgnoreCase(c.getChunkType()))
                .toList();

        if (nonTableChunks.size() <= remainingSlots) {
            selected.addAll(nonTableChunks);
        } else {
            double step = (double) nonTableChunks.size() / remainingSlots;
            for (int i = 0; i < remainingSlots; i++) {
                int index = (int) Math.round(i * step);
                if (index < nonTableChunks.size()) {
                    selected.add(nonTableChunks.get(index));
                }
            }
        }
        return selected;
    }
}
