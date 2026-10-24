-- 任务：租户隔离的模型配置表（一份配置 = 提供商 + 端点 + 密钥 + 模型；租户下多份，单份生效）
-- 约定：api_key 仅服务端解析使用，任何列表/详情接口只下发掩码（key_tail），绝不下发明文。
CREATE TABLE IF NOT EXISTS tenant_model_config (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL COMMENT '配置名称',
    provider VARCHAR(32) NOT NULL DEFAULT 'custom' COMMENT '提供商: deepseek/aliyun/openai/custom',
    base_url VARCHAR(255) NOT NULL COMMENT '接入端点',
    api_key VARCHAR(255) NULL COMMENT 'API Key（仅服务端使用，接口只回掩码）',
    model_name VARCHAR(80) NOT NULL COMMENT '模型名称',
    temperature DOUBLE NOT NULL DEFAULT 0.7,
    max_tokens INT NOT NULL DEFAULT 4096,
    is_active TINYINT NOT NULL DEFAULT 0 COMMENT '租户内生效配置（同租户唯一）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_tmc_tenant (tenant_id, is_active),
    KEY idx_tmc_tenant_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户级模型配置（CRUD + 单生效）';
