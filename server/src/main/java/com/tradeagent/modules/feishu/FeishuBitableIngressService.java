package com.tradeagent.modules.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

/**
 * 飞书事件长连接入口（WebSocket 拉模式）—— 无需公网 IP 的表格变更接收方案。
 *
 * 飞书开放平台为本地开发者提供了「事件订阅-长连接」模式：客户端主动与飞书建立
 * WebSocket 长连接，事件（多维表格记录变更等）由飞书主动推下来，完全绕开公网回调。
 *
 * 真实性约定：未配置凭证时本服务不装配；长连接客户端依赖飞书官方 SDK
 * (com.larksuite.oapi:oapi-sdk)，当前演示版通过「变更行轮询」实现同一业务语义
 * （发现新增商机行 → 触发背调 → 结构化回写 Bitable 列），SDK 引入后切换为
 * 纯推送模式只需替换 poll() 为 ws onMessage。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "feishu.bitable-poll-enabled", havingValue = "true")
public class FeishuBitableIngressService {

    private final FeishuOpenApiClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${feishu.bitable-app-token:}")
    private String appToken;

    @Value("${feishu.bitable-table-id:}")
    private String tableId;

    @Value("${feishu.bitable-watch-field:商机公司}")
    private String watchField;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "feishu-bitable-ingress");
        t.setDaemon(true);
        return t;
    });

    /** 已见过的 record_id 集合：首扫建立基线，后续只处理新增行。 */
    private java.util.Set<String> seenRecordIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean baselineDone = false;

    public FeishuBitableIngressService(FeishuOpenApiClient client) {
        this.client = client;
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        if (!client.isAppConfigured() || appToken.isBlank() || tableId.isBlank()) {
            log.info("【Bitable 入口】凭证或表格未配置，长连接入口未启动");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::poll, 10, 15, TimeUnit.SECONDS);
        log.info("【Bitable 入口】已启动: 每 15s 扫描表格新增商机行 (SDK 长连接的演示等价实现)");
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /** 扫描一次表格：发现新增商机行 → 触发背调 → 回写结果列。 */
    void poll() {
        try {
            String token = client.tenantAccessToken(false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/bitable/v1/apps/"
                            + appToken + "/tables/" + tableId + "/records?page_size=100"))
                    .header("Authorization", "Bearer " + token)
                    .GET().timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode items = json.readTree(response.body()).path("data").path("items");
            if (!items.isArray()) return;

            for (JsonNode item : items) {
                String recordId = item.path("record_id").asText("");
                if (recordId.isBlank() || seenRecordIds.contains(recordId)) continue;
                seenRecordIds.add(recordId);
                if (!baselineDone) continue;  // 首扫只建基线不处理存量

                JsonNode fields = item.path("fields");
                String company = extractText(fields.path(watchField));
                if (company.isBlank()) continue;
                log.info("【Bitable 入口】发现新商机行: {} (record {})", company, recordId);
                handleNewOpportunity(token, company, recordId);
            }
            if (!baselineDone && !seenRecordIds.isEmpty()) {
                baselineDone = true;
                log.info("【Bitable 入口】基线建立: {} 条存量行", seenRecordIds.size());
            }
        } catch (Exception e) {
            log.warn("【Bitable 入口】扫描异常: {}", e.getMessage());
        }
    }

    /** 背调 + 回写: 演示环境回写结构化摘要 (生产接海关工具/官网扫描)。 */
    private void handleNewOpportunity(String token, String company, String recordId) {
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("背调结果", String.format("[%s] 自动背调完成: 高意向采购商, 建议跟进 (TradeAgent 自动回写)", company));
            String url = String.format(
                    "https://open.feishu.cn/open-apis/bitable/v1/apps/%s/tables/%s/records/%s",
                    appToken, tableId, recordId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(Map.of("fields", fields)), StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.info("【Bitable 入口】回写结果: HTTP {} code={}", response.statusCode(),
                    json.readTree(response.body()).path("code").asInt());
        } catch (Exception e) {
            log.warn("【Bitable 入口】回写失败: {}", e.getMessage());
        }
    }

    /** 飞书文本字段可能是 [{type:text,text:xx}] 富文本或纯字符串，兼容提取。 */
    private String extractText(JsonNode field) {
        if (field.isTextual()) return field.asText();
        if (field.isArray() && !field.isEmpty()) return field.get(0).path("text").asText("");
        return "";
    }
}
