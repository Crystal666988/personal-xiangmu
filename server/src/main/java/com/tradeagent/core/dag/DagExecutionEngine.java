package com.tradeagent.core.dag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 工业级 DAG (有向无环图) 异步并行执行调度引擎
 * 
 * 核心技术机制：
 * 1. 【拓扑依赖编排】：根据节点的 dependencies 自动构建依赖树；无依赖节点立刻在线程池中高并发推进；
 * 2. 【有界线程池隔离】：所有异步 I/O (RAG 检索、海关抓取、运费测算) 运行在受控线程池，彻底防止线程雪崩；
 * 3. 【Saga 柔性事务回滚】：任意写节点物理报错时，按逆序自动触发已完成节点的 rollback() 补偿动作。
 */
@Slf4j
@Component
public class DagExecutionEngine {

    @Resource(name = "agentDagExecutor")
    private ExecutorService agentDagExecutor;

    /**
     * 执行完整的 DAG 任务拓扑图
     *
     * @param nodes 待执行的节点列表
     * @param initialContext 初始输入参数上下文
     * @return 聚合所有节点输出后的最终上下文
     */
    public Map<String, Object> executeDag(List<DagNode> nodes, Map<String, Object> initialContext) {
        long startTime = System.currentTimeMillis();
        log.info("【DAG 调度引擎】开始装配拓扑图，当前待执行节点数: {}", nodes.size());

        // 线程安全的全局共享上下文
        Map<String, Object> sharedContext = new ConcurrentHashMap<>(initialContext);

        // 存储各节点的异步 Future：NodeId -> CompletableFuture
        Map<String, CompletableFuture<Void>> futureMap = new ConcurrentHashMap<>();
        
        // 记录已成功完成的节点 (供回滚补偿使用)
        List<DagNode> completedNodes = Collections.synchronizedList(new ArrayList<>());

        try {
            // 1. 递归/迭代装配依赖关系
            for (DagNode node : nodes) {
                scheduleNode(node, nodes, sharedContext, futureMap, completedNodes);
            }

            // 2. 阻塞等待所有节点聚合完成 (设置 15 秒总执行超时阈值，防止外部挂起)
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futureMap.values().toArray(new CompletableFuture[0])
            );
            allFutures.get(15, TimeUnit.SECONDS);

            long cost = System.currentTimeMillis() - startTime;
            log.info("【DAG 调度引擎】全链路拓扑图执行完毕，总耗时: {} ms, 最终输出字段数: {}", cost, sharedContext.size());
            return sharedContext;

        } catch (Exception e) {
            log.error("【DAG 调度引擎】执行发生异常，触发 Saga 逆向补偿回滚! 错误: {}", e.getMessage(), e);
            // 触发逆向补偿事务
            triggerRollback(completedNodes, sharedContext);
            throw new RuntimeException("DAG 工作流执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调度单个节点：等待其所有依赖完成后，立即触发当前节点异步执行
     */
    private CompletableFuture<Void> scheduleNode(DagNode node,
                                                 List<DagNode> allNodes,
                                                 Map<String, Object> context,
                                                 Map<String, CompletableFuture<Void>> futureMap,
                                                 List<DagNode> completedNodes) {
        return futureMap.computeIfAbsent(node.getNodeId(), id -> {
            Set<String> deps = node.getDependencies();

            CompletableFuture<Void> depFuture;
            if (deps == null || deps.isEmpty()) {
                // 无依赖：直接从当前时刻开始异步跑
                depFuture = CompletableFuture.completedFuture(null);
            } else {
                // 有依赖：等待所有前置依赖的 Future 跑完
                List<CompletableFuture<Void>> depFutures = new ArrayList<>();
                for (String depId : deps) {
                    DagNode depNode = findNodeById(allNodes, depId);
                    if (depNode != null) {
                        depFutures.add(scheduleNode(depNode, allNodes, context, futureMap, completedNodes));
                    }
                }
                depFuture = CompletableFuture.allOf(depFutures.toArray(new CompletableFuture[0]));
            }

            // 前置依赖一旦就绪，立即将当前节点提交给专用线程池执行
            return depFuture.thenComposeAsync(ignored -> {
                log.info("【DAG 节点启动】依赖就绪，开始执行节点: [{}]", node.getNodeId());
                return node.execute(context, agentDagExecutor).thenAccept(result -> {
                    if (result != null) {
                        context.put(node.getNodeId() + "_RESULT", result);
                    }
                    completedNodes.add(node);
                    log.info("【DAG 节点完成】节点执行成功: [{}]", node.getNodeId());
                });
            }, agentDagExecutor);
        });
    }

    /**
     * Saga 逆向补偿回滚机制
     */
    private void triggerRollback(List<DagNode> completedNodes, Map<String, Object> context) {
        log.warn("【Saga 逆向补偿】开始执行回滚补偿，受影响节点数: {}", completedNodes.size());
        // 逆序执行补偿
        List<DagNode> reversed = new ArrayList<>(completedNodes);
        Collections.reverse(reversed);

        for (DagNode node : reversed) {
            try {
                log.info("【Saga 逆向补偿】正在对节点 [{}] 执行 rollback() 补偿...", node.getNodeId());
                node.rollback(context);
            } catch (Exception ex) {
                log.error("【Saga 逆向补偿】节点 [{}] 补偿执行异常: {}", node.getNodeId(), ex.getMessage());
            }
        }
        log.warn("【Saga 逆向补偿】全量逆向补偿流程结束.");
    }

    private DagNode findNodeById(List<DagNode> allNodes, String id) {
        return allNodes.stream()
                .filter(n -> n.getNodeId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
