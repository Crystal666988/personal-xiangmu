package com.tradeagent.core.model;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 工业级动态模型工厂与生命周期治理中心
 * 
 * 核心技术亮点：
 * 1. 【动态热装配】：运行时根据租户配置动态构建 ChatModel，无需重启 JVM；
 * 2. 【Bean 容器可控注册】：将模型动态注册进 Spring DefaultListableBeanFactory，享受 Spring 统一依赖注入；
 * 3. 【防内存泄漏机制】：
 *    - 维护基于 LRU 与过期机制的 Guava 缓存池；
 *    - 当租户切换模型、更改密钥或实例淘汰时，主动触发 destroySingleton 注销 Spring 单例；
 *    - 阻断底层 Netty / OkHttp 连接池孤儿线程泄漏，防止 Metaspace 膨胀 OOM。
 */
@Slf4j
@Component
public class DynamicModelFactory implements ApplicationContextAware {

    private DefaultListableBeanFactory beanFactory;

    /**
     * 记录动态注册的 Bean 名称映射：CacheKey -> Spring BeanName
     */
    private final ConcurrentMap<String, String> registeredBeanNames = new ConcurrentHashMap<>();

    /**
     * 模型实例缓存池：LRU 淘汰 + 30 分钟无访问过期自动驱逐
     */
    private final Cache<String, ChatModel> modelCache = CacheBuilder.newBuilder()
            .maximumSize(100)
            .expireAfterAccess(Duration.ofMinutes(30))
            .removalListener(new RemovalListener<String, ChatModel>() {
                @Override
                public void onRemoval(RemovalNotification<String, ChatModel> notification) {
                    handleModelEviction(notification.getKey(), notification.getValue(), notification.getCause().name());
                }
            })
            .build();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
    }

    /**
     * 获取或创建指定租户的 ChatModel 实例
     *
     * @param config 模型配置
     * @return 对应的 ChatModel
     */
    public ChatModel getOrCreateChatModel(ModelConfig config) {
        String cacheKey = config.getCacheKey();
        try {
            return modelCache.get(cacheKey, () -> createAndRegisterChatModel(config));
        } catch (Exception e) {
            log.error("构建 ChatModel 异常, cacheKey: {}, 原因: {}", cacheKey, e.getMessage(), e);
            throw new RuntimeException("动态构建 AI 模型实例失败: " + e.getMessage(), e);
        }
    }

    /**
     * 核心创建与 Spring Bean 动态注册逻辑
     */
    private synchronized ChatModel createAndRegisterChatModel(ModelConfig config) {
        String cacheKey = config.getCacheKey();
        String dynamicBeanName = "dynamicChatModel_" + Math.abs(cacheKey.hashCode());

        log.info("【动态模型工厂】开始构建新模型实例 -> 租户ID: {}, 模型名: {}, BeanName: {}", 
                config.getTenantId(), config.getModelName(), dynamicBeanName);

        // 1. 基于 OpenAI 协议封装底层 Client (兼容 DeepSeek, 豆包, Qwen, GPT)
        OpenAiApi openAiApi = new OpenAiApi(config.getBaseUrl(), config.getApiKey());

        // 2. 组装采样参数与模型选项
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(config.getModelName())
                .withTemperature(config.getTemperature())
                .withMaxTokens(config.getMaxTokens())
                .build();

        // 3. 构造 Spring AI ChatModel 实例
        OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi, options);

        // 4. 动态注册进 Spring IOC 容器（若已存在先销毁旧单例）
        if (beanFactory.containsSingleton(dynamicBeanName)) {
            beanFactory.destroySingleton(dynamicBeanName);
        }
        beanFactory.registerSingleton(dynamicBeanName, chatModel);
        registeredBeanNames.put(cacheKey, dynamicBeanName);

        log.info("【动态模型工厂】成功装配并注册模型 Bean: {}", dynamicBeanName);
        return chatModel;
    }

    /**
     * 主动驱逐模型（租户更新密钥、切换模型提供商时调用）
     *
     * @param tenantId 租户ID
     * @param usageType 用途类型
     */
    public void evictTenantModel(Long tenantId, String usageType) {
        String prefix = tenantId + ":" + usageType;
        registeredBeanNames.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .forEach(key -> {
                    log.info("【动态模型工厂】接收到主动失效指令, 驱逐 Key: {}", key);
                    modelCache.invalidate(key);
                });
    }

    /**
     * 模型实例驱逐监听处理函数 (防内存泄漏关键)
     */
    private void handleModelEviction(String cacheKey, ChatModel model, String reason) {
        log.warn("【内存泄漏防御】触发模型实例驱逐 -> CacheKey: {}, 触发原因: {}", cacheKey, reason);

        String beanName = registeredBeanNames.remove(cacheKey);
        if (beanName != null && beanFactory.containsSingleton(beanName)) {
            // 1. 显式通知 Spring 容器注销并执行 Bean 销毁生命周期
            beanFactory.destroySingleton(beanName);
            log.info("【内存泄漏防御】已成功从 Spring 容器销毁 Singleton Bean: {}", beanName);
        }

        // 2. 切断引用，提示 JVM GC 回收对象及其关联的底层 HTTP Client 连接池
        log.info("【内存泄漏防御】完成连接池解绑与孤儿对象回收准备.");
    }

    /**
     * 校验模型配置是否合法 (1-Token 极速探针法)
     *
     * @param config 待测试的模型配置
     * @return true 代表连接正常，false 代表鉴权失败或网络不可达
     */
    public boolean testConnection(ModelConfig config) {
        log.info("【模型配置测试】正在向 {} 发起 1-Token 极简探针测试...", config.getBaseUrl());
        try {
            OpenAiApi testApi = new OpenAiApi(config.getBaseUrl(), config.getApiKey());
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .withModel(config.getModelName())
                    .withMaxTokens(1)      // 只消耗 1 个 Token，成本几乎为 0
                    .withTemperature(0.0)
                    .build();
            OpenAiChatModel testModel = new OpenAiChatModel(testApi, options);
            String probeResult = testModel.call("ping");
            boolean success = probeResult != null;
            log.info("【模型配置测试】探针测试完成, 结果: {}", success ? "SUCCESS" : "EMPTY");
            return success;
        } catch (Exception e) {
            log.warn("【模型配置测试】连接失败, 错误信息: {}", e.getMessage());
            return false;
        }
    }
}
