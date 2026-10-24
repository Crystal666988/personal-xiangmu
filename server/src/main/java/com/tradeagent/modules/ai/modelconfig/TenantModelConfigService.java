package com.tradeagent.modules.ai.modelconfig;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 租户级模型配置服务：标准 CRUD + 单生效切换 + 服务端密钥解析。
 *
 * 职责边界：
 * 1. 管理页 CRUD 与"启用"切换（同租户内 is_active 唯一，事务保证）；
 * 2. {@link #resolveActive(String)} 供对话/审批/恢复链路在服务端解析生效配置，
 *    API Key 从此不进浏览器——前端只引用 tenantId，后端自己取密钥构造模型客户端；
 * 3. 对外行数据一律掩码（hasApiKey + keyTail），明文绝不出服务端。
 */
@Slf4j
@Service
public class TenantModelConfigService {

    private final TenantModelConfigMapper mapper;

    public TenantModelConfigService(TenantModelConfigMapper mapper) {
        this.mapper = mapper;
    }

    // ---------- 对外 DTO ----------

    /** 列表/详情行：不含明文密钥，只含掩码尾部。 */
    public record ConfigRow(String id, String name, String provider, String baseUrl,
                            String modelName, Double temperature, Integer maxTokens,
                            boolean hasApiKey, String keyTail, boolean active, String createTime) {}

    /** 新增/编辑请求；编辑时 apiKey 留空表示保持原密钥。 */
    public record SaveRequest(String name, String provider, String baseUrl, String apiKey,
                              String modelName, Double temperature, Integer maxTokens) {}

    // ---------- 查询 ----------

    public List<ConfigRow> list(String tenantId) {
        return mapper.selectList(query(tenantId)).stream()
                .map(this::toRow)
                .toList();
    }

    public Optional<ConfigRow> activeMasked(String tenantId) {
        return findActive(tenantId).map(this::toRow);
    }

    /** 服务端内部使用：解析租户生效配置为模型客户端配置（含明文密钥），绝不下发前端。 */
    public Optional<RoutingModelConfig> resolveActive(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        return findActive(tenantId).map(config -> new RoutingModelConfig(
                config.getApiKey(),
                config.getBaseUrl(),
                config.getModelName(),
                parseTenant(tenantId),
                config.getProvider() == null || config.getProvider().isBlank() ? "openai" : config.getProvider()
        ));
    }

    // ---------- CRUD ----------

    @Transactional
    public ConfigRow create(String tenantId, SaveRequest request) {
        validate(request);
        List<TenantModelConfig> existing = mapper.selectList(query(tenantId));
        TenantModelConfig entity = TenantModelConfig.builder()
                .id("tmc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20))
                .tenantId(tenantId)
                .name(request.name().trim())
                .provider(normalizeProvider(request.provider()))
                .baseUrl(request.baseUrl().trim())
                .apiKey(emptyToNull(request.apiKey()))
                .modelName(request.modelName().trim())
                .temperature(request.temperature() != null ? request.temperature() : 0.7)
                .maxTokens(request.maxTokens() != null ? request.maxTokens() : 4096)
                .isActive(existing.isEmpty())
                .deleted(0)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        mapper.insert(entity);
        log.info("【模型配置】租户 {} 新增配置 {} ({}), 首份自动生效: {}", tenantId, entity.getName(), entity.getModelName(), entity.getIsActive());
        return toRow(entity);
    }

    @Transactional
    public Optional<ConfigRow> update(String tenantId, String id, SaveRequest request) {
        validate(request);
        TenantModelConfig entity = owned(tenantId, id);
        entity.setName(request.name().trim());
        entity.setProvider(normalizeProvider(request.provider()));
        entity.setBaseUrl(request.baseUrl().trim());
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKey(request.apiKey().trim());
        }
        entity.setModelName(request.modelName().trim());
        if (request.temperature() != null) entity.setTemperature(request.temperature());
        if (request.maxTokens() != null) entity.setMaxTokens(request.maxTokens());
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
        return Optional.of(toRow(entity));
    }

    @Transactional
    public boolean delete(String tenantId, String id) {
        TenantModelConfig entity = owned(tenantId, id);
        boolean wasActive = Boolean.TRUE.equals(entity.getIsActive());
        mapper.deleteById(id);
        if (wasActive) {
            log.info("【模型配置】租户 {} 删除了生效配置 {}，回退到未配置状态", tenantId, entity.getName());
        }
        return wasActive;
    }

    /** 单生效切换：同租户先全部下线，再激活目标行。 */
    @Transactional
    public Optional<ConfigRow> enable(String tenantId, String id) {
        TenantModelConfig entity = owned(tenantId, id);
        for (TenantModelConfig config : mapper.selectList(query(tenantId))) {
            if (Boolean.TRUE.equals(config.getIsActive())) {
                config.setIsActive(false);
                config.setUpdateTime(LocalDateTime.now());
                mapper.updateById(config);
            }
        }
        entity.setIsActive(true);
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
        log.info("【模型配置】租户 {} 切换生效配置: {} ({})", tenantId, entity.getName(), entity.getModelName());
        return Optional.of(toRow(entity));
    }

    // ---------- 内部 ----------

    private LambdaQueryWrapper<TenantModelConfig> query(String tenantId) {
        return new LambdaQueryWrapper<TenantModelConfig>()
                .eq(TenantModelConfig::getTenantId, tenantId)
                .orderByDesc(TenantModelConfig::getIsActive)
                .orderByDesc(TenantModelConfig::getUpdateTime);
    }

    private Optional<TenantModelConfig> findActive(String tenantId) {
        // 生产环境 wrapper 已按 tenantId + is_active 过滤；此处再显式过滤一次，保证语义自洽且便于内存桩测试
        return mapper.selectList(query(tenantId)).stream()
                .filter(c -> tenantId.equals(c.getTenantId()))
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .findFirst();
    }

    private TenantModelConfig owned(String tenantId, String id) {
        TenantModelConfig entity = mapper.selectById(id);
        if (entity == null || entity.getDeleted() != null && entity.getDeleted() == 1
                || !tenantId.equals(entity.getTenantId())) {
            throw new IllegalArgumentException("配置不存在或不属于当前租户");
        }
        return entity;
    }

    private void validate(SaveRequest request) {
        if (request == null
                || request.name() == null || request.name().isBlank()
                || request.baseUrl() == null || request.baseUrl().isBlank()
                || request.modelName() == null || request.modelName().isBlank()) {
            throw new IllegalArgumentException("配置名称、接入 BaseURL 与模型名称均为必填项");
        }
        if (request.name().trim().length() > 80) {
            throw new IllegalArgumentException("配置名称过长（上限 80 字符）");
        }
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "custom" : provider.trim().toLowerCase();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long parseTenant(String tenantId) {
        try {
            return Long.valueOf(tenantId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("租户 ID 必须为数字: " + tenantId);
        }
    }

    private ConfigRow toRow(TenantModelConfig entity) {
        String key = entity.getApiKey();
        String keyTail = key == null || key.isBlank() ? null
                : key.substring(0, Math.min(5, key.length())) + "***";
        return new ConfigRow(entity.getId(), entity.getName(), entity.getProvider(), entity.getBaseUrl(),
                entity.getModelName(), entity.getTemperature(), entity.getMaxTokens(),
                key != null && !key.isBlank(), keyTail, Boolean.TRUE.equals(entity.getIsActive()),
                entity.getCreateTime() != null ? entity.getCreateTime().toString().replace('T', ' ').substring(0, 16) : "");
    }
}
