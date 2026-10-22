package com.tradeagent.modules.ai.intent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Immutable per-request copy of the user's frontend model configuration. Never cached. */
public final class RoutingModelConfig {
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final Long tenantId;
    private final String provider;

    @JsonCreator
    public RoutingModelConfig(@JsonProperty("apiKey") String apiKey,
                              @JsonProperty("baseUrl") String baseUrl,
                              @JsonProperty("modelName") String modelName,
                              @JsonProperty("tenantId") Long tenantId,
                              @JsonProperty("provider") String provider) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.tenantId = tenantId;
        this.provider = provider;
    }

    /** Compatibility for callers; incomplete identity is deliberately unavailable in production. */
    public RoutingModelConfig(String apiKey, String baseUrl, String modelName) {
        this(apiKey, baseUrl, modelName, null, null);
    }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getModelName() { return modelName; }
    public Long getTenantId() { return tenantId; }
    public String getProvider() { return provider; }

    boolean complete() {
        return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank()
                && modelName != null && !modelName.isBlank() && tenantId != null && tenantId > 0
                && provider != null && !provider.isBlank();
    }

    @Override
    public String toString() { return "RoutingModelConfig[redacted]"; }
}
