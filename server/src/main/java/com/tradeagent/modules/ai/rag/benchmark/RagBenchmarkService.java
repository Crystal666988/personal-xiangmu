package com.tradeagent.modules.ai.rag.benchmark;

import com.tradeagent.modules.ai.rag.AstMarkdownChunker;
import com.tradeagent.modules.ai.rag.DocumentChunk;
import com.tradeagent.modules.ai.rag.MarkdownTableProcessor;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 工业级 RAG 200 题黄金评测基准全自动流水线引擎
 * 
 * 核心技术机制：
 * 1. 【真实内存倒排与 BM25 检索】：基于 InMemoryChunkRetriever 对切片构建真实倒排索引并计算 TF-IDF/BM25 权重；
 * 2. 【零 Mock 真实命中判决】：检索器对合成考题执行 Top-4 真实检索，检验是否命中期望切片与关键词；
 * 3. 【真实 Bad-Case 深度溯源】：未命中用例真实捕获排名靠前的无关切片与实际得分，给出 AST 切片与提示词优化策略；
 * 4. 【多线程并发执行】：基于 8 线程并行调度，兼具高吞吐与严谨性。
 */
@Slf4j
@Service
public class RagBenchmarkService {

    @Resource
    private AstMarkdownChunker astMarkdownChunker = new AstMarkdownChunker();

    @Resource
    private MarkdownTableProcessor markdownTableProcessor = new MarkdownTableProcessor();

    @Resource
    private DynamicSyntheticDatasetGenerator dynamicSyntheticDatasetGenerator = new DynamicSyntheticDatasetGenerator();

    /** 评测并发调度线程池 (8 线程) */
    private final ExecutorService benchmarkExecutor = Executors.newFixedThreadPool(8);

    /**
     * 评测用例数据结构
     */
    @Data
    public static class TestCase {
        private String id;
        private String category; // SINGLE_HOP, MULTI_HOP, TABLE_VISION, ADVERSARIAL
        private String categoryName;
        private String question;
        private String expectedKeyword;
        private String expectedChunkId;
        private String groundTruthDocType; // MARKDOWN, TABLE, SCANNED_PDF, NONE
        private boolean isAdversarial;
    }

    /**
     * 系统默认外贸多模态黄金基准文档 (包含高精技术规格、SASO认证、UN38.3、海运集装箱、CIF阶梯报价表、L/C信用证等真实工业切片)
     */
    public static final String GOLDEN_FOREIGN_TRADE_DOC = """
# 工业出海太阳能光伏与外贸供应链标准规范手册

## 第一章：高功率光伏组件电气参数与技术规格
### 1.1 TOPCon 580W 核心电气参数
TOPCon 580W 光伏组件的标准开路电压 Voc 为 51.2V，最大工作功率点电流 Imp 为 13.8A，最大系统电压为 1500V DC。
工作温度范围在 -40℃ 至 +85℃，首年衰减率不超过 1.0%，后续年衰减不超过 0.4%。
产品符合 IEC 61215 以及 IEC 61730 国际双重认证，防护等级达 IP68。

### 1.2 出口沙特 SASO 认证与中东准入标准
出口沙特阿拉伯必须申请 SASO 认证证书，其法定有效期为 1年。
所有组件必须满足 Saber 系统清关审核要求，在高温高湿沙尘暴工况下具备抗 PID 衰减和抗盐雾测试（8级最高等级）。

### 1.3 锂电池储能海运出口要求
锂电池海运出口要求的 UN38.3 测试报告包含哪几项强制试验？必须包含 T1-T8 全部强制性测试项：
高空模拟(T1)、温度试验(T2)、振动试验(T3)、冲击试验(T4)、外部短路(T5)、撞击/挤压(T6)、过度充电(T7)和强制放电(T8)。

## 第二章：外贸海运集装箱与装载规范
### 2.1 海运标准集装箱装载容积
海运集装箱 40HQ 的标准内径容积是多少立方米？标准尺寸为 12.032m × 2.352m × 2.698m，理论内径容积为 68CBM，最大安全载重为 28,500 KGS。
20GP 普柜的内径容积为 33CBM，核定载重为 21,500 KGS。

### 2.2 迪拜杰贝阿里港海运费与阶梯报价
| 采购区间 (套) | FOB 单瓦单价 (USD) | 2000套总货值 (USD) | 推荐集装箱配载 | 即期海运费 (USD) |
| :--- | :--- | :--- | :--- | :--- |
| 500 ~ 1,500 | $0.118 / W | $129,800 | 2 × 40HQ | $4,900 |
| 2,000 以上大单 | $0.109 / W | $119,900 | 3 × 40HQ + 1 × 20GP | $9,680 |
| 5,000 以上战略采购 | $0.102 / W | $112,200 | 8 × 40HQ | $19,600 |

## 第三章：外贸单证审核与结算风控
### 3.1 信用证 (L/C) 装运条款要求
根据国际商会 UCP600 惯例与不可撤销信用证条款，除信用证另有明确规定外，提单出具日与开立日期之间最迟装运日的时间差不晚于21天。
所有单据必须在提单签发后且在信用证有效期内交单至通知行议付。

### 3.2 逆变器产品替代与迭代升级
对于欧洲及中东分布式户用场景，老款 INV-5000 已全量停产，推荐采用替代升级款 INV-Plus。
升级款 INV-Plus 具备组串级 IV 曲线扫描与智能电弧防护 (AFCI) 功能，整机转换效率达 98.6%。

### 3.3 原产地证与自贸协定零关税
货物出口阿联酋与东盟成员国，凭借原产地证FORM E可享受中阿双边自贸协定与零关税待遇，进口关税由 5% 降为 0%。

## 第四章：扫描提单与工业图纸参数
### 4.1 海运提单 Seal No 铅封号
在真实海运提单 (B/L) 中，集装箱箱号 TGHU9082310 对应的铅封号为 SEAL-889102，由船东签发并加盖中海运承运章，签发日期为 2026-08-15。

### 4.2 光伏组件电池片间距参数
根据 TOPCon 无白边工业高精图纸规定，太阳能电池片串联间距（Gap）参数标准为 0.5mm，焊接汇流带拉拔力要求大于等于 2.0N。

### 4.3 装箱单毛净重核算
跨页发票与装箱单（Packing List）合计行的装载重量为：总毛重 G.W. 为 25200KGS，总净重 N.W. 为 24500KGS。
""";

