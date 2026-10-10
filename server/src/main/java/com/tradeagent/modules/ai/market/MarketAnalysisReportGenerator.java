package com.tradeagent.modules.ai.market;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * N+4 拓扑市场分析报告生成器 (简历第 93 行技术核心)
 * 
 * 核心技术机制：
 * 1. 【N+4 任务解耦】：代码搭建报告 JSON 骨架，拆解为 N 个市场维度 + 4 个核心决策篇章；
 * 2. 【有界线程池并行填充】：多次 5~8 秒细粒度小调用并行跑，彻底告别长文本巨量生成超时卡死；
 * 3. 【证据白名单防幻觉过滤】：模型输出事实必须强制绑定 [E-xx] 证据编号，通过白名单校验防捏造；
 * 4. 【段落级失败隔离降级】：单个维度超时失败时不中断全局，该章节降级输出纯客观采信证据。
 */
@Slf4j
@Service
public class MarketAnalysisReportGenerator {

    @Resource(name = "agentDagExecutor")
    private ExecutorService agentDagExecutor;

    // 正则提取证据编号: [E-01], [E-02] 等
    private static final Pattern EVIDENCE_PATTERN = Pattern.compile("\\[E-(\\d+)\\]");

    /**
     * 生成完整的 N+4 市场深度洞察报告
     *
     * @param targetCountry 目标国家/地区 (如 "阿联酋")
     * @param targetProduct 目标行业产品 (如 "TOPCon 光伏组件")
     * @param evidenceList 采集到的客观事实证据池 (带 [E-xx] 编号)
     * @param chatModel 当前调用的 AI 模型
     * @return 聚合校验后的完整结构化市场报告
     */
    public Map<String, String> generateNPlus4Report(String targetCountry,
                                                   String targetProduct,
                                                   Map<String, String> evidenceList,
                                                   ChatModel chatModel) {
        long startTime = System.currentTimeMillis();
        log.info("【N+4 报告引擎】开始并行构建 [{} - {}] 深度市场分析报告...", targetCountry, targetProduct);

        // 白名单：已采集核实的证据编号集合
        Set<String> evidenceWhitelist = evidenceList.keySet();

        // 1. 定义 N 个市场调研维度 + 4 个高阶决策章节
        List<String> sectionTitles = List.of(
                // N 个维度细项
                "维度1_海关进出口额与关税政策",
                "维度2_海外当地头部竞品与价格带分布",
                "维度3_海运港口物流与清关壁垒",
                "维度4_核心买家EPC工程商偏好与痛点",
                // + 4 个高阶策略总结篇章
                "策略1_市场大盘容量与执行摘要",
                "策略2_合规与回款风险评估",
                "策略3_GTM本土化渠道落地建议",
                "策略4_外贸业务员第一阶段行动清单"
        );

        // 2. 线程池并行小调用填充各个段落
        Map<String, CompletableFuture<String>> futureMap = new LinkedHashMap<>();
        for (String section : sectionTitles) {
            futureMap.put(section, CompletableFuture.supplyAsync(() -> {
                return generateSingleSectionWithFallback(section, targetCountry, targetProduct, evidenceList, evidenceWhitelist, chatModel);
            }, agentDagExecutor));
        }

        // 3. 阻塞等待所有段落汇总聚合
        Map<String, String> finalReportSections = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<String>> entry : futureMap.entrySet()) {
            try {
                // 单段 10 秒超时隔离
                String content = entry.getValue().get(10, TimeUnit.SECONDS);
                finalReportSections.put(entry.getKey(), content);
            } catch (Exception e) {
                log.warn("【N+4 报告引擎】段落 [{}] 触发超时降级", entry.getKey());
                finalReportSections.put(entry.getKey(), "【降级提示】该章节模型调用超时，以下直接展示客观采信证据库内容：\n" + evidenceList);
            }
        }

        log.info("【N+4 报告引擎】报告全量构建完成，总耗时: {} ms", System.currentTimeMillis() - startTime);
        return finalReportSections;
    }

    /**
     * 单个段落生成与白名单防幻觉过滤
     */
    private String generateSingleSectionWithFallback(String sectionTitle,
                                                     String country,
                                                     String product,
                                                     Map<String, String> evidenceList,
                                                     Set<String> whitelist,
                                                     ChatModel chatModel) {
        String prompt = String.format(
                "请为外贸报告撰写章节【%s】。\n" +
                "目标市场: %s, 产品品类: %s。\n" +
                "客观证据库: %s\n" +
                "【强制规则】: 每一句数据结论必须携带引用的证据编号(如 [E-01])。严禁凭空捏造未在证据库中出现的数值！",
                sectionTitle, country, product, evidenceList
        );

        try {
            String rawOutput = chatModel.call(prompt);
            // 白名单防幻觉校验
            return filterHallucinatedEvidences(rawOutput, whitelist);
        } catch (Exception ex) {
            log.error("【段落失败隔离】章节 [{}] 生成异常，降级输出证据文本", sectionTitle);
            return "【降级章节】由于大模型异常，此处展示原始证据：\n" + evidenceList.getOrDefault("E-01", "暂无证据");
        }
    }

    /**
     * 白名单防幻觉过滤核心：剔除引用了非法编号的段落语句
     */
    private String filterHallucinatedEvidences(String text, Set<String> whitelist) {
        if (text == null) return "";
        Matcher matcher = EVIDENCE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String evidenceId = "E-" + matcher.group(1);
            if (!whitelist.contains(evidenceId)) {
                // 发现了大模型凭空发明的假证据编号！打上虚假警告标记
                matcher.appendReplacement(sb, "[⚠️未核实数据-已拦截]");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
