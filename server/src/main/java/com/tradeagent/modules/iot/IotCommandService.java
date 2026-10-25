package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备指令下发服务（Downlink）：向指定设备发布控制指令。
 * MQTT 启用时通过 IotMqttConfig.publishCommand 真实发布到设备 cmd 主题；
 * MQTT 未启用时如实标记 NOT_CONNECTED。
 */
@Slf4j
@Service
public class IotCommandService {

    private final IotCommandLogMapper commandLogMapper;
    private final IotDeviceService deviceService;
    private final ObjectProvider<IotMqttConfig> mqttConfigProvider;

    public IotCommandService(IotCommandLogMapper commandLogMapper, IotDeviceService deviceService,
                             ObjectProvider<IotMqttConfig> mqttConfigProvider) {
        this.commandLogMapper = commandLogMapper;
        this.deviceService = deviceService;
        this.mqttConfigProvider = mqttConfigProvider;
    }

    public record CommandResult(String state, String detail) {}

    public CommandResult send(String tenantId, String deviceId, String commandJson) {
        deviceService.require(tenantId, deviceId);
        String state;
        String detail;
        IotMqttConfig mqtt = mqttConfigProvider.getIfAvailable();
        if (mqtt != null) {
            mqtt.publishCommand(deviceId, commandJson);
            state = "SENT_MQTT";
            detail = "已通过 MQTT 发布至设备 cmd 主题";
        } else {
            state = "NOT_CONNECTED";
            detail = "MQTT 未启用，指令已记录";
        }
        IotCommandLog logRow = IotCommandLog.builder()
                .tenantId(tenantId).deviceId(deviceId)
                .commandJson(commandJson).state(state)
                .createTime(LocalDateTime.now())
                .build();
        commandLogMapper.insert(logRow);
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
