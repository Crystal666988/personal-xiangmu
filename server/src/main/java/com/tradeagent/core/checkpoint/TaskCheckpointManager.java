package com.tradeagent.core.checkpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长任务多子任务增量存档点管理器 (Task Checkpoint Manager)
 * 
 * 解决面试官核心考点：
 * 1. 【存档点发生在哪一步？】：发生在每一个并行子任务 Future 的 thenAccept 收口回调阶段；
 * 2. 【如何设计？】：以任务 ID 为主键，维护每个步骤的快照字典（生产环境映射至 Redis Hash）；
 * 3. 【断点续跑】：任务失败重试时，读取已存档记录，仅重新调度未完成的空缺步骤，杜绝重复烧 Token！
 */
@Slf4j
@Component
public class TaskCheckpointManager {

    /**
     * 内存/Redis 模拟快照存储: taskId -> Map<stepKey, stepResult>
     */
    private final Map<String, Map<String, String>> checkpointStorage = new ConcurrentHashMap<>();

    /**
     * 关键收口动作：子任务执行成功瞬间记录存档点
     *
     * @param taskId 长任务唯一标识
     * @param stepKey 当前子任务步骤名 (如 "dimension_1", "executive_summary")
     * @param content 生成完成的内容
     */
    public void saveCheckpoint(String taskId, String stepKey, String content) {
        checkpointStorage.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>()).put(stepKey, content);
        log.info("【存档点已持久化】任务 [{}] 步骤 [{}] 成功写入快照 (已完成步骤数: {})", 
                taskId, stepKey, checkpointStorage.get(taskId).size());
    }

    /**
     * 读取指定任务的所有已保存断点快照
     */
    public Map<String, String> getCheckpoints(String taskId) {
        return checkpointStorage.getOrDefault(taskId, Collections.emptyMap());
    }

    /**
     * 判断某个子步骤是否已经有成功的存档（断点续跑时跳过该步骤）
     */
    public boolean isStepCompleted(String taskId, String stepKey) {
        Map<String, String> checkpoints = checkpointStorage.get(taskId);
        return checkpoints != null && checkpoints.containsKey(stepKey);
    }

    /**
     * 任务彻底完结后，清理临时存档快照
     */
    public void clearCheckpoint(String taskId) {
        checkpointStorage.remove(taskId);
        log.info("【快照生命周期清理】任务 [{}] 已全量完成，释放存档空间。", taskId);
    }
}
