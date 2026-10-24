package com.tradeagent.modules.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MQTT 设备接入网关（温氏养殖智能设备）。
 *
 * 仅当 iot.mqtt.enabled=true 时装配（演示环境默认关闭，避免无 Broker 时空转）；
 * 订阅 iot.mqtt.topic（如 wens/farm/+/data），payload 约定：
 * {"farmId":"farm_001","deviceId":"dev_env_01","collectedAt":"2026-10-24 19:00:00",
 *  "metrics":[{"metric":"temperature","value":28.5,"unit":"℃"}, ...]}
 * 收到后交给 IotIngestService，与 HTTP 模拟上报走完全相同的入库/规则/预警链路。
 *
 * 生产 Broker 推荐 EMQX（规则引擎可直转 Kafka）；Kafka 侧由 IotKafkaConfig 在
 * iot.kafka.enabled=true 时装配生产者，用于把数据点转发给离线分析消费者。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "iot.mqtt.enabled", havingValue = "true")
public class IotMqttConfig {

    private final IotIngestService ingestService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${iot.mqtt.broker:tcp://127.0.0.1:1883}")
    private String broker;

    @Value("${iot.mqtt.client-id:tradeagent-iot}")
    private String clientId;

    @Value("${iot.mqtt.topic:wens/farm/+/data}")
    private String topic;

    public IotMqttConfig(IotIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Bean
    public MqttPahoClientFactory iotMqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        var options = new org.eclipse.paho.client.mqttv3.MqttConnectOptions();
        options.setServerURIs(new String[]{broker});
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        factory.setConnectionOptions(options);
        return factory;
    }

    @Bean
    public MessageChannel iotMqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter iotMqttInboundAdapter(
            MqttPahoClientFactory iotMqttClientFactory, MessageChannel iotMqttInputChannel) {
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId + "_in", iotMqttClientFactory, topic);
        adapter.setOutputChannel(iotMqttInputChannel);
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "iotMqttInputChannel")
    public MessageHandler iotMqttIngestHandler() {
        return message -> {
            try {
                String payload = new String((byte[]) message.getPayload(), StandardCharsets.UTF_8);
                JsonNode root = objectMapper.readTree(payload);
                List<IotIngestService.MetricPoint> metrics = new ArrayList<>();
                for (var metricNode : root.path("metrics")) {
                    metrics.add(new IotIngestService.MetricPoint(
                            metricNode.path("metric").asText(""),
                            metricNode.path("value").asDouble(0),
                            metricNode.path("unit").asText("")));
                }
                IotIngestService.IngestRequest request = new IotIngestService.IngestRequest(
                        "1001",
                        root.path("farmId").asText("unknown_farm"),
                        root.path("deviceId").asText("unknown_device"),
                        root.path("collectedAt").asText(""),
                        metrics);
                IotIngestService.IngestResult result = ingestService.ingest(request);
                log.info("【MQTT 接入】设备 {} 入库 {} 点, 异常 {} 条",
                        request.deviceId(), result.pointsSaved(), result.anomalies().size());
            } catch (Exception e) {
                log.warn("【MQTT 接入】payload 处理失败: {}", e.getMessage());
            }
        };
    }
}
