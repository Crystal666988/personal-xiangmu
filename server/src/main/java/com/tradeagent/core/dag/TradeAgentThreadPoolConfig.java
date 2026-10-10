package com.tradeagent.core.dag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

/**
 * TradeAgent 专用并发调度有界线程池
 * 
 * 防御重点：
 * 1. 杜绝 Executors 无界队列引发的内存溢出 (OOM)；
 * 2. 核心线程数按 CPU 密集与 IO 密集混合设定；
 * 3. 拒绝策略使用 CallerRunsPolicy，饱和时由主线程分担，保证任务不丢失。
 */
@Configuration
public class TradeAgentThreadPoolConfig {

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    @Bean("agentDagExecutor")
    public ExecutorService agentDagExecutor() {
        int corePoolSize = Math.max(4, CPU_COUNT * 2);
        int maxPoolSize = Math.max(8, CPU_COUNT * 4);
        long keepAliveTime = 60L;

        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500), // 有界缓冲队列
                new ThreadFactory() {
                    private int count = 1;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "agent-dag-worker-" + count++);
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时调用方主线程兜底
        );
    }
}
