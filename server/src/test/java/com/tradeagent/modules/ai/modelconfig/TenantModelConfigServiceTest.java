package com.tradeagent.modules.ai.modelconfig;

import com.tradeagent.modules.ai.intent.RoutingModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户模型配置服务单测：内存版 Mapper 桩验证 CRUD/单生效切换/服务端密钥解析/掩码。
 * 不依赖数据库与 Spring 上下文。
 */
class TenantModelConfigServiceTest {

    /** 内存版 BaseMapper 桩：行存内存表；selectList/selectOne 依据 wrapper 首个参数（tenantId）做隔离过滤。 */
    static class InMemoryMapper {
        final List<TenantModelConfig> rows = new ArrayList<>();

        @SuppressWarnings("unchecked")
        private static String wrapperTenant(Object wrapper) {
            try {
                var pairs = (java.util.Map<Object, Object>) wrapper.getClass()
                        .getMethod("getParamNameValuePairs").invoke(wrapper);
                return pairs.isEmpty() ? null : String.valueOf(pairs.values().iterator().next());
            } catch (Exception e) {
                return null;
            }
        }

        TenantModelConfigMapper proxy() {
            return (TenantModelConfigMapper) Proxy.newProxyInstance(
                    TenantModelConfigMapper.class.getClassLoader(), new Class<?>[]{TenantModelConfigMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> { rows.add((TenantModelConfig) args[0]); yield 1; }
                        case "updateById" -> 1;
                        case "deleteById" -> { rows.removeIf(r -> r.getId().equals(args[0])); yield 1; }
                        case "selectById" -> rows.stream().filter(r -> r.getId().equals(args[0])).findFirst().orElse(null);
                        case "selectList", "selectOne" -> {
                            List<TenantModelConfig> result = new ArrayList<>(rows);
                            String tenant = wrapperTenant(args[0]);
                            if (tenant != null) result.removeIf(r -> !tenant.equals(r.getTenantId()));
                            yield result;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private TenantModelConfigService service(InMemoryMapper m) {
        return new TenantModelConfigService(m.proxy());
    }

    private TenantModelConfigService.SaveRequest req(String name, String apiKey) {
        return new TenantModelConfigService.SaveRequest(name, "aliyun",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey, "qwen-plus", 0.7, 4096);
    }

    @Test
    @DisplayName("首份配置自动生效；列表只回掩码，绝无明文密钥")
    void firstConfigAutoActivatesAndRowsAreMasked() {
        InMemoryMapper m = new InMemoryMapper();
        TenantModelConfigService service = service(m);

        var row = service.create("1001", req("通义千问-生产", "sk-abcdefghijklmnop"));
        assertTrue(row.active(), "租户首份配置应自动生效");
        assertTrue(row.hasApiKey());
        assertEquals("sk-ab***", row.keyTail());

        assertTrue(service.list("1001").stream().noneMatch(r ->
                String.valueOf(r.keyTail()).contains("cdefghij")), "掩码之外不得泄露密钥其余部分");
    }

    @Test
    @DisplayName("启用切换：同租户内单生效，旧行自动下线")
    void enableSwitchesSingleActive() {
        InMemoryMapper m = new InMemoryMapper();
        TenantModelConfigService service = service(m);
        var first = service.create("1001", req("qwen", "sk-qwen-key-123456"));
        var second = service.create("1001", req("deepseek", "sk-deep-key-654321"));
        assertFalse(second.active(), "第二份不应自动生效");

        service.enable("1001", second.id());

        var active = service.activeMasked("1001").orElseThrow();
        assertEquals("deepseek", active.name());
        assertTrue(second.hasApiKey());
        var rows = service.list("1001");
        assertEquals(1, rows.stream().filter(TenantModelConfigService.ConfigRow::active).count(), "生效配置必须唯一");
        assertTrue(rows.stream().filter(r -> r.name().equals("qwen")).noneMatch(TenantModelConfigService.ConfigRow::active));
    }

    @Test
    @DisplayName("resolveActive 供服务端解析明文密钥并构造租户模型配置")
    void resolveActiveBuildsTenantModelConfig() {
        InMemoryMapper m = new InMemoryMapper();
        TenantModelConfigService service = service(m);
        service.create("1001", req("通义千问-生产", "sk-live-key-987654"));

        Optional<RoutingModelConfig> resolved = service.resolveActive("1001");
        assertTrue(resolved.isPresent());
        assertEquals("qwen-plus", resolved.get().getModelName());
        assertEquals("sk-live-key-987654", resolved.get().getApiKey());
        assertEquals(1001L, resolved.get().getTenantId());
        assertTrue(service.resolveActive("2002").isEmpty(), "其他租户不得解析到配置");
    }

    @Test
    @DisplayName("删除生效配置后回退为未配置；编辑留空密钥保持原值")
    void deleteActiveFallsBackAndEditKeepsKey() {
        InMemoryMapper m = new InMemoryMapper();
        TenantModelConfigService service = service(m);
        var only = service.create("1001", req("qwen", "sk-keep-me-123456"));

        var updated = service.update("1001", only.id(),
                new TenantModelConfigService.SaveRequest("qwen-改名", "aliyun",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-max", 0.5, 2048))
                .orElseThrow();
        assertTrue(updated.hasApiKey(), "编辑留空密钥应保持原密钥");
        assertEquals("qwen-max", updated.modelName());

        assertTrue(service.delete("1001", only.id()), "删除生效配置应返回 wasActive=true");
        assertTrue(service.activeMasked("1001").isEmpty(), "删除后租户回退为未配置");
    }

    @Test
    @DisplayName("缺名称/端点/模型的请求被拒绝")
    void validationRejectsIncompleteSave() {
        InMemoryMapper m = new InMemoryMapper();
        TenantModelConfigService service = service(m);
        try {
            service.create("1001", new TenantModelConfigService.SaveRequest(null, "aliyun",
                    "https://x", null, "qwen-plus", null, null));
            assertFalse(true, "应抛出参数校验异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("必填"));
        }
    }
}
