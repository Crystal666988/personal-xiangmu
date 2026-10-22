-- Task 6: 实体画像记忆表 (CRM Buyer Profile)
-- 只填空不覆盖原则 (Fill-Blank-Only) + 人工核准加锁保护 (Human Verification Lock)
CREATE TABLE IF NOT EXISTS crm_buyer_profile (
    id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键ID',
    tenant_id VARCHAR(64) NOT NULL DEFAULT '1001' COMMENT '租户ID',
    buyer_id VARCHAR(64) NOT NULL COMMENT '买家/客户唯一标识',
    buyer_name VARCHAR(128) NULL COMMENT '买家联系人姓名',
    company_name VARCHAR(128) NULL COMMENT '买家企业名称',
    country VARCHAR(64) NULL COMMENT '国家/地区',
    destination_port VARCHAR(64) NULL COMMENT '指定目的港口 (如 Hamburg, Rotterdam)',
    preferred_incoterm VARCHAR(32) NULL COMMENT '偏好国际贸易术语 (CIF, FOB, EXW, CFR, DDP)',
    target_product VARCHAR(128) NULL COMMENT '核心意向采购产品 (如 550W单晶硅光伏组件)',
    target_volume VARCHAR(64) NULL COMMENT '意向采购规模与柜型 (如 2000片 / 2个40HQ)',
    price_sensitivity VARCHAR(32) NULL COMMENT '价格敏感度 (HIGH, MEDIUM, LOW)',
    locked_fields_json TEXT NULL COMMENT '人工确认加锁字段JSON数组 (如 ["destinationPort","preferredIncoterm"])',
    extra_attributes_json TEXT NULL COMMENT '动态实体扩展属性JSON键值对',
    source_session_id VARCHAR(64) NULL COMMENT '最新提炼来源会话ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted INT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_crm_buyer_tenant (tenant_id, buyer_id),
    KEY idx_crm_buyer_port (destination_port)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家实体画像跨会话记忆主表';
