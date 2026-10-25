package com.tradeagent.modules.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.auth.JwtService;
import com.tradeagent.core.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书扫码/授权登录（OAuth 2.0 授权码模式）+ JWT 签发。
 *
 * 流程：前端跳转飞书授权页 → 用户扫码确认 → 飞书带 code 回调前端 →
 * 前端 POST /feishu/auth/callback {code} → 本服务用 code 换 user_access_token →
 * 拉取用户信息（union_id/姓名）→ 关联本地用户 → 签发 JWT。
 * 需在自建应用配置「安全设置-重定向 URL」为前端地址。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/feishu/auth")
public class FeishuAuthController {

    private final FeishuOpenApiClient appClient;
    private final JwtService jwt;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${feishu.app-id:}")
    private String appId;

    @Value("${feishu.app-secret:}")
    private String appSecret;

    public FeishuAuthController(FeishuOpenApiClient appClient, JwtService jwt) {
        this.appClient = appClient;
        this.jwt = jwt;
    }

    /** 前端获取授权跳转地址。 */
    @GetMapping("/authorize-url")
    public ApiResponse<Map<String, String>> authorizeUrl(
            @RequestParam(value = "redirectUri", defaultValue = "http://localhost:5173/login?feishu=1") String redirectUri) {
        if (!appClient.isAppConfigured()) {
            return ApiResponse.error(503, "未配置飞书自建应用凭证，SSO 不可用");
        }
        String url = String.format(
                "https://open.feishu.cn/open-apis/authen/v1/index?app_id=%s&redirect_uri=%s",
                appId, java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
        return ApiResponse.success(Map.of("authorizeUrl", url));
    }

    /** 授权码换 JWT。 */
    @PostMapping("/callback")
    public ApiResponse<Map<String, String>> callback(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ApiResponse.error(400, "缺少授权码 code");
        }
        try {
            // 1. code -> user_access_token
            String tokenBody = json.writeValueAsString(Map.of("grant_type", "authorization_code", "code", code));
            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/authen/v2/oauth/token"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> tokenResp = http.send(tokenReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode tokenRoot = json.readTree(tokenResp.body());
            String userAccessToken = tokenRoot.path("data").path("access_token").asText("");
            if (userAccessToken.isBlank()) {
                log.warn("【飞书SSO】code 换 token 失败: {}", tokenResp.body());
                return ApiResponse.error(401, "授权码无效或已过期");
            }

            // 2. 拉取用户信息
            HttpRequest userReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/authen/v1/user_info"))
                    .header("Authorization", "Bearer " + userAccessToken)
                    .GET().timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> userResp = http.send(userReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode userRoot = json.readTree(userResp.body()).path("data");
            String unionId = userRoot.path("union_id").asText(userRoot.path("user_id").asText("u_" + System.currentTimeMillis()));
            String name = userRoot.path("name").asText("飞书用户");
            String tenantKey = userRoot.path("tenant_key").asText("1001");

            // 3. 签发 JWT
            String jwtToken = jwt.issue(new JwtService.TokenInfo(unionId, name, tenantKey));
            Map<String, String> result = new LinkedHashMap<>();
            result.put("token", jwtToken);
            result.put("name", name);
            result.put("userId", unionId);
            log.info("【飞书SSO】用户 {} ({}) 登录成功, JWT 已签发", name, unionId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.warn("【飞书SSO】回调处理失败: {}", e.getMessage());
            return ApiResponse.error(500, "SSO 登录失败: " + e.getMessage());
        }
    }

    /** JWT 校验（前端刷新时验证 token 有效性）。 */
    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return ApiResponse.error(401, "未登录");
        }
        JwtService.TokenInfo info = jwt.verify(auth.substring(7));
        if (info == null) {
            return ApiResponse.error(401, "登录已过期");
        }
        return ApiResponse.success(Map.of("userId", info.userId(), "name", info.name(), "tenantId", info.tenantId()));
    }
}