    /**
     * 运行全自动 RAG 200 题基准评测 (默认黄金评测集)
     */
    public RagBenchmarkReport runAutomatedBenchmark() {
        String benchmarkId = "BENCH-" + System.currentTimeMillis();
        log.info("【RAG 自动化评测】启动默认 200 题黄金基准全自动评测流水线, TaskId: {}", benchmarkId);

        // 1. 真实切片默认黄金文档
        List<DocumentChunk> chunks = astMarkdownChunker.split(1001L, GOLDEN_FOREIGN_TRADE_DOC);
        log.info("【RAG 评测】默认黄金文档成功切分出 {} 个真实 AST 增强切片", chunks.size());

        // 2. 构建真实 BM25 内存倒排索引检索器
        InMemoryChunkRetriever retriever = new InMemoryChunkRetriever(chunks);

        // 3. 动态逆向合成 200 道考题
        List<TestCase> testCases = dynamicSyntheticDatasetGenerator.synthesizeFromChunks(chunks, 200);

        // 4. 执行真实检索判定
        return executeBenchmarkSuite(testCases, chunks, retriever, benchmarkId);
    }

    /**
     * 针对任意领域（医疗、法律、金融、IT 手册等）上传的真实文档切片，动态逆向合成测试题并执行评测
     */
    public RagBenchmarkReport runBenchmarkOnCustomChunks(List<DocumentChunk> chunks, int targetCount) {
        String benchmarkId = "DYN-BENCH-" + System.currentTimeMillis();
        log.info("【RAG 自动化评测】针对任意领域真实切片 ({}) 动态合成 {} 道基准测试题, TaskId: {}",
                chunks.size(), targetCount, benchmarkId);

        // 1. 构建针对该文档真实切片的 BM25 检索器
        InMemoryChunkRetriever retriever = new InMemoryChunkRetriever(chunks);

        // 2. 动态逆向出题
        List<TestCase> dynamicCases = dynamicSyntheticDatasetGenerator.synthesizeFromChunks(chunks, targetCount);

        // 3. 执行全流程真实检索评测
        return executeBenchmarkSuite(dynamicCases, chunks, retriever, benchmarkId);
    }

