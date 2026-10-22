package com.tradeagent.core.model;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.ResponseFormat;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tenant/configuration-scoped cached models, registered with Spring and evicted together. */
@Slf4j
@Component
public class DynamicModelFactory implements ApplicationContextAware {
    public static final String ROUTING_USAGE = "routing";
    private DefaultListableBeanFactory beanFactory;
    private final ConcurrentMap<String, String> registeredBeanNames = new ConcurrentHashMap<>();
    private final Cache<String, ChatModel> modelCache = CacheBuilder.newBuilder()
            .maximumSize(100).expireAfterAccess(Duration.ofMinutes(30))
            .removalListener((RemovalNotification<String, ChatModel> notification) ->
                    handleModelEviction(notification.getKey())).build();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
    }

    public synchronized ChatModel getOrCreateChatModel(ModelConfig config) {
        ModelConfig snapshot = snapshot(config);
        String cacheKey = snapshot.getCacheKey();
        try {
            return modelCache.get(cacheKey, () -> createAndRegisterChatModel(snapshot));
        } catch (Exception e) {
            log.warn("ChatModel creation failed for configuration fingerprint {}", cacheKey);
            throw new IllegalStateException("Unable to create configured AI model");
        }
    }

    private ChatModel createAndRegisterChatModel(ModelConfig config) {
        if (beanFactory == null) throw new IllegalStateException("Model factory application context is unavailable");
        String cacheKey = config.getCacheKey();
        // Full SHA-256 fingerprint replaces collision-prone 32-bit String.hashCode bean names.
        String name = "dynamicChatModel_" + cacheKey.substring(cacheKey.lastIndexOf(':') + 1);
        ChatModel model = createChatModel(config);
        beanFactory.registerSingleton(name, model);
        registeredBeanNames.put(cacheKey, name);
        return model;
    }

    /** Offline tests may replace construction while retaining real cache/lifecycle behavior. */
    protected ChatModel createChatModel(ModelConfig config) {
        boolean routing = ROUTING_USAGE.equals(config.getUsageType());
        RestClient.Builder rest = RestClient.builder();
        if (routing) rest.requestFactory(routingRequestFactory());
        // User baseUrl already includes /v1 or another API prefix. Avoid M2's extra /v1.
        OpenAiApi api = new OpenAiApi(config.getBaseUrl(), config.getApiKey(), "/chat/completions",
                "/embeddings", rest, WebClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        var options = OpenAiChatOptions.builder().withModel(config.getModelName())
                .withTemperature(config.getTemperature().floatValue()).withMaxTokens(config.getMaxTokens());
        if (!routing) return new OpenAiChatModel(api, options.build());
        RetryTemplate once = new RetryTemplate();
        once.setRetryPolicy(new NeverRetryPolicy());
        return new AnnotationChatModel(api, options
                .withResponseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT))
                .withN(1).build(), once);
    }

    protected ClientHttpRequestFactory routingRequestFactory() {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(12));
        return requests;
    }

    private static final class AnnotationChatModel extends OpenAiChatModel {
        AnnotationChatModel(OpenAiApi api, OpenAiChatOptions options, RetryTemplate once) {
            super(api, options, null, List.of(), once);
        }
        @Override
        protected boolean isToolCall(ChatResponse response, Set<String> finishReasons) {
            // M2 has no proxyToolCalls option. Prevent its automatic function loop before
            // RoutingPlanGenerator inspects and rejects unsolicited tool-call output.
            return false;
        }
    }

    private static ModelConfig snapshot(ModelConfig config) {
        Objects.requireNonNull(config, "Model configuration is required");
        if (config.getTenantId() == null || config.getTenantId() <= 0
                || config.getApiKey() == null || config.getApiKey().isBlank()
                || config.getModelName() == null || config.getModelName().isBlank()) {
            throw new IllegalArgumentException("Tenant, credentials and model are required");
        }
        return config.toBuilder().baseUrl(normalizeBaseUrl(config.getBaseUrl()))
                .apiKey(config.getApiKey().trim()).modelName(config.getModelName().trim())
                .provider(config.getProvider() == null || config.getProvider().isBlank() ? "openai"
                        : config.getProvider().trim().toLowerCase(Locale.ROOT))
                .usageType(config.getUsageType() == null || config.getUsageType().isBlank() ? "DEFAULT" : config.getUsageType().trim())
                .temperature(config.getTemperature() == null ? 0.7 : config.getTemperature()).build();
    }

    static String normalizeBaseUrl(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("API base URL is required");
        String base = source.trim().replaceAll("/+$", "");
        boolean complete = base.endsWith("/chat/completions");
        if (complete) base = base.substring(0, base.length() - "/chat/completions".length());
        URI uri = URI.create(base);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid API base URL");
        }
        if (!complete && (uri.getPath() == null || uri.getPath().isEmpty())) base += "/v1";
        return base;
    }

    public synchronized void evictTenantModel(Long tenantId) {
        String prefix = tenantId + ":";
        registeredBeanNames.keySet().stream().filter(key -> key.startsWith(prefix))
                .toList().forEach(modelCache::invalidate);
    }

    public synchronized void evictTenantModel(Long tenantId, String usageType) {
        String prefix = tenantId + ":" + usageType + ":";
        registeredBeanNames.keySet().stream().filter(key -> key.startsWith(prefix))
                .toList().forEach(modelCache::invalidate);
    }

    private void handleModelEviction(String cacheKey) {
        String name = registeredBeanNames.remove(cacheKey);
        if (name != null && beanFactory != null && beanFactory.containsSingleton(name)) beanFactory.destroySingleton(name);
    }

    /** Legacy demo convenience method; routing preview deliberately never calls this. */
    public ChatModel getModel(Long tenantId) {
        return getOrCreateChatModel(ModelConfig.builder().tenantId(tenantId).usageType("DEFAULT")
                .provider("openai").modelName("gpt-4o-mini").baseUrl("https://api.openai.com/v1")
                .apiKey("sk-trade-agent-mock-key").temperature(0.7).build());
    }

    public boolean warmUpModel(Long tenantId, ModelConfig config) {
        config.setTenantId(tenantId);
        if (config.getUsageType() == null) config.setUsageType("DEFAULT");
        getOrCreateChatModel(config);
        return testConnection(config);
    }

    public boolean testConnection(ModelConfig config) {
        try {
            var options = OpenAiChatOptions.builder().withModel(config.getModelName()).withMaxTokens(1)
                    .withTemperature(0.0f).build();
            var api = new OpenAiApi(normalizeBaseUrl(config.getBaseUrl()), config.getApiKey(),
                    "/chat/completions", "/embeddings", RestClient.builder(), WebClient.builder(),
                    RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
            return new OpenAiChatModel(api, options).call("ping") != null;
        } catch (Exception e) {
            log.warn("Model connection probe failed");
            return false;
        }
    }
}
