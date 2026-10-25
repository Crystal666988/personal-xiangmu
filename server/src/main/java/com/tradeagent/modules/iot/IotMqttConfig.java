package com.tradeagent.modules.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "iot.mqtt.enabled", havingValue = "true")
public class IotMqttConfig implements MqttCallback {

    private final IotIngestService ingestService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MqttClient mqttClient;

    @Value("${iot.mqtt.broker:tcp://127.0.0.1:1883}")
    private String broker;

    @Value("${iot.mqtt.client-id:tradeagent-iot}")
    private String clientId;

    @Value("${iot.mqtt.topic:wens/farm/+/data}")
    private String topic;

    @Value("${iot.mqtt.cmd-topic-prefix:wens/device}")
    private String cmdTopicPrefix;

    public IotMqttConfig(IotIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostConstruct
    public void connect() {
        try {
            mqttClient = new MqttClient(broker, clientId + "_" + System.currentTimeMillis() % 10000, new MemoryPersistence());
            mqttClient.setCallback(this);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            mqttClient.connect(options);
            mqttClient.subscribe(topic, 1);
            log.info("MQTT connected to {} subscribing {}", broker, topic);
        } catch (Exception e) {
            log.warn("MQTT connect failed ({}), device data will use HTTP entry: {}", broker, e.getMessage());
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) mqttClient.disconnect();
        } catch (Exception ignored) {}
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost, auto-reconnecting: {}", cause.getMessage());
    }

    @Override
    public void messageArrived(String topicName, MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
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
            log.info("MQTT ingest device {} saved {} points, {} anomalies",
                    request.deviceId(), result.pointsSaved(), result.anomalies().size());
        } catch (Exception e) {
            log.warn("MQTT payload processing failed: {}", e.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {}

    public void publishCommand(String deviceId, String commandJson) {
        try {
            MqttMessage msg = new MqttMessage(commandJson.getBytes(StandardCharsets.UTF_8));
            msg.setQos(1);
            mqttClient.publish(cmdTopicPrefix + "/" + deviceId + "/cmd", msg);
        } catch (Exception e) {
            log.warn("MQTT command publish failed: {}", e.getMessage());
        }
    }

    /** Paho 自带内存持久化即可（CleanSession=true 时只是会话载体）。 */
    private static final class MemoryPersistence extends org.eclipse.paho.client.mqttv3.persist.MemoryPersistence {}
}
