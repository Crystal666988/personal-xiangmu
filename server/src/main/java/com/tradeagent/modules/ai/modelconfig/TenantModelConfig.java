package com.tradeagent.modules.ai.modelconfig;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户级模型配置（一份配置 = 提供商 + 端点 + 密钥 + 模型；同租户多份、单份生效）。
 *
 * 安全约定：api_key 仅在服务端内部解析使用（对话/审批/恢复时构造模型客户端），
 * 任何对外接口只下发掩码 key_tail，绝不下发明文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_model_config")
public class TenantModelConfig {

    @TableId(type = IdType.INPUT)
    private String id;

    private String tenantId;

    private String name;

    private String provider;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Double temperature;

    private Integer maxTokens;

    private Boolean isActive;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
