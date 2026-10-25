package com.tradeagent.modules.feishu;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书开放平台客户端：tenant_access_token 获取 + 连通性自检 + 真实 API 调用入口。
 *
 * 未配置凭证时所有方法如实返回不可用状态（available=false），绝不伪造成功。
 * 配置方法（自建应用）: https://open.feishu.cn 创建企业自建应用 →
 * 获取 App ID / App Secret → 配置到环境变量 FEISHU_APP_ID / FEISHU_APP_SECRET。
 */
@Slf4j
@Service
public class FeishuOpenApiClient {

    @Value("${feishu.app-id:}")
    private String appId;

    @Value("${feishu.app-secret:}")
    private String appSecret;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private volatile String cachedToken;
    private volatile long tokenExpiresAt;

    public record ConnCheck(boolean appConfigured, boolean tokenOk, String detail) {
        public boolean available() { return appConfigured && tokenOk; }
    }

    public boolean isAppConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    /** 连通性自检: 配置状态 + 能否换取 tenant_access_token。 */
    public ConnCheck checkConnection() {
        if (!isAppConfigured()) {
            return new ConnCheck(false, false,
                    "未配置自建应用凭证 (FEISHU_APP_ID / FEISHU_APP_SECRET)，文档拉取与 Bitable 写入不可用");
        }
        try {
            String token = tenantAccessToken(true);
            return new ConnCheck(true, token != null && !token.isBlank(),
                    token != null && !token.isBlank()
                            ? "凭证有效，已成功换取 tenant_access_token"
                            : "凭证无效或网络不可达");
        } catch (Exception e) {
            return new ConnCheck(true, false, "校验失败: " + e.getMessage());
        }
    }

    /** tenant_access_token（缓存至过期前 60s）。 */
    public String tenantAccessToken(boolean forceRefresh) throws Exception {
        if (!forceRefresh && cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", appId.trim());
        body.put("app_secret", appSecret.trim());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var root = json.readTree(response.body());
        String token = root.path("tenant_access_token").asText("");
        if (!token.isBlank()) {
            cachedToken = token;
            tokenExpiresAt = System.currentTimeMillis() + (root.path("expire").asLong(7200) - 60) * 1000;
        }
        return token;
    }

    /** 真实拉取飞书云文档纯文本 (raw_content)。返回 null = 失败或未配置。 */
    public String fetchDocumentRawContent(String documentToken) {
        if (!isAppConfigured()) return null;
        try {
            String token = tenantAccessToken(false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/docx/v1/documents/"
                            + documentToken + "/raw_content"))
                    .header("Authorization", "Bearer " + token)
                    .GET().timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var root = json.readTree(response.body());
            if (root.path("code").asInt(-1) == 0) {
                return root.path("data").path("content").asText("");
            }
            log.warn("【飞书文档】拉取失败 code={}: {}", root.path("code").asInt(), root.path("msg").asText());
        } catch (Exception e) {
            log.warn("【飞书文档】拉取异常: {}", e.getMessage());
        }
        return null;
    }
}
