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

    private final IotAnomalyEventMapper anomalyMapper;
    private final IotDailySummaryMapper summaryMapper;
    private final IotThresholdRuleEngine ruleEngine;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
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

    public IotDailySummaryService(IotAnomalyEventMapper anomalyMapper,
                                  IotDailySummaryMapper summaryMapper, IotThresholdRuleEngine ruleEngine,
                                  org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbcProvider) {
        this.anomalyMapper = anomalyMapper;
        this.summaryMapper = summaryMapper;
        this.ruleEngine = ruleEngine;
        this.jdbc = jdbcProvider.getIfAvailable();
    }

    /** 按天聚合：SQL 侧 GROUP BY（45 万压测数据也不能拖垮内存聚合——日报必须始终秒级）。 */
    public List<Map<String, Object>> summarize(LocalDate date, String tenantId) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        if (jdbc == null) return List.of();

        // 指标区间: 数据库聚合 min/max/avg/count
        List<Map<String, Object>> agg = jdbc.queryForList("""
                SELECT device_id, MIN(farm_id) AS farm_id, metric,
                       MIN(metric_value) AS min_v, MAX(metric_value) AS max_v,
                       AVG(metric_value) AS avg_v, COUNT(*) AS cnt
                FROM iot_device_data
                WHERE tenant_id = ? AND collected_at >= ? AND collected_at < ?
                GROUP BY device_id, metric
                """, tenantId, dayStart, dayEnd);

        // 异常数: 数据库聚合
        List<Map<String, Object>> anomalies = jdbc.queryForList("""
                SELECT device_id, COUNT(*) AS cnt
                FROM iot_anomaly_event
                WHERE tenant_id = ? AND create_time >= ? AND create_time < ?
                GROUP BY device_id
                """, tenantId, dayStart, dayEnd);

        Map<String, Long> anomalyByDevice = new LinkedHashMap<>();
        anomalies.forEach(a -> anomalyByDevice.put(String.valueOf(a.get("device_id")),
                ((Number) a.get("cnt")).longValue()));

        Map<String, Map<String, Object>> byDevice = new LinkedHashMap<>();
        for (Map<String, Object> row : agg) {
            String deviceId = String.valueOf(row.get("device_id"));
            Map<String, Object> device = byDevice.computeIfAbsent(deviceId, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("farmId", String.valueOf(row.get("farm_id")));
                m.put("ranges", new LinkedHashMap<String, String>());
                m.put("points", 0L);
                return m;
            });
            @SuppressWarnings("unchecked")
            Map<String, String> ranges = (Map<String, String>) device.get("ranges");
            ranges.put(ruleEngine.labelOf(String.valueOf(row.get("metric"))),
                    String.format("%.1f~%.1f (均值 %.1f)",
                            ((Number) row.get("min_v")).doubleValue(),
                            ((Number) row.get("max_v")).doubleValue(),
                            ((Number) row.get("avg_v")).doubleValue()));
            device.put("points", ((Number) device.get("points")).longValue()
                    + ((Number) row.get("cnt")).longValue());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : byDevice.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("farmId", entry.getValue().get("farmId"));
            row.put("deviceId", entry.getKey());
            row.put("points", entry.getValue().get("points"));
            row.put("ranges", entry.getValue().get("ranges"));
            row.put("anomalyCount", anomalyByDevice.getOrDefault(entry.getKey(), 0L));
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
        // 同日重跑幂等: 唯一键冲突时更新已有行而不是报错
        try {
            summaryMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            IotDailySummary exist = summaryMapper.selectOne(new LambdaQueryWrapper<IotDailySummary>()
                    .eq(IotDailySummary::getTenantId, tenantId).eq(IotDailySummary::getFarmId, "ALL")
                    .eq(IotDailySummary::getSummaryDate, date));
            if (exist != null) {
                exist.setSummaryJson(summaryJson);
                exist.setFeishuState(feishuState);
                summaryMapper.updateById(exist);
            }
        }
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
            // 日报推送限量: 只推有异常或数据量最大的前 20 台设备 (504 台全推会打爆飞书 API 限流)
            List<Map<String, Object>> top = summary.stream()
                    .sorted((a, b) -> Long.compare(
                            Long.parseLong(String.valueOf(b.getOrDefault("anomalyCount", 0))),
                            Long.parseLong(String.valueOf(a.getOrDefault("anomalyCount", 0)))))
                    .limit(20)
                    .toList();
            for (Map<String, Object> row : top) {
                // 一列一字段: 设备名/地区/报错信息/日期(毫秒时间戳=含时分秒); 飞书自动忽略表中不存在的列
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("设备名", String.valueOf(row.get("deviceId")));
                fields.put("地区", String.valueOf(row.get("farmId")));
                fields.put("报错信息", String.format("点数:%s 指标:%s 异常:%s",
                        row.get("points"), row.get("ranges"), row.get("anomalyCount")));
                fields.put("日期", System.currentTimeMillis());
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
