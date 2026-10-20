package com.tradeagent.modules.ai.intent.benchmark;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.common.PageResult;
import com.tradeagent.core.dag.IntentRouter;
import com.tradeagent.core.dag.SemanticRouter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 意图路由 200 题黄金基准全自动评测流水线引擎
 */
@Slf4j
@Service
public class IntentBenchmarkService {

    @Resource
    private SemanticRouter semanticRouter;

    @Resource
    private IntentRouter intentRouter;

    @Resource
    private IntentBenchmarkRecordMapper benchmarkRecordMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 评测并发调度线程池 (8 线程) */
    private final ExecutorService executorService = Executors.newFixedThreadPool(8);

    /** 最新评测报告内存缓存 */
    private volatile IntentBenchmarkReport latestReport;

    /**
     * 运行 200 题意图路由基准自动化评测流水线
     */
    public IntentBenchmarkReport runBenchmark() {
        String benchmarkId = "INTENT_BENCH_" + System.currentTimeMillis();
        log.info("【意图基准评测】启动 200 题全流程跑批评测流水线, ID: {}", benchmarkId);

        List<IntentBenchmarkCase> testCases = IntentBenchmarkDataset.get200BenchmarkCases();
        int total = testCases.size();

        AtomicInteger totalHits = new AtomicInteger(0);
        AtomicInteger chitchatHits = new AtomicInteger(0);
        AtomicInteger chitchatTotal = new AtomicInteger(0);

        AtomicInteger singleDomainHits = new AtomicInteger(0);
        AtomicInteger singleDomainTotal = new AtomicInteger(0);

        AtomicInteger compositeHits = new AtomicInteger(0);
        AtomicInteger compositeTotal = new AtomicInteger(0);

        // 混淆矩阵: ExpectedDomain -> (PredictedDomain -> Count)
        Map<String, ConcurrentHashMap<String, AtomicInteger>> confusionMatrixMap = new ConcurrentHashMap<>();
        String[] allDomains = {"GENERAL", "COMMERCE", "KNOWLEDGE", "PROSPECT", "CRM", "COMPOSITE"};
        for (String exp : allDomains) {
            confusionMatrixMap.put(exp, new ConcurrentHashMap<>());
            for (String pred : allDomains) {
                confusionMatrixMap.get(exp).put(pred, new AtomicInteger(0));
            }
        }

        // 各领域指标统计: Domain -> [total, hits]
        Map<String, AtomicInteger[]> domainStats = new ConcurrentHashMap<>();
        for (String d : allDomains) {
            domainStats.put(d, new AtomicInteger[]{new AtomicInteger(0), new AtomicInteger(0)});
        }

        List<IntentBenchmarkReport.CaseResult> results = Collections.synchronizedList(new ArrayList<>(total));
        List<IntentBenchmarkReport.CaseResult> badCases = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = new ArrayList<>(total);
        for (IntentBenchmarkCase tc : testCases) {
            futures.add(CompletableFuture.runAsync(() -> {
                long t0 = System.currentTimeMillis();
                // 1. 真实语义向量路由 (调用 DashScope text-embedding-v3 计算余弦相似度)
                SemanticRouter.SemanticRoutingResult sr = semanticRouter.route(tc.getQuery(), null);
                long latency = System.currentTimeMillis() - t0;

                String predicted = sr != null ? sr.getDomain() : "GENERAL";
                double topSim = sr != null ? sr.getTopSimilarity() : 0.0;
                String matchedSeed = sr != null ? sr.getMatchedSeedQuestion() : "";

                // 获取激活工具与剪枝率
                List<String> activeTools = new ArrayList<>();
                if (sr != null && sr.getFallbackRouting() != null && sr.getFallbackRouting().activeTools() != null) {
                    activeTools = sr.getFallbackRouting().activeTools().stream()
                            .map(IntentRouter.Tool::id)
                            .collect(Collectors.toList());
                }
                int prunedCount = sr != null && sr.getFallbackRouting() != null ?
                        sr.getFallbackRouting().prunedToolsCount() : 15;
                double pruningRate = Math.round(((double) prunedCount / 15.0) * 1000.0) / 10.0;

                // 2. 命中判决
                boolean isHit = false;
                String expDomain = tc.getExpectedDomain();
                if ("COMPOSITE".equalsIgnoreCase(expDomain)) {
                    // 复合路由：如果判为 COMPOSITE，或激活了多个领域工具 (activeTools >= 2)，均视为复合调度成功
                    isHit = "COMPOSITE".equalsIgnoreCase(predicted) || activeTools.size() >= 2;
                } else if ("GENERAL".equalsIgnoreCase(expDomain)) {
                    // 通用闲聊：预期判为 GENERAL 且激活工具为 0
                    isHit = "GENERAL".equalsIgnoreCase(predicted) && activeTools.isEmpty();
                } else {
                    // 单一垂直领域：严格匹配预测领域
                    isHit = expDomain.equalsIgnoreCase(predicted);
                }

                // 统计计数
                String cat = tc.getCategory();
                if ("CHITCHAT".equalsIgnoreCase(cat)) {
                    chitchatTotal.incrementAndGet();
                    if (isHit) chitchatHits.incrementAndGet();
                } else if ("SINGLE_DOMAIN".equalsIgnoreCase(cat)) {
                    singleDomainTotal.incrementAndGet();
                    if (isHit) singleDomainHits.incrementAndGet();
                } else if ("COMPOSITE".equalsIgnoreCase(cat)) {
                    compositeTotal.incrementAndGet();
                    if (isHit) compositeHits.incrementAndGet();
                }

                if (isHit) totalHits.incrementAndGet();

                // 混淆矩阵记录
                confusionMatrixMap.computeIfAbsent(expDomain, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(predicted, k -> new AtomicInteger(0))
                        .incrementAndGet();

                // 领域统计记录
                AtomicInteger[] stat = domainStats.computeIfAbsent(expDomain, k -> new AtomicInteger[]{new AtomicInteger(0), new AtomicInteger(0)});
                stat[0].incrementAndGet();
                if (isHit) stat[1].incrementAndGet();

                // 记录用例结果
                IntentBenchmarkReport.CaseResult cr = IntentBenchmarkReport.CaseResult.builder()
                        .id(tc.getId())
                        .category(tc.getCategory())
                        .query(tc.getQuery())
                        .expectedDomain(expDomain)
                        .predictedDomain(predicted)
                        .topSimilarity(topSim)
                        .matchedSeed(matchedSeed)
                        .expectedTools(tc.getExpectedTools())
                        .activeTools(activeTools)
                        .pruningRate(pruningRate)
                        .hit(isHit)
                        .latencyMs(latency)
                        .failureReason(!isHit ? "预期领域 " + expDomain + "，实际判为 " + predicted + " (TopSim: " + topSim + ")" : null)
                        .build();

                results.add(cr);
                if (!isHit) {
                    badCases.add(cr);
                }
            }, executorService));
        }

        // 等待所有用例跑批结束
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long totalCostMs = System.currentTimeMillis() - startTime;
        double avgLatency = (double) totalCostMs / Math.max(1, total);

        // 指标计算与百分比格式化
        double overallAcc = Math.round(((double) totalHits.get() / total) * 1000.0) / 10.0;
        double chitchatAcc = Math.round(((double) chitchatHits.get() / Math.max(1, chitchatTotal.get())) * 1000.0) / 10.0;
        double singleDomainAcc = Math.round(((double) singleDomainHits.get() / Math.max(1, singleDomainTotal.get())) * 1000.0) / 10.0;
        double compositeAcc = Math.round(((double) compositeHits.get() / Math.max(1, compositeTotal.get())) * 1000.0) / 10.0;

        double sumPruning = results.stream().mapToDouble(IntentBenchmarkReport.CaseResult::getPruningRate).sum();
        double avgPruning = Math.round((sumPruning / Math.max(1, results.size())) * 10.0) / 10.0;

        // 构建混淆矩阵数据结构
        Map<String, Map<String, Integer>> confusionMatrix = new LinkedHashMap<>();
        for (String exp : allDomains) {
            Map<String, Integer> row = new LinkedHashMap<>();
            for (String pred : allDomains) {
                int count = confusionMatrixMap.getOrDefault(exp, new ConcurrentHashMap<>())
                        .getOrDefault(pred, new AtomicInteger(0)).get();
                row.put(pred, count);
            }
            confusionMatrix.put(exp, row);
        }

        // 构建领域维度指标
        Map<String, IntentBenchmarkReport.DomainMetric> domainMetrics = new LinkedHashMap<>();
        for (String d : allDomains) {
            AtomicInteger[] st = domainStats.get(d);
            int dTotal = st != null ? st[0].get() : 0;
            int dHits = st != null ? st[1].get() : 0;
            double dAcc = dTotal > 0 ? Math.round(((double) dHits / dTotal) * 1000.0) / 10.0 : 0.0;
            domainMetrics.put(d, IntentBenchmarkReport.DomainMetric.builder()
                    .domain(d)
                    .domainName(getDomainDisplayName(d))
                    .total(dTotal)
                    .hits(dHits)
                    .accuracy(dAcc)
                    .build());
        }

        // 排序测试用例结果
        results.sort(Comparator.comparing(IntentBenchmarkReport.CaseResult::getId));

        IntentBenchmarkReport report = IntentBenchmarkReport.builder()
                .benchmarkId(benchmarkId)
                .totalCases(total)
                .hitCount(totalHits.get())
                .overallAccuracy(overallAcc)
                .chitchatAccuracy(chitchatAcc)
                .chitchatTotal(chitchatTotal.get())
                .chitchatHits(chitchatHits.get())
                .singleDomainAccuracy(singleDomainAcc)
                .singleDomainTotal(singleDomainTotal.get())
                .singleDomainHits(singleDomainHits.get())
                .compositeAccuracy(compositeAcc)
                .compositeTotal(compositeTotal.get())
                .compositeHits(compositeHits.get())
                .avgPruningRate(avgPruning)
                .avgLatencyMs(Math.round(avgLatency * 100.0) / 100.0)
                .confusionMatrix(confusionMatrix)
                .domainMetrics(domainMetrics)
                .badCases(badCases.size() > 10 ? badCases.subList(0, 10) : badCases)
                .caseResults(results)
                .build();

        this.latestReport = report;

        // 3. 持久化至 MySQL intent_benchmark_record
        try {
            String reportJson = objectMapper.writeValueAsString(report);
            String cmJson = objectMapper.writeValueAsString(confusionMatrix);

            IntentBenchmarkRecord record = IntentBenchmarkRecord.builder()
                    .id(benchmarkId)
                    .totalCases(total)
                    .overallAccuracy(overallAcc)
                    .chitchatAccuracy(chitchatAcc)
                    .singleDomainAccuracy(singleDomainAcc)
                    .compositeAccuracy(compositeAcc)
                    .avgPruningRate(avgPruning)
                    .avgLatencyMs(Math.round(avgLatency * 100.0) / 100.0)
                    .confusionMatrixJson(cmJson)
                    .reportJson(reportJson)
                    .createTime(LocalDateTime.now())
                    .build();

            benchmarkRecordMapper.insert(record);
            log.info("【意图基准评测持久化】成功入库 MySQL, ID: {}, 准确率: {}%, 平均剪枝率: {}%",
                    record.getId(), record.getOverallAccuracy(), record.getAvgPruningRate());
        } catch (Exception e) {
            log.error("【意图基准评测持久化】写入 MySQL 异常 (不影响返回): {}", e.getMessage(), e);
        }

        return report;
    }

    /**
     * 获取最新一次意图评测报告 (优先内存，次选数据库)
     */
    public IntentBenchmarkReport getLatestReport() {
        if (this.latestReport != null) {
            return this.latestReport;
        }
        try {
            LambdaQueryWrapper<IntentBenchmarkRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByDesc(IntentBenchmarkRecord::getCreateTime).last("LIMIT 1");
            IntentBenchmarkRecord rec = benchmarkRecordMapper.selectOne(wrapper);
            if (rec != null && rec.getReportJson() != null && !rec.getReportJson().isBlank()) {
                IntentBenchmarkReport r = objectMapper.readValue(rec.getReportJson(), IntentBenchmarkReport.class);
                this.latestReport = r;
                return r;
            }
        } catch (Exception e) {
            log.warn("从数据库加载最新意图评测报告失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 分页查询历史意图基准评测记录 (列表排除大字段 reportJson)
     */
    public PageResult<IntentBenchmarkRecord> pageRecords(int pageNum, int pageSize) {
        int current = pageNum > 0 ? pageNum : 1;
        int size = pageSize > 0 ? pageSize : 10;

        Page<IntentBenchmarkRecord> page = new Page<>(current, size);
        LambdaQueryWrapper<IntentBenchmarkRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(IntentBenchmarkRecord.class, info -> !info.getColumn().equals("report_json"));
        wrapper.orderByDesc(IntentBenchmarkRecord::getCreateTime);

        Page<IntentBenchmarkRecord> res = benchmarkRecordMapper.selectPage(page, wrapper);
        return PageResult.of(res.getTotal(), res.getCurrent(), res.getSize(), res.getRecords());
    }

    /**
     * 获取历史单次意图评测详情完整报告
     */
    public IntentBenchmarkReport getDetail(String id) {
        if (id == null || id.isBlank()) return null;
        IntentBenchmarkRecord rec = benchmarkRecordMapper.selectById(id);
        if (rec != null && rec.getReportJson() != null && !rec.getReportJson().isBlank()) {
            try {
                return objectMapper.readValue(rec.getReportJson(), IntentBenchmarkReport.class);
            } catch (Exception e) {
                log.error("反序列化历史意图评测报告失败: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 删除历史意图评测报告
     */
    public boolean deleteRecord(String id) {
        return benchmarkRecordMapper.deleteById(id) > 0;
    }

    private String getDomainDisplayName(String domain) {
        return switch (domain) {
            case "GENERAL" -> "通用问答/闲聊 (0 工具)";
            case "COMMERCE" -> "商贸阶梯核价与海运";
            case "KNOWLEDGE" -> "行业标准与合规 RAG";
            case "PROSPECT" -> "海关提单与全球买家";
            case "CRM" -> "客户商机与信用风控";
            case "COMPOSITE" -> "复合跨领域协同调度";
            default -> domain;
        };
    }
}
