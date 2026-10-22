package com.tradeagent.modules.ai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.memory.episodic.EpisodicCase;
import com.tradeagent.modules.ai.rag.kb.DashScopeEmbeddingService;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 历史交易情景向量记忆服务 (Episodic Memory Service)
 *
 * 遵循 Carl (youngyangyang04/llm-master) 核心架构规范：
 * 1. 【高价值案例沉淀】：当外贸交易成交 (DEAL_CLOSED) 或业务员给予好评 (UPVOTE) 时，将输入诉求、解决方案与黄金工具链结构化向量化入库；
 * 2. 【情景向量检索】：新客户输入类似询盘时，基于 1024 维阿里百炼向量进行余弦相似度检索，动态召回最贴近的历史成交案例；
 * 3. 【Few-Shot 动态注入】：将召回的黄金案例格式化为少样本示范，注入 Agent 上下文，指导模型精准选择工具链与生成报价。
 */
@Slf4j
@Service
public class EpisodicMemoryService {

    public static final int EMBEDDING_DIMENSION = 1024;
    public static final double DEFAULT_MIN_SIMILARITY = 0.60;
    public static final int DEFAULT_TOP_K = 3;

    @Value("${pgvector.host:127.0.0.1}")
    private String host = "127.0.0.1";

    @Value("${pgvector.port:5433}")
    private int port = 5433;

    @Value("${pgvector.database:postgres}")
    private String database = "postgres";

    @Value("${pgvector.username:postgres}")
    private String username = "postgres";

    @Value("${pgvector.password:postgres}")
    private String password = "postgres";

    private final DashScopeEmbeddingService embeddingService;
    private final ObjectMapper json;

    /** 堆内情景案例高频缓存与单机降级存储 (caseId -> EpisodicCase) */
    private final Map<String, EpisodicCase> inMemoryCases = new ConcurrentHashMap<>();

    private volatile boolean pgVectorAvailable = false;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EpisodicCaseHit {
        private EpisodicCase episodicCase;
        private double similarity;
    }

    public EpisodicMemoryService() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public EpisodicMemoryService(@Autowired(required = false) DashScopeEmbeddingService embeddingService,
                                 ObjectMapper json) {
        this.embeddingService = embeddingService;
        this.json = json != null ? json : new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        try (Connection conn = getPgConnection();
             Statement stmt = conn.createStatement()) {

            // 1. 确保 vector 扩展可用
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector;");

            // 2. 创建 1024 维情景案例向量表
            String sql = """
                CREATE TABLE IF NOT EXISTS episodic_case_vector (
                    case_id VARCHAR(64) PRIMARY KEY,
                    tenant_id VARCHAR(64) NOT NULL,
                    session_id VARCHAR(64),
                    user_inquiry TEXT NOT NULL,
                    solution_summary TEXT NOT NULL,
                    tool_chain_json TEXT,
                    rating VARCHAR(32),
                    deal_amount DOUBLE PRECISION,
                    embedding vector(1024),
                    metadata_json TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
            """;
            stmt.execute(sql);

            // 3. 构建 HNSW 图索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_episodic_embedding_hnsw ON episodic_case_vector USING hnsw (embedding vector_cosine_ops);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_episodic_tenant ON episodic_case_vector (tenant_id);");

            pgVectorAvailable = true;
            log.info("【EpisodicMemory pgvector 就绪】情景案例向量索引已初始化 (host: {}, port: {})", host, port);
        } catch (Exception e) {
            log.warn("【EpisodicMemory pgvector 未就绪】启用内存级情景向量检索与余弦计算回退模式: {}", e.getMessage());
            pgVectorAvailable = false;
        }
    }

