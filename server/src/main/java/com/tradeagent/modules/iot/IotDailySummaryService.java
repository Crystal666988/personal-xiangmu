package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日设备情况总结 → 飞书多维表格。
 *
 * 聚合当天各设备的 min/max/avg 指标与异常数，生成汇总 JSON 落库；
 * 配置了飞书自建应用（feishu.app-id/secret + bitable app/table token）时，
 * 通过开放平台真实写入多维表格记录；未配置时如实标记 LOCAL_ONLY。
 * 定时任务每日 23:00 自动执行，也支持手动触发。
 */
@Slf4j
@Service
public class IotDailySummaryService {

    private final IotDeviceDataMapper dataMapper;
    private final IotAnomalyEventMapper anomalyMapper;
    private final IotDailySummaryMapper summaryMapper;
    private final IotThresholdRuleEngine ruleEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${feishu.app-id:}")
    private String appId;

    @Value("${feishu.app-secret:}")
    private String appSecret;

    @Value("${feishu.bitable-app-token:}")
    private String bitableAppToken;

    @Value("${feishu.bitable-table-id:}")
    private String bitableTableId;

    public IotDailySummaryService(IotDeviceDataMapper dataMapper, IotAnomalyEventMapper anomalyMapper,
                                  IotDailySummaryMapper summaryMapper, IotThresholdRuleEngine ruleEngine) {
        this.dataMapper = dataMapper;
        this.anomalyMapper = anomalyMapper;
        this.summaryMapper = summaryMapper;
        this.ruleEngine = ruleEngine;
    }

    /** 按天聚合（简单聚合在 SQL 侧做不完 min/max 分指标，此处内存聚合，量级为天 × 设备，可承受）。 */
    public List<Map<String, Object>> summarize(LocalDate date, String tenantId) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<IotDeviceData> points = dataMapper.selectList(new LambdaQueryWrapper<IotDeviceData>()
                .eq(IotDeviceData::getTenantId, tenantId)
                .ge(IotDeviceData::getCollectedAt, dayStart)
                .lt(IotDeviceData::getCollectedAt, dayEnd));
        List<IotAnomalyEvent> anomalies = anomalyMapper.selectList(new LambdaQueryWrapper<IotAnomalyEvent>()
                .eq(IotAnomalyEvent::getTenantId, tenantId)
                .ge(IotAnomalyEvent::getCreateTime, dayStart)
                .lt(IotAnomalyEvent::getCreateTime, dayEnd));

        Map<String, Map<String, Object>> byDevice = new LinkedHashMap<>();
        for (IotDeviceData point : points) {
            byDevice.computeIfAbsent(point.getDeviceId(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("farmId", point.getFarmId());
                m.put("metrics", new LinkedHashMap<String, List<Double>>());
                return m;
            });
            @SuppressWarnings("unchecked")
            Map<String, List<Double>> metrics = (Map<String, List<Double>>) byDevice.get(point.getDeviceId()).get("metrics");
            metrics.computeIfAbsent(point.getMetric(), k -> new ArrayList<>()).add(point.getMetricValue());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : byDevice.entrySet()) {
            long anomalyCount = anomalies.stream()
                    .filter(a -> entry.getKey().equals(a.getDeviceId()))
                    .count();
            Map<String, Object> ranges = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, List<Double>> metrics = (Map<String, List<Double>>) entry.getValue().get("metrics");
            metrics.forEach((metric, values) -> {
                double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                ranges.put(ruleEngine.labelOf(metric), String.format("%.1f~%.1f (均值 %.1f)", min, max, avg));
            });
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("farmId", entry.getValue().get("farmId"));
            row.put("deviceId", entry.getKey());
            row.put("points", points.stream().filter(p -> entry.getKey().equals(p.getDeviceId())).count());
            row.put("ranges", ranges);
            row.put("anomalyCount", anomalyCount);
            result.add(row);
        }
        return result;
    }

    /** 定时任务：每日 23:00 自动聚合当天设备情况并推送飞书多维表格。 */
    @Scheduled(cron = "${iot.summary-cron:0 0 23 * * ?}")
    public void scheduledDailySummary() {
        try {
            String state = summarizeAndPush(java.time.LocalDate.now(), "1001");
            log.info("【IoT 日报】定时总结完成, 飞书状态: {}", state);
        } catch (Exception e) {
            log.warn("【IoT 日报】定时总结失败: {}", e.getMessage());
        }
    }

    /** 手动触发：聚合今天并推送飞书多维表格（未配置凭证时仅本地落库）。 */
    public String summarizeAndPush(LocalDate date, String tenantId) {
        List<Map<String, Object>> summary = summarize(date, tenantId);
        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new IllegalStateException("总结序列化失败", e);
        }
        // 按栋舍拆行：一行 = 栋舍 × 当天（演示数据量小，取设备级合并到栋舍行）
        String feishuState;
        if (appId.isBlank() || appSecret.isBlank() || bitableAppToken.isBlank() || bitableTableId.isBlank()) {
            feishuState = "LOCAL_ONLY";
            log.info("【IoT 日报】{} 飞书凭证未配置，总结仅本地落库 ({} 台设备)", date, summary.size());
        } else {
            feishuState = pushToBitable(date, tenantId, summary);
        }
        IotDailySummary entity = IotDailySummary.builder()
                .tenantId(tenantId).farmId("ALL").summaryDate(date)
                .summaryJson(summaryJson).feishuState(feishuState)
                .createTime(LocalDateTime.now())
                .build();
        summaryMapper.insert(entity);
        return feishuState;
    }

    /** 真实飞书开放平台调用：tenant_access_token → Bitable 新增记录（按栋舍一行）。 */
    private String pushToBitable(LocalDate date, String tenantId, List<Map<String, Object>> summary) {
        try {
            String tokenBody = objectMapper.writeValueAsString(Map.of("app_id", appId, "app_secret", appSecret));
            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> tokenResp = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String token = objectMapper.readTree(tokenResp.body()).path("tenant_access_token").asText("");
            if (token.isBlank()) {
                log.warn("【IoT 日报】获取 tenant_access_token 失败: {}", tokenResp.body());
                return "FAILED";
            }
            for (Map<String, Object> row : summary) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("日期", date.toString());
                fields.put("文本", String.format("%s %s | 点数%s | 指标%s | 异常%s",
                        row.get("farmId"), row.get("deviceId"), row.get("points"),
                        row.get("ranges"), row.get("anomalyCount")));
                @SuppressWarnings("unchecked")
                Map<String, Object> body = Map.of(
                        "fields", fields);
                String url = String.format(
                        "https://open.feishu.cn/open-apis/bitable/v1/apps/%s/tables/%s/records",
                        bitableAppToken, bitableTableId);
                HttpRequest recordReq = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> recordResp = httpClient.send(recordReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (recordResp.statusCode() != 200) {
                    log.warn("【IoT 日报】Bitable 写入失败 HTTP {}: {}", recordResp.statusCode(), recordResp.body());
                    return "FAILED";
                }
            }
            log.info("【IoT 日报】{} 已写入飞书多维表格 {} 行", date, summary.size());
            return "BITABLE_SENT";
        } catch (Exception e) {
            log.warn("【IoT 日报】飞书推送异常: {}", e.getMessage());
            return "FAILED";
        }
    }
}
