package com.tradeagent.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 租户/系统多模型动态配置实体
 * 包含平台类型、BaseURL、脱敏密钥、模型名称及采样参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 租户标识 (多租户隔离主键)
     */
    private Long tenantId;

    /**
     * 配置用途标识 (e.g. "chat", "embedding", "vision")
     */
    private String usageType;

    /**
     * 提供商类型: DEEPSEEK, DOUBAO, QWEN, OPENAI
     */
    private String provider;

    /**
     * 模型接入 BaseURL (支持兼容 OpenAI 协议的任意端点)
     */
    private String baseUrl;

    /**
     * API Key (支持 AES 密文存储，内存解密)
     */
    private String apiKey;

    /**
     * 具体模型名称 (e.g. "deepseek-chat", "doubao-pro-32k", "gpt-4o")
     */
    private String modelName;

    /**
     * 温度采样参数 (0.0 ~ 2.0)
     */
    @Builder.Default
    private Double temperature = 0.7;

    /**
     * 最大输出 Token
     */
    @Builder.Default
    private Integer maxTokens = 4096;

    /**
     * 生成缓存 Key
     */
    public String getCacheKey() {
        return String.format("%s:%s:%s:%s", tenantId, usageType, provider, modelName);
    }
}
