package com.tradeagent.modules.prospect;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客源采集 Provider 注册中心 (Spring 构造器注入 + SPI 架构)
 * 
 * 核心技术考点 (简历第 92 行)：
 * 利用 Spring 构造器自动装配特性注入 List<ProspectCollectorProvider>。
 * 新增渠道（如 TikTok/Instagram）时，只需编写新的实现类并加 @Component，
 * 注册中心自动发现并注册，完全符合“开闭原则 (OCP)”，实现纯新增类接入！
 */
@Slf4j
@Component
public class ProspectCollectorRegistry {

    private final Map<String, ProspectCollectorProvider> providerMap = new ConcurrentHashMap<>();

    /**
     * Spring 构造器注入：自动收集 IOC 容器内所有实现了 ProspectCollectorProvider 的 Bean
     */
    public ProspectCollectorRegistry(List<ProspectCollectorProvider> providers) {
        if (providers != null) {
            for (ProspectCollectorProvider provider : providers) {
                providerMap.put(provider.getChannelCode().toUpperCase(), provider);
                log.info("【SPI 注册中心】已成功挂载采集通道插件: [{}] -> {}", 
                        provider.getChannelCode(), provider.getClass().getSimpleName());
            }
        }
    }

    /**
     * 获取指定渠道的采集提供商
     */
    public ProspectCollectorProvider getProvider(String channelCode) {
        if (channelCode == null) return null;
        return providerMap.get(channelCode.toUpperCase());
    }

    /**
     * 获取当前所有已挂载的可用渠道列表
     */
    public Set<String> getRegisteredChannels() {
        return Collections.unmodifiableSet(providerMap.keySet());
    }
}
