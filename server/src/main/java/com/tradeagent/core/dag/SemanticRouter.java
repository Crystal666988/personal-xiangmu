package com.tradeagent.core.dag;

import com.tradeagent.modules.ai.rag.kb.DashScopeEmbeddingService;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实语义向量路由器 (Semantic Vector Router)
 * 彻底告别单纯的静态正则匹配！
 *
 * P2 定位：服务于【意图 200 题评测】与【/agent/tools/semantic-route 调试接口】，
 * 不在生产聊天主路径上（聊天由 LLM 规划管线 RoutingWorkflowService 承担）。
 *
 * 1. 预置 6 大领域的标准业务种子提问（涵盖通用闲聊、商业报价、知识库、客户背调、CRM销售跟进、视觉单据）；
 * 2. 系统启动时通过阿里百炼 text-embedding-v3 提取 1024 维稠密特征向量并持久化在向量索引表中；
 * 3. 运行时对用户任意自然语言提问提取 Embedding，计算余弦相似度 (Cosine Distance)，实现真正的语义命中！
 */
@Slf4j
@Component
public class SemanticRouter {

    private final DashScopeEmbeddingService embeddingService;
    private final IntentRouter regexRouter;

    /** 领域 -> 种子问题列表 (覆盖 6 大领域核心工业场景) */
    private static final Map<ToolDomain, List<String>> SEED_QUESTIONS = Map.of(
            ToolDomain.GENERAL, List.of(
                    "你好，请问你是谁？你能帮我做什么？",
                    "早上好，今天天气怎么样？会下雨吗？",
                    "给我讲一个关于程序员或职场的搞笑段子",
                    "帮我写一首关于秋天金黄麦浪的七言绝句诗歌",
                    "用 Java 写一个快速排序的实现代码",
                    "番茄炒蛋到底是先放番茄还是先放鸡蛋？",
                    "帮我拟定一份减脂期一周健康食谱建议"
            ),
            ToolDomain.COMMERCE, List.of(
                    "请核算 2000 套 580W 组件发往汉堡的 CIF 价格与海运费",
                    "现在的美元对离岸人民币汇率是多少？今日汇率牌价查询",
                    "采购 5000 片单晶硅电池板能给多少大单阶梯折扣？单瓦价格核算",
                    "帮我生成一份形式发票 PI 商务订单单据",
                    "请问集装箱 40HQ 和 20GP 到中东迪拜杰贝阿里港口的运费是多少？",
                    "把 125,000 美元货值按照最新汇率折算成人民币是多少元？",
                    "外贸 FOB 与 CIF 价格测算，集装箱装柜配载方案"
            ),
            ToolDomain.KNOWLEDGE, List.of(
                    "TOPCon 580W 组件首年和逐年线性衰减率参数是多少？开路电压 Voc 标准",
                    "出口沙特阿拉伯必须办理什么证书？SASO 认证法定有效期与 Saber 清关",
                    "锂电池储能海运出口要求的 UN38.3 报告包含哪八项强制试验？",
                    "根据国际商会 UCP600 惯例，信用证提单签发后最迟交单天数与软条款防范",
                    "海运集装箱 40HQ 的标准内径尺寸和有效容积是多少立方米？20GP 载重",
                    "货物出口东盟国家，原产地证 FORM E 能减免多少关税？自贸协定",
                    "逆变器 AFCI 智能电弧防护与 IEC 61215 国际标准测试规范"
            ),
            ToolDomain.PROSPECT, List.of(
                    "查询海外采购商 Al-Futtaim 的海关真实提单进出口记录与 HS 编码",
                    "查询海关 HS 编码 8541.4300 在荷兰鹿特丹的进口采购商名单",
                    "近半年在中东迪拜采购太阳能组件的核心买家名单与提单数据",
                    "分析海外买家近两年的海运采购量趋势、采购周期与供应商集中度",
                    "通过 HS Code 检索港口清关提单，挖掘高净值活跃买家及联系人",
                    "在地图上反查采购商的注册地址，并对海外买家进行企业资质背调"
            ),
            ToolDomain.CRM, List.of(
                    "查看客户 ACME Solar 在 CRM 系统中的历史跟进记录与跟进阶段",
                    "该客户付款方式提出 OA 90 天，评估信用评级与授信额度",
                    "帮我拟写一封针对海外买家的展会商务约见与开发信邮件",
                    "在 CRM 中新建一条客户商机，并将状态推进至商务谈判阶段",
                    "为销售代表创建下周二下午的客户跟进日程与催促定金任务",
                    "记录客户反馈与客诉工单，更新客户画像档案标签"
            ),
            ToolDomain.VISION, List.of(
                    "请识别这张装箱单发票截图中的明细数据",
                    "看下图片中的图纸表格要素并抽取参数",
                    "核对提单上的印章真伪与报关单编号"
            )
    );

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeedEmbedding {
        private String text;
        private ToolDomain domain;
        private float[] vector;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SemanticRoutingResult {
        private String domain;
        private String domainName;
        private double topSimilarity;
        private String matchedSeedQuestion;
        private Map<String, Double> allDomainSimilarities;
        private boolean isSemanticMatched;
        private IntentRouter.Routing fallbackRouting;
    }

    /** 缓存已向量化的种子题库 */
    private final List<SeedEmbedding> seedEmbeddingsCache = new ArrayList<>();
    private volatile boolean isInitialized = false;

    public SemanticRouter(@Lazy DashScopeEmbeddingService embeddingService, IntentRouter regexRouter) {
        this.embeddingService = embeddingService;
        this.regexRouter = regexRouter;
    }

    /**
     * 异步后台初始化：调用阿里百炼 text-embedding-v3 向量化所有种子题
     */
    @PostConstruct
    public void initSeedVectorsAsync() {
        Thread thread = new Thread(() -> {
            try {
                log.info("【语义向量路由初始化】正在调用阿里百炼 text-embedding-v3 向量化 6 大领域标准种子问题库...");
                List<String> allTexts = new ArrayList<>();
                List<ToolDomain> domainMapping = new ArrayList<>();

                for (Map.Entry<ToolDomain, List<String>> entry : SEED_QUESTIONS.entrySet()) {
                    for (String q : entry.getValue()) {
                        allTexts.add(q);
                        domainMapping.add(entry.getKey());
                    }
                }

                // 批量调百炼 text-embedding-v3 提取 1024 维稠密特征向量
                List<float[]> vectors = embeddingService.embedBatch(allTexts, null);
                for (int i = 0; i < allTexts.size() && i < vectors.size(); i++) {
                    seedEmbeddingsCache.add(SeedEmbedding.builder()
                            .text(allTexts.get(i))
                            .domain(domainMapping.get(i))
                            .vector(vectors.get(i))
                            .build());
                }
                isInitialized = true;
                log.info("【语义向量路由初始化就绪】成功构建 {} 条领域种子向量库，真实 1024 维 Embedding 已常驻内存！", seedEmbeddingsCache.size());
            } catch (Exception e) {
                log.warn("【语义向量路由初始化跳过】网络超时或无 Key，将采用自适应混合回退模式: {}", e.getMessage());
            }
        });
        thread.setName("SemanticRouter-Init");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 核心路由方法：基于百炼 1024 维余弦相似度的真实语义路由
     *
     * @param userQuery 用户自然语言输入
     * @return 语义路由结果（包含每个领域的相似度得分、最佳匹配问题）
     */
    public SemanticRoutingResult route(String userQuery, String attachmentName) {
        // 先获取正则基底路由
        IntentRouter.Routing baseRouting = regexRouter.route(userQuery, attachmentName);

        if (!isInitialized || seedEmbeddingsCache.isEmpty() || userQuery == null || userQuery.isBlank()) {
            return SemanticRoutingResult.builder()
                    .domain(baseRouting.domain())
                    .domainName(baseRouting.domainName())
                    .topSimilarity(1.0)
                    .matchedSeedQuestion("基底规则正则直接匹配")
                    .allDomainSimilarities(Map.of(baseRouting.domain(), 1.0))
                    .isSemanticMatched(false)
                    .fallbackRouting(baseRouting)
                    .build();
        }

        try {
            // 1. 抽取用户 Query 的真实 1024 维稠密向量
            float[] queryVector = embeddingService.embedText(userQuery, null);
            if (queryVector == null || queryVector.length == 0) {
                return fallback(baseRouting);
            }

            // 2. 与各领域种子向量计算余弦相似度并按领域取最大相似度
            Map<ToolDomain, Double> domainMaxScores = new ConcurrentHashMap<>();
            Map<ToolDomain, String> domainBestSeeds = new ConcurrentHashMap<>();

            for (SeedEmbedding seed : seedEmbeddingsCache) {
                double sim = DashScopeEmbeddingService.cosineSimilarity(queryVector, seed.getVector());
                if (sim > domainMaxScores.getOrDefault(seed.getDomain(), 0.0)) {
                    domainMaxScores.put(seed.getDomain(), sim);
                    domainBestSeeds.put(seed.getDomain(), seed.getText());
                }
            }

            // 3. 找出相似度最高的主领域
            ToolDomain topDomain = ToolDomain.GENERAL;
            double highestScore = 0.0;
            for (Map.Entry<ToolDomain, Double> entry : domainMaxScores.entrySet()) {
                if (entry.getValue() > highestScore) {
                    highestScore = entry.getValue();
                    topDomain = entry.getKey();
                }
            }

            // 4. 融合规则与语义路由 (Hybrid Fusion)
            String baseDomain = baseRouting.domain();
            boolean isBaseComposite = "COMPOSITE".equalsIgnoreCase(baseDomain);
            boolean isBaseSpecific = !"GENERAL".equalsIgnoreCase(baseDomain) && !isBaseComposite;

            String finalDomain;
            String finalDomainName;

            if (isBaseComposite) {
                // 规则明确匹配出跨领域关键字并集 (复合路由)
                finalDomain = ToolDomain.COMPOSITE.name();
                finalDomainName = ToolDomain.COMPOSITE.getDescription();
            } else if (isBaseSpecific) {
                // 规则命中了单一垂直领域 (COMMERCE, KNOWLEDGE, PROSPECT, CRM, VISION)
                finalDomain = baseDomain;
                finalDomainName = baseRouting.domainName();
            } else {
                // 规则未包含强关键词，检验语义向量是否唤醒
                if (highestScore < 0.42 || topDomain == ToolDomain.GENERAL) {
                    finalDomain = ToolDomain.GENERAL.name();
                    finalDomainName = ToolDomain.GENERAL.getDescription();
                } else {
                    finalDomain = topDomain.name();
                    finalDomainName = topDomain.getDescription();
                }
            }

            Map<String, Double> simMap = new LinkedHashMap<>();
            domainMaxScores.forEach((k, v) -> simMap.put(k.name(), Math.round(v * 1000.0) / 1000.0));

            return SemanticRoutingResult.builder()
                    .domain(finalDomain)
                    .domainName(finalDomainName)
                    .topSimilarity(Math.round(highestScore * 1000.0) / 1000.0)
                    .matchedSeedQuestion(domainBestSeeds.getOrDefault(topDomain, "无"))
                    .allDomainSimilarities(simMap)
                    .isSemanticMatched(true)
                    .fallbackRouting(baseRouting)
                    .build();

        } catch (Exception e) {
            log.error("【语义向量路由计算失败】降级为基底规则: {}", e.getMessage());
            return fallback(baseRouting);
        }
    }

    private SemanticRoutingResult fallback(IntentRouter.Routing baseRouting) {
        return SemanticRoutingResult.builder()
                .domain(baseRouting.domain())
                .domainName(baseRouting.domainName())
                .topSimilarity(0.0)
                .matchedSeedQuestion("降级基底规则")
                .allDomainSimilarities(Collections.emptyMap())
                .isSemanticMatched(false)
                .fallbackRouting(baseRouting)
                .build();
    }
}