    /**
     * 并发调度并执行评测套件 (全流程真实计算，零硬编码 Mock)
     */
    private RagBenchmarkReport executeBenchmarkSuite(
            List<TestCase> testCases,
            List<DocumentChunk> chunks,
            InMemoryChunkRetriever retriever,
            String benchmarkId
    ) {
        int total = testCases.size();

        AtomicInteger totalHits = new AtomicInteger(0);
        AtomicInteger totalBadCases = new AtomicInteger(0);
        AtomicInteger adversarialRejections = new AtomicInteger(0);
        Map<String, AtomicInteger> categoryHits = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> categoryTotals = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> categoryBadCases = new ConcurrentHashMap<>();
        List<RagBenchmarkReport.BadCaseDetail> sampleBadCases = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();

        // 并发执行测试用例
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (TestCase tc : testCases) {
            categoryTotals.computeIfAbsent(tc.getCategory(), k -> new AtomicInteger(0)).incrementAndGet();
            categoryHits.computeIfAbsent(tc.getCategory(), k -> new AtomicInteger(0));
            categoryBadCases.computeIfAbsent(tc.getCategory(), k -> new AtomicInteger(0));

            futures.add(CompletableFuture.runAsync(() -> {
                evaluateSingleCase(tc, retriever, totalHits, totalBadCases, adversarialRejections,
                        categoryHits, categoryBadCases, sampleBadCases);
            }, benchmarkExecutor));
        }

        // 等待全部用例跑批结束
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long totalCostMs = System.currentTimeMillis() - startTime;
        double avgLatency = (double) totalCostMs / Math.max(1, total);

        int finalHits = totalHits.get();
        int finalBadCases = totalBadCases.get();
        int finalRejections = adversarialRejections.get();

        // 最终指标严格四舍五入
        double recallRate = Math.round(((double) finalHits / total) * 1000.0) / 10.0;
        double badCaseRate = Math.round(((double) finalBadCases / total) * 1000.0) / 10.0;
        int advTotal = categoryTotals.getOrDefault("ADVERSARIAL", new AtomicInteger(1)).get();
        double adversarialRate = Math.round(((double) finalRejections / Math.max(1, advTotal)) * 1000.0) / 10.0;

        Map<String, RagBenchmarkReport.CategoryMetric> metricMap = new HashMap<>();
        for (String cat : categoryTotals.keySet()) {
            int catTotal = categoryTotals.get(cat).get();
            int catHit = categoryHits.get(cat).get();
            int catBad = categoryBadCases.get(cat).get();
            double catRate = Math.round(((double) catHit / Math.max(1, catTotal)) * 1000.0) / 10.0;
            metricMap.put(cat, RagBenchmarkReport.CategoryMetric.builder()
                    .categoryName(getCategoryDisplayName(cat))
                    .total(catTotal)
                    .hits(catHit)
                    .hitRate(catRate)
                    .badCases(catBad)
                    .build());
        }

        // 构建切片预览列表 (向用户透明呈现真实切分出的块)
        List<RagBenchmarkReport.ChunkPreview> chunkPreviews = chunks.stream()
                .limit(10) // 预览前 10 个切片
                .map(c -> {
                    String clean = c.getContent() != null ? c.getContent().replaceAll("[#*`|\r\n]+", " ").trim() : "";
                    String snippet = clean.length() > 60 ? clean.substring(0, 60) + "..." : clean;
                    return RagBenchmarkReport.ChunkPreview.builder()
                            .chunkId(c.getChunkId())
                            .breadcrumb(c.getBreadcrumbPath() != null ? c.getBreadcrumbPath() : "正文")
                            .chunkType(c.getChunkType() != null ? c.getChunkType() : "TEXT")
                            .charLength(c.getContent() != null ? c.getContent().length() : 0)
                            .snippet(snippet)
                            .build();
                })
                .collect(Collectors.toList());

        log.info("【RAG 自动化评测】真实评测完成! 真实召回率(Recall): {}%, 缺陷率(Bad-case): {}%, 对抗拒答率: {}%, 平均耗时: {}ms",
                recallRate, badCaseRate, adversarialRate, String.format("%.2f", avgLatency));

        return RagBenchmarkReport.builder()
                .benchmarkId(benchmarkId)
                .totalChunksIndexed(chunks.size())
                .chunkPreviews(chunkPreviews)
                .totalQuestions(total)
                .hitCount(finalHits)
                .recallRate(recallRate)
                .badCaseCount(finalBadCases)
                .badCaseRate(badCaseRate)
                .adversarialRejectionRate(adversarialRate)
                .avgRetrievalLatencyMs(Math.round(avgLatency * 100.0) / 100.0)
                .categoryMetrics(metricMap)
                .sampleBadCases(sampleBadCases.size() > 8 ? sampleBadCases.subList(0, 8) : sampleBadCases)
                .build();
    }

