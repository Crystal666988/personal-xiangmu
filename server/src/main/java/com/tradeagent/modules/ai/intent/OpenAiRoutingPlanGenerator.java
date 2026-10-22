package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.model.DynamicModelFactory;
import com.tradeagent.core.model.ModelConfig;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.ResponseFormat;
import org.springframework.stereotype.Component;

import java.util.List;

/** Generate annotations with a tenant/configuration-scoped ChatModel from the shared factory. */
@Component
public final class OpenAiRoutingPlanGenerator implements RoutingPlanGenerator {
    private final DynamicModelFactory models;

    public OpenAiRoutingPlanGenerator(DynamicModelFactory models) { this.models = models; }

    @Override
    public boolean isAvailable(RoutingModelConfig config) { return config != null && config.complete(); }

    @Override
    public String generate(String instructions, String sourceJson, RoutingModelConfig config) {
        if (!isAvailable(config)) throw new IllegalStateException("Routing model is not configured");
        try {
            var model = models.getOrCreateChatModel(ModelConfig.builder().tenantId(config.getTenantId())
                    .usageType(DynamicModelFactory.ROUTING_USAGE).provider(config.getProvider())
                    .baseUrl(config.getBaseUrl()).apiKey(config.getApiKey()).modelName(config.getModelName())
                    .temperature(0.0).maxTokens(4096).build());
            var options = OpenAiChatOptions.builder().withModel(config.getModelName().trim())
                    .withTemperature(0.0f).withMaxTokens(4096).withN(1)
                    .withResponseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT)).build();
            var response = model.call(new Prompt(List.of(new SystemMessage(instructions), new UserMessage(sourceJson)), options));
            if (response == null || response.getResults().size() != 1 || response.getResult() == null
                    || response.getResult().getMetadata() == null
                    || !"STOP".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) {
                throw new IllegalStateException("Incomplete routing output");
            }
            var output = response.getResult().getOutput();
            if (output == null || output.getContent() == null || output.getContent().isBlank()
                    || (output.getToolCalls() != null && !output.getToolCalls().isEmpty())
                    || output.getMetadata().get("function_call") != null
                    || output.getMetadata().get("tool_calls") != null
                    || (output.getMetadata().get("refusal") != null
                        && !output.getMetadata().get("refusal").toString().isBlank())) {
                throw new IllegalStateException("Routing output must contain only text annotations");
            }
            return output.getContent();
        } catch (RuntimeException e) {
            // Do not include factory/provider exception text, key, URL, prompt or raw response.
            throw new IllegalStateException("Routing model is unavailable");
        }
    }
}
