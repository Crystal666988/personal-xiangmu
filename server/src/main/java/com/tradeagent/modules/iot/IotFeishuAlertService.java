package com.tradeagent.modules.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书人工预警推送：异常事件聚合成交互卡片，经群机器人 Incoming Webhook 推送。
 *
 * 真实性约定：配置 feishu.iot-alert-webhook-url 后为真实 HTTP 推送（在飞书群里
 * 添加"自定义机器人"即可获得地址，30 秒完成）；未配置时如实标记 SKIPPED_NO_WEBHOOK，
 * 绝不伪造"已推送"。
 */
@Slf4j
@Service
public class IotFeishuAlertService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${feishu.iot-alert-webhook-url:}")
    private String webhookUrl;

    public enum AlertState { SENT, SKIPPED_NO_WEBHOOK, FAILED }

    public record AlertResult(AlertState state, String detail) {}

    /**
     * 聚合推送：一次上报产生的所有异常合并为一张卡片，避免刷屏。
     *
     * @return 卡片推送状态（SENT / SKIPPED_NO_WEBHOOK / FAILED）
     */
    public AlertResult pushAnomalyAlert(String farmId, String deviceId, List<IotAnomalyEvent> anomalies) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("【IoT 预警】未配置飞书群机器人 Webhook，{} 条异常仅落库 (设备 {})", anomalies.size(), deviceId);
            return new AlertResult(AlertState.SKIPPED_NO_WEBHOOK, "未配置 feishu.iot-alert-webhook-url");
        }
        try {
            StringBuilder md = new StringBuilder();
            for (IotAnomalyEvent event : anomalies) {
                md.append(String.format("**%s**: %s（当前 %.1f）\n",
                        metricLabel(event.getMetric()), event.getMessage(), event.getMetricValue()));
            }
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("title", Map.of("tag", "plain_text", "content", "畜禽舍环境异常预警 · 需人工确认"));
            header.put("template", anomalies.stream().anyMatch(a -> "CRITICAL".equals(a.getSeverity())) ? "red" : "orange");

            StringBuilder noteText = new StringBuilder("TradeAgent IoT 环控预警 · ")
                    .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            Map<String, Object> noteTextMap = new LinkedHashMap<>();
            noteTextMap.put("tag", "plain_text");
            noteTextMap.put("content", noteText.toString());
            Map<String, Object> noteElement = new LinkedHashMap<>();
            noteElement.put("tag", "note");
            noteElement.put("elements", List.of(noteTextMap));

            Map<String, Object> mdTextMap = new LinkedHashMap<>();
            mdTextMap.put("tag", "lark_md");
            mdTextMap.put("content", String.format("**栋舍**: %s\\n**设备**: %s\\n%s", farmId, deviceId, md));
            Map<String, Object> mdElement = new LinkedHashMap<>();
            mdElement.put("tag", "div");
            mdElement.put("text", mdTextMap);

            Map<String, Object> hrElement = new LinkedHashMap<>();
            hrElement.put("tag", "hr");

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("config", Map.of("wide_screen_mode", true));
            card.put("header", header);
            card.put("elements", List.of(mdElement, hrElement, noteElement));

            String body = objectMapper.writeValueAsString(Map.of("msg_type", "interactive", "card", card));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl.trim()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if (root.path("code").asInt(0) == 0 || root.path("StatusCode").asInt(0) == 0) {
                    log.info("【IoT 预警】飞书卡片推送成功: 设备 {} 异常 {} 条", deviceId, anomalies.size());
                    return new AlertResult(AlertState.SENT, "已推送至飞书群");
                }
                return new AlertResult(AlertState.FAILED, "飞书返回: " + response.body());
            }
            return new AlertResult(AlertState.FAILED, "HTTP " + response.statusCode());
        } catch (Exception e) {
            log.warn("【IoT 预警】飞书推送异常: {}", e.getMessage());
            return new AlertResult(AlertState.FAILED, e.getMessage());
        }
    }

    private String metricLabel(String metric) {
        return switch (metric) {
            case "temperature" -> "舍内温度";
            case "humidity" -> "相对湿度";
            case "nh3" -> "氨气浓度";
            case "co2" -> "二氧化碳浓度";
            case "feed" -> "料线状态";
            case "water" -> "饮水流量";
            default -> metric;
        };
    }
}