    /**
     * 单个测试用例的全自动流水线判定 (在真实 BM25 倒排索引中查询)
     */
    private void evaluateSingleCase(
            TestCase tc,
            InMemoryChunkRetriever retriever,
            AtomicInteger totalHits,
            AtomicInteger totalBadCases,
            AtomicInteger adversarialRejections,
            Map<String, AtomicInteger> categoryHits,
            Map<String, AtomicInteger> categoryBadCases,
            List<RagBenchmarkReport.BadCaseDetail> sampleBadCases
    ) {
        // 1. 真实检索 Top-4
        List<InMemoryChunkRetriever.ScoredChunk> retrieved = retriever.retrieve(tc.getQuestion(), 4);

        // 2. 对抗与拒答用例处理 (验证检索器是否未产生虚假高分匹配)
        if (tc.isAdversarial()) {
            double topScore = retrieved.isEmpty() ? 0.0 : retrieved.get(0).getScore();
            // 如果最高匹配得分极低 (< 8.0)，证明知识库没有该领域知识，判定为成功防御拒答
            boolean correctlyRejected = topScore < 8.0;

            if (correctlyRejected) {
                totalHits.incrementAndGet();
                adversarialRejections.incrementAndGet();
                categoryHits.get(tc.getCategory()).incrementAndGet();
            } else {
                totalBadCases.incrementAndGet();
                categoryBadCases.get(tc.getCategory()).incrementAndGet();
                sampleBadCases.add(new RagBenchmarkReport.BadCaseDetail(
                        tc.getId(),
                        tc.getQuestion(),
                        tc.getCategoryName(),
                        "对抗诱导题产生虚假关联匹配 (Top-1 得分: " + topScore + "，命中了切片 [" + retrieved.get(0).getChunk().getChunkId() + "])",
                        "强化负向样本判定阈值（将拒答门禁提升至得分 > 0.6）并强化 Prompt 显式拒答约束"
                ));
            }
            return;
        }

        // 3. 真实知识问答检索测试 (单跳/多跳/表格/视觉)
        boolean isHit = false;
        for (InMemoryChunkRetriever.ScoredChunk sc : retrieved) {
            DocumentChunk c = sc.getChunk();
            if (c == null) continue;
            // 判定准则 A：命中期望的源切片 ID
            if (tc.getExpectedChunkId() != null && tc.getExpectedChunkId().equals(c.getChunkId())) {
                isHit = true;
                break;
            }
            // 判定准则 B：命中期望的实体关键词
            if (tc.getExpectedKeyword() != null && c.getContent() != null && c.getContent().contains(tc.getExpectedKeyword())) {
                isHit = true;
                break;
            }
        }

        if (isHit) {
            totalHits.incrementAndGet();
            categoryHits.get(tc.getCategory()).incrementAndGet();
        } else {
            totalBadCases.incrementAndGet();
            categoryBadCases.get(tc.getCategory()).incrementAndGet();

            String actualRetrievedStr = retrieved.isEmpty() ? "未检索到任何切片" :
                    retrieved.stream().map(s -> s.getChunk().getChunkId() + "(得分:" + s.getScore() + ")").collect(Collectors.joining(", "));

            sampleBadCases.add(new RagBenchmarkReport.BadCaseDetail(
                    tc.getId(),
                    tc.getQuestion(),
                    tc.getCategoryName(),
                    "期望切片 [" + tc.getExpectedChunkId() + "] 实体 [" + tc.getExpectedKeyword() + "] 未能排进 Top-4。实际召回: " + actualRetrievedStr,
                    "建议增加 AST 面包屑标题权重，或对包含密集数据的段落开启自然语言三元组展开"
            ));
        }
    }

    private String getCategoryDisplayName(String cat) {
        return switch (cat) {
            case "SINGLE_HOP" -> "单跳事实检索";
            case "MULTI_HOP" -> "多跳跨文档推理";
            case "TABLE_VISION" -> "复杂表格与视觉降级";
            case "ADVERSARIAL" -> "对抗与拒答白名单";
            default -> cat;
        };
    }
}
