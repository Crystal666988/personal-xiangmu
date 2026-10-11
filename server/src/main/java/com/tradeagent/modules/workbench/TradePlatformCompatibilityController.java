package com.tradeagent.modules.workbench;

import com.tradeagent.core.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * WeKnora 前端外壳兼容层控制器 (WeKnora Platform Compatibility Controller)
 * 
 * 为 WeKnora 前端侧边栏、状态管理 (auth, models, sessions, system) 提供标准 200 OK 结构响应，
 * 彻底消除前端挂载时的 404/401 报错，保证免登录一键直达外贸智能体全景看板。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class TradePlatformCompatibilityController {

    /**
     * 1. 获取可用大模型列表（返回 DeepSeek-V3 / GPT-4o / 通义千问）
     */
    @GetMapping({"/models", "/models/"})
    public ApiResponse<List<Map<String, Object>>> getModels() {
        log.info("【兼容网关】返回预设外贸大模型列表");
        List<Map<String, Object>> models = new ArrayList<>();

        Map<String, Object> deepseek = new HashMap<>();
        deepseek.put("id", "deepseek-chat");
        deepseek.put("name", "deepseek-chat");
        deepseek.put("display_name", "DeepSeek-V3 (主用生产模型)");
        deepseek.put("type", "KnowledgeQA");
        deepseek.put("source", "remote");
        deepseek.put("is_default", true);
        Map<String, Object> dsParams = new HashMap<>();
        dsParams.put("provider", "deepseek");
        dsParams.put("base_url", "https://api.deepseek.com/v1");
        deepseek.put("parameters", dsParams);
        models.add(deepseek);

        Map<String, Object> gpt4o = new HashMap<>();
        gpt4o.put("id", "gpt-4o");
        gpt4o.put("name", "gpt-4o");
        gpt4o.put("display_name", "OpenAI GPT-4o (备用降级模型)");
        gpt4o.put("type", "KnowledgeQA");
        gpt4o.put("source", "remote");
        gpt4o.put("is_default", false);
        Map<String, Object> gptParams = new HashMap<>();
        gptParams.put("provider", "openai");
        gptParams.put("base_url", "https://api.openai.com/v1");
        gpt4o.put("parameters", gptParams);
        models.add(gpt4o);

        Map<String, Object> qwen = new HashMap<>();
        qwen.put("id", "qwen-plus");
        qwen.put("name", "qwen-plus");
        qwen.put("display_name", "通义千问 Qwen-Plus (高并发通道)");
        qwen.put("type", "KnowledgeQA");
        qwen.put("source", "remote");
        qwen.put("is_default", false);
        Map<String, Object> qwenParams = new HashMap<>();
        qwenParams.put("provider", "aliyun");
        qwenParams.put("base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        qwen.put("parameters", qwenParams);
        models.add(qwen);

        return ApiResponse.success(models);
    }

    /**
     * 2. 查询单个模型
     */
    @GetMapping("/models/{id}")
    public ApiResponse<Map<String, Object>> getModel(@PathVariable("id") String id) {
        Map<String, Object> model = new HashMap<>();
        model.put("id", id);
        model.put("name", id);
        model.put("display_name", id);
        model.put("type", "KnowledgeQA");
        model.put("source", "remote");
        return ApiResponse.success(model);
    }

    /**
     * 3. 用户与租户鉴权信息（免登录 Admin 注入）
     */
    @GetMapping({"/auth/me", "/me"})
    public ApiResponse<Map<String, Object>> getCurrentUser() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> user = new HashMap<>();
        user.put("id", "user_1001");
        user.put("username", "admin");
        user.put("name", "TradeAgent 演示管理员");
        user.put("email", "admin@tradeagent.com");
        user.put("role", "admin");
        user.put("is_admin", true);
        user.put("tenant_id", "1001");
        result.put("user", user);

        Map<String, Object> tenant = new HashMap<>();
        tenant.put("id", "1001");
        tenant.put("name", "TradeAgent 外贸工作空间");
        tenant.put("owner_id", "user_1001");
        result.put("tenant", tenant);
        result.put("active_tenant", tenant);

        List<Map<String, Object>> memberships = new ArrayList<>();
        Map<String, Object> member = new HashMap<>();
        member.put("tenant_id", 1001);
        member.put("tenant_name", "TradeAgent 外贸工作空间");
        member.put("role", "admin");
        memberships.add(member);
        result.put("memberships", memberships);

        return ApiResponse.success(result);
    }

    /**
     * 4. 空列表降级：会话、知识库、智能体、组织、系统信息
     */
    @GetMapping("/sessions")
    public ApiResponse<List<Object>> getSessions() {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping("/knowledge-bases")
    public ApiResponse<List<Object>> getKnowledgeBases() {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping("/agents")
    public ApiResponse<List<Object>> getAgents() {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping({"/organizations", "/tenants"})
    public ApiResponse<List<Object>> getOrganizations() {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping("/web-search-providers")
    public ApiResponse<List<Object>> getWebSearchProviders() {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping("/system/sandbox-configs")
    public ApiResponse<List<Object>> getSandboxConfigs() {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping("/system/info")
    public ApiResponse<Map<String, Object>> getSystemInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("edition", "community");
        info.put("version", "1.0.0");
        return ApiResponse.success(info);
    }

    @GetMapping("/im/channels")
    public ApiResponse<List<Object>> getImChannels() {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping("/embed/channels")
    public ApiResponse<List<Object>> getEmbedChannels() {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping({"/tenants/me/invitations/pending-count", "/me/invitations/pending-count"})
    public ApiResponse<Map<String, Object>> getPendingInvitationCount() {
        Map<String, Object> map = new HashMap<>();
        map.put("pending_count", 0);
        return ApiResponse.success(map);
    }
}
