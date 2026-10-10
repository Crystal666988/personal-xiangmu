package com.tradeagent.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.checkpoint.TaskCheckpointManager;
import com.tradeagent.core.dag.DagExecutionEngine;
import com.tradeagent.core.dag.DagNode;
import com.tradeagent.core.json.JsonRepairUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Plan-and-Execute (规划与执行分离) 长文章自适应编排引擎
 * 
 * 核心设计：
 * 1. 【Plan 阶段 (AI 规划器)】: 
 *    大模型利用 CoT 思维链分析长文章主题，动态制定结构化目录大纲 JSON (决定细分子任务与拓扑依赖)；
 * 2. 【Execute 阶段 (Java 调度引擎)】:
 *    代码接管生成的子任务列表，转化为 DagNode 扔入有界线程池并发填充各个章节；
 * 3. 【增量 Checkpoint】:
 *    每个子任务跑完立即写入 Redis 存档点，断点续跑时跳过已完成章节。
 */
@Slf4j
@Service
public class PlanAndExecuteOrchestrator {

    @Resource
    private DagExecutionEngine dagExecutionEngine;

    @Resource
    private TaskCheckpointManager checkpointManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 规划并生成长篇报告
     *
     * @param taskId 任务唯一ID
     * @param topic 报告宏观主题 (如 "中东阿联酋光伏市场准入与竞争对手全景调研")
     * @param plannerModel 用于规划大纲的思维模型
     * @param writerModel 用于并行填充正文的写作模型
     * @return 完整拼接后的长篇大报告
     */
    public String executeLongArticlePlan(String taskId, String topic, ChatModel plannerModel, ChatModel writerModel) {
        log.info("【Plan-and-Execute】开始启动长任务长篇规划: [{}] 任务ID: {}", topic, taskId);

        // 步骤 1: Plan 阶段 -> AI Planner 动态拆解子任务大纲
        List<ArticleSubtask> subtasks = planArticleStructure(topic, plannerModel);
        log.info("【Plan-and-Execute】AI 规划器成功拆解出 {} 个细分子任务章节", subtasks.size());

        // 步骤 2: Execute 阶段 -> 将子任务转化为 DAG 拓扑节点
        List<DagNode> dagNodes = new ArrayList<>();
        for (ArticleSubtask subtask : subtasks) {
            dagNodes.add(new DagNode() {
                @Override
                public String getNodeId() {
                    return subtask.id;
                }

                @Override
                public Set<String> getDependencies() {
                    return subtask.dependsOn;
                }

                @Override
                public CompletableFuture<Object> execute(Map<String, Object> context, ExecutorService executor) {
                    // 断点续跑检查：如果 Redis 里该段已经存过档，直接跳过不重复烧 Token！
                    if (checkpointManager.isStepCompleted(taskId, subtask.id)) {
                        log.info("【断点秒级命中】子任务 [{}] 已在 Redis 存档，直接复用", subtask.id);
                        String cachedContent = checkpointManager.getCheckpoints(taskId).get(subtask.id);
                        return CompletableFuture.completedFuture(cachedContent);
                    }

                    // 真正调用小模型填充该章节内容 (受控在 5~8 秒内)
                    return CompletableFuture.supplyAsync(() -> {
                        String sectionContent = writerModel.call(subtask.prompt);
                        // 每一个子任务完成瞬间 -> 立即记录增量 Checkpoint！
                        checkpointManager.saveCheckpoint(taskId, subtask.id, sectionContent);
                        return sectionContent;
                    }, executor);
                }
            });
        }

        // 步骤 3: 调度 DAG 执行引擎并发跑
        Map<String, Object> finalContext = dagExecutionEngine.executeDag(dagNodes, new HashMap<>());

        // 步骤 4: 按章节顺序聚合拼接长文
        StringBuilder fullArticle = new StringBuilder();
        fullArticle.append("# ").append(topic).append("\n\n");
        for (ArticleSubtask subtask : subtasks) {
            fullArticle.append("## ").append(subtask.title).append("\n");
            fullArticle.append(finalContext.get(subtask.id + "_RESULT")).append("\n\n");
        }

        checkpointManager.clearCheckpoint(taskId); // 清理临时快照
        log.info("【Plan-and-Execute】长篇报告全量合成完毕，总字数: {}", fullArticle.length());
        return fullArticle.toString();
    }

    /**
     * 调用 AI Planner 动态规划大纲
     */
    private List<ArticleSubtask> planArticleStructure(String topic, ChatModel plannerModel) {
        String planPrompt = String.format(
                "请为深度行业报告《%s》设计动态章节子任务计划。\n" +
                "请通过 CoT 思维链分解为 3~4 个章节，必须严格输出如下格式的 JSON 数组：\n" +
                "[{\"id\":\"sec_1\",\"title\":\"宏观背景与进出口数据\",\"prompt\":\"请撰写...\",\"dependsOn\":[]}," +
                " {\"id\":\"sec_2\",\"title\":\"竞品格局与渠道分析\",\"prompt\":\"请撰写...\",\"dependsOn\":[]}," +
                " {\"id\":\"sec_3\",\"title\":\"执行摘要与商务落地清单\",\"prompt\":\"基于前文汇总撰写...\",\"dependsOn\":[\"sec_1\",\"sec_2\"]}]",
                topic
        );

        try {
            String rawJson = plannerModel.call(planPrompt);
            String cleanJson = JsonRepairUtil.autoRepairJson(rawJson); // 栈平衡自愈修补
            JsonNode rootNode = objectMapper.readTree(cleanJson);

            List<ArticleSubtask> subtasks = new ArrayList<>();
            for (JsonNode item : rootNode) {
                Set<String> deps = new HashSet<>();
                if (item.has("dependsOn")) {
                    item.get("dependsOn").forEach(d -> deps.add(d.asText()));
                }
                subtasks.add(new ArticleSubtask(
                        item.get("id").asText(),
                        item.get("title").asText(),
                        item.get("prompt").asText(),
                        deps
                ));
            }
            return subtasks;
        } catch (Exception e) {
            log.warn("【AI 大纲规划降级】大模型生成大纲解析异常，降级使用标准三段论大纲: {}", e.getMessage());
            return List.of(
                    new ArticleSubtask("sec_1", "海关与行业基础数据", "撰写海关进出口基础数据分析", Collections.emptySet()),
                    new ArticleSubtask("sec_2", "竞争对手与价格走势", "撰写竞品价格与渠道分析", Collections.emptySet()),
                    new ArticleSubtask("sec_3", "商务拓展决策清单", "汇总前文生成业务员行动清单", Set.of("sec_1", "sec_2"))
            );
        }
    }

    private static class ArticleSubtask {
        String id;
        String title;
        String prompt;
        Set<String> dependsOn;

        public ArticleSubtask(String id, String title, String prompt, Set<String> dependsOn) {
            this.id = id;
            this.title = title;
            this.prompt = prompt;
            this.dependsOn = dependsOn;
        }
    }
}
