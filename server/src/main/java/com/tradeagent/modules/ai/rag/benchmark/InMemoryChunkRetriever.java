package com.tradeagent.modules.ai.rag.benchmark;

import com.tradeagent.modules.ai.rag.DocumentChunk;
import lombok.Builder;
import lombok.Data;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 真实内存 BM25 + 倒排索引检索器
 * 对切片内容与面包屑路径进行分词与 N-Gram 切割，计算真实 BM25 相似度得分并按 Top-K 召回，杜绝任何随机数与硬编码！
 */
public class InMemoryChunkRetriever {

    private final List<DocumentChunk> chunks;
    private final Map<String, List<Posting>> invertedIndex = new HashMap<>();
    private final Map<String, Integer> docLengths = new HashMap<>();
    private double avgDocLength = 0.0;

    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s,，。；;：:!！?？\\(\\)（）\\[\\]【】/\\\\\"'“”‘’\\-_|*#`]+");

    private static final Set<String> STOPWORDS = Set.of(
            "请", "的", "了", "在", "和", "是", "与", "及", "等", "中", "关于", "对应", "具体", "是多少", "数值", "内容", "为", "有", "公司"
    );

    @Data
    @Builder
    public static class Posting {
        private String chunkId;
        private int termFreq;
        private DocumentChunk chunk;
    }

    @Data
    @Builder
    public static class ScoredChunk {
        private DocumentChunk chunk;
        private double score;
    }

    public InMemoryChunkRetriever(List<DocumentChunk> chunks) {
        this.chunks = chunks != null ? chunks : Collections.emptyList();
        buildIndex();
    }

    private void buildIndex() {
        if (chunks.isEmpty()) return;

        long totalLength = 0;
        for (DocumentChunk chunk : chunks) {
            String fullText = (chunk.getBreadcrumbPath() != null ? chunk.getBreadcrumbPath() + " " : "")
                    + (chunk.getContent() != null ? chunk.getContent() : "");
            List<String> terms = tokenize(fullText);
            docLengths.put(chunk.getChunkId(), terms.size());
            totalLength += terms.size();

            // 统计当前块的词频
            Map<String, Integer> tfMap = new HashMap<>();
            for (String term : terms) {
                tfMap.put(term, tfMap.getOrDefault(term, 0) + 1);
            }

            for (Map.Entry<String, Integer> entry : tfMap.entrySet()) {
                invertedIndex.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(Posting.builder()
                                .chunkId(chunk.getChunkId())
                                .termFreq(entry.getValue())
                                .chunk(chunk)
                                .build());
            }
        }
        this.avgDocLength = (double) totalLength / chunks.size();
    }

    /**
     * 执行真实的 BM25 算法检索
     *
     * @param query 查询句子
     * @param topK 召回数量
     * @return 按得分降序排列的切片结果列表
     */
    public List<ScoredChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank() || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTerms = tokenize(query);
        Map<String, Double> chunkScores = new HashMap<>();
        Map<String, DocumentChunk> chunkMap = chunks.stream()
                .collect(Collectors.toMap(DocumentChunk::getChunkId, c -> c, (a, b) -> a));

        int totalDocs = chunks.size();

        for (String term : queryTerms) {
            List<Posting> postings = invertedIndex.get(term);
            if (postings == null || postings.isEmpty()) continue;

            int docFreq = postings.size();
            // 标准 IDF 计算: log((N - n_q + 0.5) / (n_q + 0.5) + 1)
            double idf = Math.log(1.0 + (totalDocs - docFreq + 0.5) / (docFreq + 0.5));

            for (Posting p : postings) {
                int f = p.getTermFreq();
                int docLen = docLengths.getOrDefault(p.getChunkId(), 1);

                // BM25 词频归一化公式
                double tfPart = (f * (K1 + 1.0)) / (f + K1 * (1.0 - B + B * (docLen / Math.max(1.0, avgDocLength))));
                double termScore = idf * tfPart;

                // 面包屑权重加成：如果查询词命中切片的面包屑路径，给予 1.6 倍加权
                if (p.getChunk().getBreadcrumbPath() != null && p.getChunk().getBreadcrumbPath().toLowerCase().contains(term)) {
                    termScore *= 1.6;
                }

                chunkScores.put(p.getChunkId(), chunkScores.getOrDefault(p.getChunkId(), 0.0) + termScore);
            }
        }

        return chunkScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(entry -> ScoredChunk.builder()
                        .chunk(chunkMap.get(entry.getKey()))
                        .score(Math.round(entry.getValue() * 100.0) / 100.0)
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 中英文与 N-Gram 分词器
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        List<String> tokens = new ArrayList<>();
        String[] words = WORD_SPLIT.split(text.toLowerCase());
        for (String w : words) {
            if (w.length() <= 1 || STOPWORDS.contains(w)) continue;
            // 纯英文字符串或纯数字
            if (w.matches("^[a-z0-9.]+$")) {
                tokens.add(w);
            } else {
                // 中文字符串生成词本身 + 2-gram / 3-gram
                if (!STOPWORDS.contains(w)) tokens.add(w);
                for (int i = 0; i < w.length() - 1; i++) {
                    String sub2 = w.substring(i, i + 2);
                    if (!STOPWORDS.contains(sub2)) tokens.add(sub2);
                    if (i < w.length() - 2) {
                        String sub3 = w.substring(i, i + 3);
                        if (!STOPWORDS.contains(sub3)) tokens.add(sub3);
                    }
                }
            }
        }
        return tokens;
    }
}
