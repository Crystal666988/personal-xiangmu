package com.tradeagent.modules.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Iterator;

/**
 * 设备影子服务（Device Shadow）—— 离线指令不丢的核心。
 *
 * 模型（对齐 AWS IoT Shadow / ThingsBoard）：
 *   desired  = 云端期望状态（下发指令时写入，如 {"fan":"on","targetTemp":26}）
 *   reported = 设备最新上报状态（每次 ingest 时合并写入）
 *   pending  = desired 中尚未被 reported 覆盖的差量 = 设备离线期间漏执行的指令
 *
 * 设备上线（下一次上报）时通过 ingest 响应或 GET /shadow/{id} 拿到 pending 补执行；
 * 执行完上报状态 → reported 追平 desired → pending 清空。version 为乐观锁版本号。
 */
@Slf4j
@Service
public class IotShadowService {

    private final IotDeviceShadowMapper shadowMapper;
    private final ObjectMapper json = new ObjectMapper();

    public IotShadowService(IotDeviceShadowMapper shadowMapper) {
        this.shadowMapper = shadowMapper;
    }

    /** 下发指令时：把指令参数合入 desired（同名键覆盖），并重算 pending。 */
    public void mergeDesired(String tenantId, String deviceId, String commandJson) {
        IotDeviceShadow shadow = loadOrInit(tenantId, deviceId);
        ObjectNode desired = parseOrNew(shadow.getDesiredJson());
        try {
            JsonNode cmd = json.readTree(commandJson);
            if (cmd.isObject()) {
                cmd.fields().forEachRemaining(e -> {
                    // command 本身不是状态，其余参数（fan/targetTemp/speed...）视为期望状态
                    if (!"command".equals(e.getKey())) desired.set(e.getKey(), e.getValue());
                });
                // 指令名也记录，设备按 command 路由动作
                desired.put("lastCommand", cmd.path("command").asText(""));
            }
        } catch (Exception ignored) {}
        shadow.setDesiredJson(desired.toString());
        recomputePending(shadow);
        shadowMapper.updateById(shadow);
    }

    /** 设备上报时：合并 reported；返回 pending（若有，设备应补执行）。 */
    public String mergeReported(String tenantId, String deviceId, java.util.List<IotIngestService.MetricPoint> metrics) {
        IotDeviceShadow shadow = loadOrInit(tenantId, deviceId);
        ObjectNode reported = parseOrNew(shadow.getReportedJson());
        metrics.forEach(m -> reported.put(m.metric(), m.value()));
        reported.put("lastSeen", LocalDateTime.now().toString());
        shadow.setReportedJson(reported.toString());
        recomputePending(shadow);
        shadowMapper.updateById(shadow);
        return hasPending(shadow) ? shadow.getPendingJson() : null;
    }

    public IotDeviceShadow get(String tenantId, String deviceId) {
        IotDeviceShadow shadow = shadowMapper.selectById(deviceId);
        if (shadow == null || !tenantId.equals(shadow.getTenantId())) {
            throw new IllegalArgumentException("设备影子不存在: " + deviceId);
        }
        return shadow;
    }

    private IotDeviceShadow loadOrInit(String tenantId, String deviceId) {
        IotDeviceShadow shadow = shadowMapper.selectById(deviceId);
        if (shadow == null) {
            shadow = IotDeviceShadow.builder()
                    .deviceId(deviceId).tenantId(tenantId)
                    .desiredJson("{}").reportedJson("{}").pendingJson("{}")
                    .version(0L).updateTime(LocalDateTime.now())
                    .build();
            shadowMapper.insert(shadow);
        }
        return shadow;
    }

    /** pending = desired 中 reported 尚未覆盖（或值不等）的键。 */
    private void recomputePending(IotDeviceShadow shadow) {
        ObjectNode pending = json.createObjectNode();
        try {
            JsonNode desired = json.readTree(shadow.getDesiredJson() == null ? "{}" : shadow.getDesiredJson());
            JsonNode reported = json.readTree(shadow.getReportedJson() == null ? "{}" : shadow.getReportedJson());
            Iterator<String> keys = desired.fieldNames();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("lastCommand".equals(key)) continue; // 路由提示非期望状态, 不参与差量
                JsonNode want = desired.get(key);
                JsonNode have = reported.get(key);
                if (have == null || !valueEquals(want, have)) {
                    pending.set(key, want);
                }
            }
        } catch (Exception ignored) {}
        shadow.setPendingJson(pending.toString());
        shadow.setVersion((shadow.getVersion() == null ? 0 : shadow.getVersion()) + 1);
    }

    /** 值等价比较：数值走 double 比较（26 与 26.0 等价），其余走文本。 */
    private boolean valueEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) return a == b;
        if (a.isNumber() && b.isNumber()) return a.asDouble() == b.asDouble();
        return a.asText("").equals(b.asText(""));
    }

    private boolean hasPending(IotDeviceShadow shadow) {
        try {
            return shadow.getPendingJson() != null
                    && json.readTree(shadow.getPendingJson()).size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private ObjectNode parseOrNew(String raw) {
        try {
            JsonNode node = json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            return node.isObject() ? (ObjectNode) node : json.createObjectNode();
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }
}
