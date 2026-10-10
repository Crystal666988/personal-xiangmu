package com.tradeagent.core.dag;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * DAG 任务执行节点通用接口
 * 支持异步执行、依赖声明与失败逆向补偿 (Rollback)
 */
public interface DagNode {

    /**
     * 节点唯一标识 (e.g. "RAG_RETRIEVER_NODE", "SHIPPING_CALC_NODE")
     */
    String getNodeId();

    /**
     * 依赖的前置节点标识集合 (前置节点全部完成后，当前节点才触发)
     */
    default Set<String> getDependencies() {
        return Collections.emptySet();
    }

    /**
     * 异步执行节点逻辑
     *
     * @param context 全局会话与节点共享上下文
     * @param executor 有界调度线程池
     * @return 节点异步执行结果
     */
    CompletableFuture<Object> execute(Map<String, Object> context, ExecutorService executor);

    /**
     * 逆向补偿与回滚钩子 (Saga 模式支撑：写操作失败或全局熔断时执行)
     *
     * @param context 上下文
     */
    default void rollback(Map<String, Object> context) {
        // 默认无状态只读节点无需回滚
    }
}
