package com.tradeagent.modules.feishu;

import com.tradeagent.core.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 飞书连接状态自检: 自建应用凭证 + 群机器人 Webhook 可达性。 */
@RestController
@RequestMapping("/api/v1/feishu")
public class FeishuConnectionController {

    private final FeishuOpenApiClient client;

    @Value("${feishu.iot-alert-webhook-url:}")
    private String webhookUrl;

    public FeishuConnectionController(FeishuOpenApiClient client) {
        this.client = client;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        FeishuOpenApiClient.ConnCheck app = client.checkConnection();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appConfigured", app.appConfigured());
        result.put("tokenOk", app.tokenOk());
        result.put("appDetail", app.detail());
        result.put("webhookConfigured", webhookUrl != null && !webhookUrl.isBlank());
        return ApiResponse.success(result);
    }
}
