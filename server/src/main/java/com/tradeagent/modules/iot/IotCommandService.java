package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备指令下发服务（Downlink）+ 幂等 + 影子联动。
 *
 * 幂等：MQTT QoS1 是"至少一次"送达，网关重发会造重复指令；
 * 调用方携带 messageId（idempotencyKey）时，同设备同键只执行一次，
 * 重复请求直接返回首次结果（DB 唯一键兜底，并发也安全）。
 *
 * 影子：指令同时合入设备影子的 desired 侧，设备离线也不丢，
 * 上线后通过 pending 差量补执行。
 */
@Slf4j
@Service
public class IotCommandService {

    private final IotCommandLogMapper commandLogMapper;
    private final IotDeviceService deviceService;
    private final ObjectProvider<IotMqttConfig> mqttConfigProvider;
    private final IotShadowService shadowService;

    public IotCommandService(IotCommandLogMapper commandLogMapper, IotDeviceService deviceService,
                             ObjectProvider<IotMqttConfig> mqttConfigProvider, IotShadowService shadowService) {
        this.commandLogMapper = commandLogMapper;
        this.deviceService = deviceService;
        this.mqttConfigProvider = mqttConfigProvider;
        this.shadowService = shadowService;
    }

    public record CommandResult(String state, String detail, boolean duplicate) {
        public CommandResult(String state, String detail) { this(state, detail, false); }
    }

    public CommandResult send(String tenantId, String deviceId, String commandJson, String idempotencyKey) {
        deviceService.require(tenantId, deviceId);

        // 幂等检查：同设备同 key 已执行过 → 直接返回，不再下发（QoS1 重放防护）
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IotCommandLog existing = commandLogMapper.selectOne(new LambdaQueryWrapper<IotCommandLog>()
                    .eq(IotCommandLog::getDeviceId, deviceId)
                    .eq(IotCommandLog::getIdempotencyKey, idempotencyKey)
                    .last("LIMIT 1"));
            if (existing != null) {
                log.info("【指令幂等】{} 重复请求 {} -> 返回已有结果，不重复下发", deviceId, idempotencyKey);
                return new CommandResult(existing.getState(), "重复请求（幂等命中），未重复下发", true);
            }
        }

        String state;
        String detail;
        IotMqttConfig mqtt = mqttConfigProvider.getIfAvailable();
        if (mqtt != null) {
            mqtt.publishCommand(deviceId, commandJson);
            state = "SENT_MQTT";
            detail = "已通过 MQTT 发布至设备 cmd 主题";
        } else {
            state = "NOT_CONNECTED";
            detail = "MQTT 未启用，指令已写入设备影子待设备上线补执行";
        }

        // 指令写入影子 desired：设备离线也不丢，上线补执行
        shadowService.mergeDesired(tenantId, deviceId, commandJson);

        IotCommandLog logRow = IotCommandLog.builder()
                .tenantId(tenantId).deviceId(deviceId)
                .commandJson(commandJson).state(state)
                .idempotencyKey(idempotencyKey)
                .createTime(LocalDateTime.now())
                .build();
        try {
            commandLogMapper.insert(logRow);
        } catch (DuplicateKeyException e) {
            // 并发下的双保险：唯一键冲突 = 已有同 key 指令
            return new CommandResult(state, "重复请求（并发幂等命中）", true);
        }
        return new CommandResult(state, detail);
    }

    public List<IotCommandLog> recentCommands(String tenantId, String deviceId, int limit) {
        return commandLogMapper.selectList(new LambdaQueryWrapper<IotCommandLog>()
                .eq(IotCommandLog::getTenantId, tenantId)
                .eq(deviceId != null && !deviceId.isBlank(), IotCommandLog::getDeviceId, deviceId)
                .orderByDesc(IotCommandLog::getCreateTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }
}