    private Connection getPgConnection() throws SQLException {
        String url = String.format("jdbc:postgresql://%s:%d/%s?ssl=false", host, port, database);
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * 沉淀高价值成功交易或好评案例到情景记忆库
     */
    public EpisodicCase recordSuccessfulCase(String tenantId, String sessionId, String userInquiry,
                                             String solutionSummary, List<String> toolChain,
                                             String rating, Double dealAmount, Map<String, Object> metadata) {
        if (userInquiry == null || userInquiry.isBlank()) {
            throw new IllegalArgumentException("情景案例的用户原始询盘内容不能为空");
        }

        String safeTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : "1001";
        String caseId = UUID.randomUUID().toString().replace("-", "");
        List<String> safeToolChain = toolChain != null ? new ArrayList<>(toolChain) : Collections.emptyList();
        Map<String, Object> safeMetadata = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();
        String safeRating = (rating != null && !rating.isBlank()) ? rating.trim().toUpperCase() : "UPVOTE";

        // 提取 1024 维百炼特征向量 (若离线或异常则自动使用确定性文本哈希伪向量回退)
        float[] vector = extractVector(userInquiry);

        EpisodicCase episodicCase = EpisodicCase.builder()
                .caseId(caseId)
                .tenantId(safeTenant)
                .sessionId(sessionId)
                .userInquiry(userInquiry.trim())
                .solutionSummary(solutionSummary != null ? solutionSummary.trim() : "")
                .toolChain(safeToolChain)
                .rating(safeRating)
                .dealAmount(dealAmount)
                .embedding(vector)
                .metadata(safeMetadata)
                .createdAt(LocalDateTime.now())
                .build();

        // 写入本地内存缓存
        inMemoryCases.put(caseId, episodicCase);

        // 持久化到 pgvector 向量数据库 (若可用)
        if (pgVectorAvailable) {
            persistToPgVector(episodicCase);
        }

        log.info("【情景记忆沉淀成功】已记录案例: caseId={}, tenant={}, rating={}, toolChain={}",
                caseId, safeTenant, safeRating, safeToolChain);
        return episodicCase;
    }

    /**
     * 根据用户新询盘检索最相似的历史高分案例 (Top-K)
     */
    public List<EpisodicCaseHit> recallSimilarCases(String tenantId, String userInquiry, int topK, double minSimilarity) {
        if (userInquiry == null || userInquiry.isBlank()) {
            return Collections.emptyList();
        }

        String safeTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : "1001";
        int limit = topK > 0 ? topK : DEFAULT_TOP_K;
        double threshold = minSimilarity > 0 ? minSimilarity : DEFAULT_MIN_SIMILARITY;

        float[] queryVector = extractVector(userInquiry);

        // 1. 如果 pgvector 可用，优先走数据库 HNSW 索引检索
        if (pgVectorAvailable) {
            try {
                List<EpisodicCaseHit> pgHits = queryFromPgVector(safeTenant, queryVector, limit, threshold);
                if (!pgHits.isEmpty()) {
                    return pgHits;
                }
            } catch (Exception e) {
                log.warn("pgvector 情景检索失败，降级为内存检索: {}", e.getMessage());
            }
        }

        // 2. 内存计算余弦相似度回退模式
        List<EpisodicCaseHit> hits = new ArrayList<>();
        for (EpisodicCase ec : inMemoryCases.values()) {
            if (!safeTenant.equals(ec.getTenantId())) {
                continue;
            }
            double sim = computeCosineSimilarity(queryVector, ec.getEmbedding());
            if (sim >= threshold) {
                hits.add(new EpisodicCaseHit(ec, sim));
            }
        }

        hits.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /**
     * 将检索出的历史高价值情景案例格式化为 Carl 标准 Few-Shot Exemplar 提示词块
     */
    public String formatAsFewShotPrompt(List<EpisodicCaseHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【历史高分成交案例库 (Few-Shot Exemplars - 规划与工具链参考)】\n");
        sb.append("以下为系统沉淀的真实高分成交与业务员好评案例，请参考其业务拆解思路与工具调用链：\n\n");

        int index = 1;
        for (EpisodicCaseHit hit : hits) {
            EpisodicCase c = hit.getEpisodicCase();
            sb.append(String.format("[案例 %d] (语义匹配度: %.1f%%, 评价: %s)\n",
                    index++, hit.getSimilarity() * 100.0, c.getRating()));
            sb.append("- 买家原始诉求: \"").append(c.getUserInquiry()).append("\"\n");
            if (c.getToolChain() != null && !c.getToolChain().isEmpty()) {
                sb.append("- 推荐工具链: ").append(String.join(" -> ", c.getToolChain())).append("\n");
            }
            if (c.getSolutionSummary() != null && !c.getSolutionSummary().isBlank()) {
                sb.append("- 成功方案摘要: ").append(c.getSolutionSummary()).append("\n");
            }
            if (c.getDealAmount() != null && c.getDealAmount() > 0) {
                sb.append(String.format("- 成交金额: $%,.2f\n", c.getDealAmount()));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 文本特征向量提取 (带确定性离线回退保护)
     */
    public float[] extractVector(String text) {
        if (text == null || text.isBlank()) {
            return new float[EMBEDDING_DIMENSION];
        }

        if (embeddingService != null) {
            try {
                float[] vec = embeddingService.embedText(text, null);
                if (vec != null && vec.length == EMBEDDING_DIMENSION) {
                    return vec;
                }
            } catch (Exception e) {
                log.debug("百炼 Embedding 调用异常，使用确定性语义伪向量生成器: {}", e.getMessage());
            }
        }

        return generateDeterministicVector(text);
    }

    /**
     * 确定性伪向量生成器 (针对本地单元测试及离线无 API Key 环境，保证余弦相似度数学逻辑 100% 确定且鲁棒)
     */
    public static float[] generateDeterministicVector(String text) {
        float[] vector = new float[EMBEDDING_DIMENSION];
        if (text == null || text.isBlank()) return vector;

        byte[] bytes = text.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            byte b1 = bytes[i % bytes.length];
            byte b2 = bytes[(i * 7 + 3) % bytes.length];
            vector[i] = (float) Math.sin((b1 ^ b2) * (i + 1));
        }

        // L2 归一化 (Unit Normalization)
        double norm = 0.0;
        for (float v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    /**
     * 余弦相似度计算 (Cosine Similarity)
     */
    public static double computeCosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length == 0 || v2.length == 0 || v1.length != v2.length) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private void persistToPgVector(EpisodicCase c) {
        String sql = """
            INSERT INTO episodic_case_vector
            (case_id, tenant_id, session_id, user_inquiry, solution_summary, tool_chain_json, rating, deal_amount, embedding, metadata_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?)
            ON CONFLICT (case_id) DO UPDATE SET
                solution_summary = EXCLUDED.solution_summary,
                tool_chain_json = EXCLUDED.tool_chain_json,
                rating = EXCLUDED.rating,
                deal_amount = EXCLUDED.deal_amount,
                metadata_json = EXCLUDED.metadata_json;
        """;

        try (Connection conn = getPgConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, c.getCaseId());
            ps.setString(2, c.getTenantId());
            ps.setString(3, c.getSessionId());
            ps.setString(4, c.getUserInquiry());
            ps.setString(5, c.getSolutionSummary());
            ps.setString(6, json.writeValueAsString(c.getToolChain()));
            ps.setString(7, c.getRating());
            if (c.getDealAmount() != null) ps.setDouble(8, c.getDealAmount()); else ps.setNull(8, Types.DOUBLE);
            ps.setString(9, formatVectorSql(c.getEmbedding()));
            ps.setString(10, json.writeValueAsString(c.getMetadata()));
            ps.setTimestamp(11, Timestamp.valueOf(c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.now()));

            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("写入 pgvector episodic_case_vector 失败: {}", e.getMessage());
        }
    }

    private List<EpisodicCaseHit> queryFromPgVector(String tenantId, float[] queryVector, int limit, double threshold) throws Exception {
        String sql = """
            SELECT case_id, tenant_id, session_id, user_inquiry, solution_summary, tool_chain_json, rating, deal_amount, metadata_json, created_at,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM episodic_case_vector
            WHERE tenant_id = ? AND (1 - (embedding <=> ?::vector)) >= ?
            ORDER BY embedding <=> ?::vector ASC
            LIMIT ?;
        """;

        List<EpisodicCaseHit> results = new ArrayList<>();
        String vectorStr = formatVectorSql(queryVector);

        try (Connection conn = getPgConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, vectorStr);
            ps.setString(2, tenantId);
            ps.setString(3, vectorStr);
            ps.setDouble(4, threshold);
            ps.setString(5, vectorStr);
            ps.setInt(6, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> tools = Collections.emptyList();
                    try {
                        tools = json.readValue(rs.getString("tool_chain_json"), new TypeReference<List<String>>() {});
                    } catch (Exception ignored) {}

                    EpisodicCase ec = EpisodicCase.builder()
                            .caseId(rs.getString("case_id"))
                            .tenantId(rs.getString("tenant_id"))
                            .sessionId(rs.getString("session_id"))
                            .userInquiry(rs.getString("user_inquiry"))
                            .solutionSummary(rs.getString("solution_summary"))
                            .toolChain(tools)
                            .rating(rs.getString("rating"))
                            .dealAmount(rs.getObject("deal_amount") != null ? rs.getDouble("deal_amount") : null)
                            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : LocalDateTime.now())
                            .build();

                    double sim = rs.getDouble("similarity");
                    results.add(new EpisodicCaseHit(ec, sim));
                }
            }
        }
        return results;
    }

    private String formatVectorSql(float[] vector) {
        if (vector == null || vector.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    public int getInMemoryCaseCount() {
        return inMemoryCases.size();
    }

    public void clearInMemoryCases() {
        inMemoryCases.clear();
    }
}
