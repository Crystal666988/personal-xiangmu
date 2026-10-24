package com.tradeagent.modules.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 设备数据事件传输层装配。
 *
 * iot.kafka.enabled=true → Kafka 生产者（需要 spring.kafka.bootstrap-servers 等标准配置）；
 * 默认 → 进程内日志发布器（演示环境零依赖跑通全链路）。
 * 消费侧统一依赖 IotDeviceEventPublisher 抽象，切换传输层无感。
 */
@Slf4j
@Configuration
public class IotEventTransportConfig {

    @Bean
    @ConditionalOnProperty(name = "iot.kafka.enabled", havingValue = "true")
    public IotDeviceEventPublisher kafkaDeviceEventPublisher(
            org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        return data -> {
            try {
                kafkaTemplate.send("iot-device-data", data.getDeviceId(),
                        objectMapper.writeValueAsString(data));
            } catch (Exception e) {
                log.warn("【Kafka】设备数据转发失败: {}", e.getMessage());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(IotDeviceEventPublisher.class)
    public IotDeviceEventPublisher loggingDeviceEventPublisher() {
        return data -> log.debug("【IoT 事件】{} / {} / {} = {}",
                data.getFarmId(), data.getDeviceId(), data.getMetric(), data.getMetricValue());
    }
}
