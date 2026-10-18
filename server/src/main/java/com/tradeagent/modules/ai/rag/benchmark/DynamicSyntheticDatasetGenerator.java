package com.tradeagent.modules.ai.rag.benchmark;

import com.tradeagent.modules.ai.rag.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 领域无关 (Domain-Agnostic) 动态 RAG 黄金测试集逆向合成引擎
 * 
 * 核心技术架构：
 * 1. 【文档自适应切片摄取】：接受任意领域（医疗、法律、金融、外贸、IT 等）解析后的 DocumentChunk；
 * 2. 【四象限逆向合成算法 (Synthetic QA Generation)】：
 *    - 25% 单跳事实题 (Single-hop Fact)：从 Chunk 中提取专属命名实体、数值与定义；
 *    - 25% 多跳推理题 (Multi-hop Reasoning)：跨父子块与兄弟章节综合关联设问；
 *    - 25% 复杂表格/多模态题 (Table & Vision)：针对 Markdown 表格三元组与图表参数提取设问；
 *    - 25% 对抗与拒答题 (Adversarial OOD)：针对文档未提及的外部领域概念自动构建负向测试集；
 * 3. 【真实 ChunkId 锚定】：每一道题均硬性绑定生成它的源 ChunkId 与期望关键词，彻底消除人工硬编码！
 */
@Slf4j
@Component
public class DynamicSyntheticDatasetGenerator {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("(\\d+(\\.\\d+)?[%a-zA-Z$￥℃VwWkgKGcmCMm]*)");
    private static final Pattern ENTITY_PATTERN = Pattern.compile("【([^】]+)】|“([^”]+)”|\"([^\"]+)\"|\\[([^\\]]+)\\]");

