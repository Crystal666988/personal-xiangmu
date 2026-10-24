package com.tradeagent.modules.ai.modelconfig;

import com.tradeagent.core.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户级模型配置管理接口（标准 CRUD + 单生效切换）。
 *
 * 安全约定：列表/详情只回掩码 key_tail，API Key 明文仅随保存请求进入服务端，
 * 对话/审批/恢复链路由 {@link TenantModelConfigService#resolveActive(String)} 在服务端解析。
 */
@RestController
@RequestMapping("/api/v1/model-configs")
public class ModelConfigController {

    private final TenantModelConfigService service;

    public ModelConfigController(TenantModelConfigService service) {
        this.service = service;
    }

    public record SaveRequest(String name, String provider, String baseUrl, String apiKey,
                              String modelName, Double temperature, Integer maxTokens) {}

    @GetMapping
    public ApiResponse<List<TenantModelConfigService.ConfigRow>> list(
            @RequestParam("tenantId") String tenantId) {
        return ApiResponse.success(service.list(tenantId));
    }

    @GetMapping("/active")
    public ApiResponse<TenantModelConfigService.ConfigRow> active(
            @RequestParam("tenantId") String tenantId) {
        return ApiResponse.success(service.activeMasked(tenantId).orElse(null));
    }

    @PostMapping
    public ApiResponse<TenantModelConfigService.ConfigRow> create(
            @RequestParam("tenantId") String tenantId,
            @RequestBody SaveRequest request) {
        return ApiResponse.success(service.create(tenantId, toServiceRequest(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<TenantModelConfigService.ConfigRow> update(
            @PathVariable("id") String id,
            @RequestParam("tenantId") String tenantId,
            @RequestBody SaveRequest request) {
        return ApiResponse.success(service.update(tenantId, id, toServiceRequest(request))
                .orElseThrow(() -> new IllegalArgumentException("配置不存在")));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(
            @PathVariable("id") String id,
            @RequestParam("tenantId") String tenantId) {
        return ApiResponse.success(service.delete(tenantId, id));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<TenantModelConfigService.ConfigRow> enable(
            @PathVariable("id") String id,
            @RequestParam("tenantId") String tenantId) {
        return ApiResponse.success(service.enable(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在")));
    }

    private TenantModelConfigService.SaveRequest toServiceRequest(SaveRequest request) {
        if (request == null) throw new IllegalArgumentException("请求体不能为空");
        return new TenantModelConfigService.SaveRequest(request.name(), request.provider(),
                request.baseUrl(), request.apiKey(), request.modelName(),
                request.temperature(), request.maxTokens());
    }
}