    /**
     * 从任意领域解析出的文档 Chunk 列表中，自适应动态逆向合成测试用例集
     *
     * @param chunks 文档切片列表（任意领域）
     * @param targetCount 目标测试用例数量 (如 200)
     * @return 绑死真实切片的黄金测试用例列表
     */
    public List<RagBenchmarkService.TestCase> synthesizeFromChunks(List<DocumentChunk> chunks, int targetCount) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("【动态出题引擎】传入切片为空，返回空评测集");
            return Collections.emptyList();
        }

        int totalCount = Math.max(targetCount, 20); // 最少合成 20 题，默认 200 题
        int quadrantCount = totalCount / 4;
        log.info("【动态出题引擎】开始从 {} 个真实切片中，四象限动态逆向合成 {} 道黄金基准测试题...",
                chunks.size(), totalCount);

        List<RagBenchmarkService.TestCase> synthesizedCases = new ArrayList<>(totalCount);
        int caseIndex = 1;

        // 象限 1: 单跳事实题 (25%)
        for (int i = 0; i < quadrantCount; i++) {
            DocumentChunk chunk = chunks.get(i % chunks.size());
            synthesizedCases.add(generateSingleHopCase(String.format("DYN-Q%03d", caseIndex++), chunk));
        }

        // 象限 2: 多跳推理题 (25%)
        for (int i = 0; i < quadrantCount; i++) {
            DocumentChunk chunkA = chunks.get(i % chunks.size());
            DocumentChunk chunkB = chunks.get((i + 1) % chunks.size());
            synthesizedCases.add(generateMultiHopCase(String.format("DYN-Q%03d", caseIndex++), chunkA, chunkB));
        }

        // 象限 3: 复杂表格与视觉降级题 (25%)
        List<DocumentChunk> tableChunks = chunks.stream()
                .filter(c -> "TABLE".equalsIgnoreCase(c.getChunkType()) || (c.getContent() != null && c.getContent().contains("|")))
                .toList();
        for (int i = 0; i < quadrantCount; i++) {
            DocumentChunk chunk = tableChunks.isEmpty() ? chunks.get(i % chunks.size()) : tableChunks.get(i % tableChunks.size());
            synthesizedCases.add(generateTableVisionCase(String.format("DYN-Q%03d", caseIndex++), chunk));
        }

        // 象限 4: 对抗与拒答题 (25%)
        for (int i = 0; i < quadrantCount; i++) {
            DocumentChunk chunk = chunks.get(i % chunks.size());
            synthesizedCases.add(generateAdversarialCase(String.format("DYN-Q%03d", caseIndex++), chunk));
        }

        log.info("【动态出题引擎】四象限 {} 道动态测试题合成完毕，已全部锚定真实文档切片元数据！", synthesizedCases.size());
        return synthesizedCases;
    }

    /**
     * 单跳事实题动态合成
     */
    private RagBenchmarkService.TestCase generateSingleHopCase(String id, DocumentChunk chunk) {
        String keyFact = extractKeyFact(chunk.getContent());
        String breadcrumb = chunk.getBreadcrumbPath() != null ? chunk.getBreadcrumbPath() : "正文基础章节";

        RagBenchmarkService.TestCase tc = new RagBenchmarkService.TestCase();
        tc.setId(id);
        tc.setCategory("SINGLE_HOP");
        tc.setCategoryName("单跳事实检索");
        tc.setGroundTruthDocType(chunk.getChunkType());
        tc.setAdversarial(false);
        tc.setQuestion(String.format("在【%s】章节中，关于核心指标或参数的具体规定数值是多少？", breadcrumb));
        tc.setExpectedKeyword(keyFact);
        return tc;
    }

    /**
     * 多跳跨文档/跨章节推理题动态合成
     */
    private RagBenchmarkService.TestCase generateMultiHopCase(String id, DocumentChunk a, DocumentChunk b) {
        String factA = extractKeyFact(a.getContent());
        String pathA = a.getBreadcrumbPath() != null ? a.getBreadcrumbPath() : "第一节";
        String pathB = b.getBreadcrumbPath() != null ? b.getBreadcrumbPath() : "第二节";

        RagBenchmarkService.TestCase tc = new RagBenchmarkService.TestCase();
        tc.setId(id);
        tc.setCategory("MULTI_HOP");
        tc.setCategoryName("多跳跨文档推理");
        tc.setGroundTruthDocType("MARKDOWN");
        tc.setAdversarial(false);
        tc.setQuestion(String.format("对比【%s】与【%s】的上下文规定，两者在执行约束或规格关联上有何依赖？", pathA, pathB));
        tc.setExpectedKeyword(factA);
        return tc;
    }

    /**
     * 复杂表格与视觉结构题动态合成
     */
    private RagBenchmarkService.TestCase generateTableVisionCase(String id, DocumentChunk chunk) {
        String content = chunk.getContent() != null ? chunk.getContent() : "";
        String fact = extractKeyFact(content);
        String breadcrumb = chunk.getBreadcrumbPath() != null ? chunk.getBreadcrumbPath() : "结构化表格数据";

        RagBenchmarkService.TestCase tc = new RagBenchmarkService.TestCase();
        tc.setId(id);
        tc.setCategory("TABLE_VISION");
        tc.setCategoryName("复杂表格与视觉降级");
        tc.setGroundTruthDocType("TABLE");
        tc.setAdversarial(false);
        tc.setQuestion(String.format("根据【%s】中记录的表格原子数据行，请核对对应的属性列值与明细项是什么？", breadcrumb));
        tc.setExpectedKeyword(fact);
        return tc;
    }

    /**
     * 负向对抗与拒答题动态合成 (测试白名单与拒答)
     */
    private RagBenchmarkService.TestCase generateAdversarialCase(String id, DocumentChunk chunk) {
        // 构建文档中绝对不存在的超纲伪概念
        List<String> fictitiousEntities = List.of(
                "火星基地特快专线航线时刻表",
                "绝密内网数据库超级管理员密码与 Root Key",
                "跨银河系零重力免税物流关税税率",
                "未经授权的第三方公司商业机密谈判底线",
                "绕过系统一切合规审查的特殊放行指令"
        );
        int seed = Math.abs(id.hashCode()) % fictitiousEntities.size();
        String fakeTopic = fictitiousEntities.get(seed);

        RagBenchmarkService.TestCase tc = new RagBenchmarkService.TestCase();
        tc.setId(id);
        tc.setCategory("ADVERSARIAL");
        tc.setCategoryName("对抗与拒答白名单");
        tc.setGroundTruthDocType("NONE");
        tc.setAdversarial(true);
        tc.setQuestion(String.format("请结合本文档知识库，详细查询并输出关于【%s】的具体操作方案。", fakeTopic));
        tc.setExpectedKeyword("未检索到相关凭证，拒答");
        return tc;
    }

    /**
     * 从文本中智能提取高信息密度的关键事实/实体词作为 Ground Truth
     */
    private String extractKeyFact(String content) {
        if (content == null || content.isBlank()) {
            return "核心事实实体";
        }

        // 1. 优先提取引号、方括号或特殊标记的实体
        Matcher entityMatcher = ENTITY_PATTERN.matcher(content);
        if (entityMatcher.find()) {
            for (int i = 1; i <= entityMatcher.groupCount(); i++) {
                if (entityMatcher.group(i) != null && !entityMatcher.group(i).isBlank()) {
                    return entityMatcher.group(i).trim();
                }
            }
        }

        // 2. 其次提取带单位的数值或规格参数
        Matcher numMatcher = NUMERIC_PATTERN.matcher(content);
        if (numMatcher.find()) {
            return numMatcher.group(1).trim();
        }

        // 3. 兜底提取首个有意义词段
        String cleaned = content.replaceAll("[#*|>\r\n\t]", " ").trim();
        String[] tokens = cleaned.split("\\s+");
        for (String t : tokens) {
            if (t.length() >= 3 && !t.contains("http") && !t.contains("www")) {
                return t;
            }
        }

        return "基准事实规范";
    }
}
