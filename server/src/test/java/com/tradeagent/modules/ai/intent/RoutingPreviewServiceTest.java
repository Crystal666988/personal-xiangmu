package com.tradeagent.modules.ai.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.tools.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.tradeagent.modules.ai.intent.RoutingPreviewService.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingPreviewServiceTest {
    private static final RoutingModelConfig CONFIG = new RoutingModelConfig(
            "request-test-key", "https://example.test/v1", "user-chosen-model", 1001L, "openai");
    private static final String PRICE_PLAN = """
            {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m","quantity":"q"}}],
             "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon580W"},
                               {"id":"q","type":"QUANTITY","evidence":"1200 pieces"}]}
            """;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void controllerToGeneratorToValidationPreservesSourceAndNeverExecutesTools() throws Exception {
        var fake = new FakeGenerator(PRICE_PLAN);
        var preview = new RoutingPreviewController(service(fake)).preview(
                new RoutingPreviewController.PreviewRequest("\uD83D\uDE80 Quote TOPCon580W, 1200 pieces", CONFIG));
        assertEquals(200, preview.getCode());
        assertEquals(PARAMETERS_VALIDATED, preview.getData().status());
        assertTrue(preview.getData().structurallyValid());
        assertFalse(preview.getData().executable());
        assertEquals(1, fake.calls);
        var source = json.readTree(fake.source);
        assertEquals("\uD83D\uDE80 Quote TOPCon580W, 1200 pieces", source.path("originalQuery").textValue());
        assertEquals(5, source.path("availableTools").size());
        assertFalse(fake.source.contains("2000"));
        assertFalse(fake.source.contains("Al-Futtaim"));
        assertFalse(fake.source.contains("default"));
        assertTrue(fake.instructions.contains("UTF-16"));
        var serialized = json.readTree(json.writeValueAsString(preview.getData()));
        assertFalse(serialized.path("executable").asBoolean(true));
        assertTrue(serialized.path("parametersValid").asBoolean());
        assertEquals("TOPCon-580W", serialized.path("resolvedSlots").path(0).path("value").textValue());
        assertEquals("TOPCon580W", serialized.path("resolvedSlots").path(0).path("evidence").textValue());
        assertEquals("BACKEND_DEMO_MODEL_ALIASES", serialized.path("resolvedSlots").path(0).path("source").path("rule").textValue());
        assertEquals(1200, serialized.path("resolvedTasks").path(0).path("parameters").path("quantity").intValue());
        assertTrue(serialized.path("clarifications").isArray());
    }

    @Test
    void missingRequiredQuantityRequestsClarificationInsteadOfSupplyingDefault() {
        var fake = new FakeGenerator(PRICE_PLAN.replace("\"quantity\":\"q\"", "\"quantity\":null"));
        var result = service(fake).preview("Quote TOPCon580W 1200 pieces", CONFIG);
        assertEquals(NEEDS_CLARIFICATION, result.status());
        assertEquals("MISSING_REQUIRED_SLOT", result.issues().get(0).code());
        assertNull(result.plan().tasks().get(0).slots().get("quantity"));
        assertFalse(result.executable());
        assertEquals(1, fake.calls);
        assertFalse(result.resolvedSlots().isEmpty());
        assertTrue(result.clarifications().get(0).contains("采购数量"));
    }

    @Test
    void forgedEvidenceUnknownToolsAndWrongTypesAreRejectedWithoutRetryOrFallback() {
        for (String output : List.of(PRICE_PLAN.replace("1200 pieces", "2000"),
                PRICE_PLAN.replace("calculate_tier_price", "generate_proforma_invoice"),
                PRICE_PLAN.replace("QUANTITY", "MONEY"),
                PRICE_PLAN.replace("\"quantity\":\"q\"", "\"quantity\":2000"), "not JSON")) {
            var fake = new FakeGenerator(output);
            var result = service(fake).preview("TOPCon580W 1200 pieces", CONFIG);
            assertEquals(REJECTED, result.status());
            assertFalse(result.structurallyValid());
            assertFalse(result.executable());
            assertEquals(1, fake.calls);
            assertTrue(result.resolvedSlots().isEmpty());
            assertTrue(result.resolvedTasks().isEmpty());
        }
    }

    @Test
    void emptyPlanSupportsConversationAndIsStillNotExecutable() {
        var result = service(new FakeGenerator("{\"tasks\":[],\"slotCandidates\":[]}")).preview("Hello", CONFIG);
        assertEquals(STRUCTURALLY_VALID, result.status());
        assertTrue(result.plan().tasks().isEmpty());
        assertFalse(result.executable());
    }

    @Test
    void unavailableOrFailedModelDoesNotLeakSecretsOrTriggerRuleFallback() {
        var fake = new FakeGenerator(PRICE_PLAN);
        fake.available = false;
        assertEquals(MODEL_UNAVAILABLE, service(fake).preview("quote", CONFIG).status());
        assertEquals(0, fake.calls);
        fake.available = true;
        fake.fail = true;
        var failed = service(fake).preview("quote", CONFIG);
        assertEquals(MODEL_UNAVAILABLE, failed.status());
        assertNull(failed.plan());
        assertEquals(1, fake.calls);
        assertFalse(failed.toString().contains("secret"));
    }

    @Test
    void catalogOnlyAllowsRegisteredMappedToolsAndEnforcesFxPair() {
        var definition = new CurrencyExchangeRateTool().getDefinition();
        IToolHandler handler = throwingHandler(definition);
        var catalog = new EvidenceToolCatalog(new ToolRegistry(List.of(handler,
                throwingHandler(ToolDefinition.builder().id("unmapped").parameterSchema(Map.of()).build()))));
        var snapshot = catalog.snapshot();
        assertEquals(List.of("currency_exchange_rate"), snapshot.tools().stream().map(EvidenceToolCatalog.Tool::id).toList());
        assertEquals(java.util.Set.of("baseCurrency", "targetCurrency"),
                snapshot.policies().get("currency_exchange_rate").required());
        var fake = new FakeGenerator("""
                {"tasks":[{"tool":"currency_exchange_rate","slots":{}}],"slotCandidates":[]}
                """);
        assertEquals(NEEDS_CLARIFICATION,
                new RoutingPreviewService(fake, catalog, new StrictEvidencePlanReader(), json)
                        .preview("exchange rate", CONFIG).status());
    }

    @Test
    void schemaDriftFailsBeforeCallingModel() {
        var definition = new TierPriceCalculatorTool().getDefinition();
        definition.setParameterSchema(Map.of("newRequiredField", Map.of("type", "string", "required", true)));
        var fake = new FakeGenerator(PRICE_PLAN);
        var service = new RoutingPreviewService(fake,
                new EvidenceToolCatalog(new ToolRegistry(List.of(throwingHandler(definition)))),
                new StrictEvidencePlanReader(), json);
        assertEquals(CONFIGURATION_ERROR, service.preview("quote", CONFIG).status());
        assertEquals(0, fake.calls);
    }

    @Test
    void badRequestsAreRejectedBeforeAnyModelCall() {
        var fake = new FakeGenerator(PRICE_PLAN);
        var controller = new RoutingPreviewController(service(fake));
        assertEquals(400, controller.preview(null).getCode());
        for (String query : java.util.Arrays.asList(null, "", " ", "x".repeat(20_001))) {
            assertEquals(400, controller.preview(new RoutingPreviewController.PreviewRequest(query, CONFIG)).getCode());
        }
        assertEquals(0, fake.calls);
    }

    @Test
    void frontendModelConfigDeserializesAndFlowsPerRequestWithoutCredentialExposure() throws Exception {
        var request = json.readValue("""
                {"query":"TOPCon580W 1200 pieces","modelConfig":{"apiKey":"frontend-secret",
                 "baseUrl":"https://user.example/v1","modelName":"chosen-qwen-model","tenantId":"1001","provider":"aliyun"}}
                """, RoutingPreviewController.PreviewRequest.class);
        var fake = new FakeGenerator(PRICE_PLAN);
        var response = new RoutingPreviewController(service(fake)).preview(request);
        assertEquals(PARAMETERS_VALIDATED, response.getData().status());
        assertSame(request.modelConfig(), fake.receivedConfig);
        assertEquals("frontend-secret", fake.receivedConfig.getApiKey());
        assertEquals("chosen-qwen-model", fake.receivedConfig.getModelName());
        assertFalse(request.toString().contains("frontend-secret"));
        assertFalse(json.writeValueAsString(request).contains("frontend-secret"));
        assertFalse(json.writeValueAsString(response).contains("frontend-secret"));
        assertFalse(fake.source.contains("frontend-secret"));
        assertFalse(fake.source.contains("chosen-qwen-model"));
    }

    @Test
    void missingOrIncompleteFrontendConfigReturnsUnavailableWithoutFallbackOrModelCall() {
        var fake = new FakeGenerator(PRICE_PLAN);
        var controller = new RoutingPreviewController(service(fake));
        for (var config : java.util.Arrays.asList(null,
                new RoutingModelConfig("", "https://example.test", "model"),
                new RoutingModelConfig("key", "", "model"),
                new RoutingModelConfig("key", "https://example.test", ""))) {
            var result = controller.preview(new RoutingPreviewController.PreviewRequest("quote", config));
            assertEquals(MODEL_UNAVAILABLE, result.getData().status());
            assertNull(result.getData().plan());
            assertFalse(result.getData().executable());
        }
        assertEquals(0, fake.calls);
    }

    @Test
    void typedFailureAfterStructurePassesProducesClarificationAndNoUnsafeQuantity() throws Exception {
        String output = PRICE_PLAN.replace("TOPCon580W", "580W").replace("1200 pieces", "2000套");
        var result = service(new FakeGenerator(output)).preview("580W采购2000套", CONFIG);
        assertTrue(result.structurallyValid());
        assertEquals(NEEDS_CLARIFICATION, result.status());
        assertFalse(result.parametersValid());
        assertFalse(result.executable());
        assertEquals(2000, result.resolvedSlots().get(1).value());
        assertEquals("套", result.resolvedSlots().get(1).unit());
        assertFalse(result.resolvedTasks().get(0).parameters().containsKey("quantity"));
        var serialized = json.readTree(json.writeValueAsString(result));
        assertTrue(serialized.path("clarifications").size() > 0);
        assertEquals("2000套", serialized.path("resolvedSlots").path(1).path("evidence").textValue());
    }

    @Test
    void completeModelPieceCountAndAbuDhabiValidateWithoutExecutingOrClaimingFreightRate() {
        String output = """
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m","quantity":"q"}},
                          {"tool":"estimate_freight_cif","slots":{"destPort":"p","quantity":"q"}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon580W"},
                    {"id":"q","type":"QUANTITY","evidence":"２，０００片"},
                    {"id":"p","type":"PORT","evidence":"阿布扎比"}]}
                """;
        var result = service(new FakeGenerator(output)).preview("TOPCon580W采购２，０００片，发往阿布扎比", CONFIG);
        assertEquals(PARAMETERS_VALIDATED, result.status());
        assertTrue(result.parametersValid());
        assertEquals(2000, result.resolvedTasks().get(1).parameters().get("quantity"));
        assertEquals("Abu Dhabi", result.resolvedTasks().get(1).parameters().get("destPort"));
        assertEquals("２，０００片", result.resolvedSlots().get(1).evidence());
        assertTrue(result.clarifications().isEmpty());
        assertFalse(result.executable());
    }

    private RoutingPreviewService service(FakeGenerator fake) {
        List<IToolHandler> handlers = new ArrayList<>();
        for (var definition : List.of(new TierPriceCalculatorTool().getDefinition(),
                new FreightCifEstimatorTool().getDefinition(), new CurrencyExchangeRateTool().getDefinition(),
                new CustomsDataLookupTool().getDefinition(), new RagVectorSearchTool(null).getDefinition())) {
            handlers.add(throwingHandler(definition));
        }
        return new RoutingPreviewService(fake, new EvidenceToolCatalog(new ToolRegistry(handlers)),
                new StrictEvidencePlanReader(), json);
    }

    private static IToolHandler throwingHandler(ToolDefinition definition) {
        return new IToolHandler() {
            public ToolDefinition getDefinition() { return definition; }
            public Object execute(Map<String, Object> params) { throw new AssertionError("Preview must never execute a tool"); }
        };
    }

    private static final class FakeGenerator implements RoutingPlanGenerator {
        final String output;
        int calls;
        boolean available = true;
        boolean fail;
        String source;
        String instructions;
        RoutingModelConfig receivedConfig;
        FakeGenerator(String output) { this.output = output; }
        public boolean isAvailable(RoutingModelConfig config) {
            return available && config != null && config.complete();
        }
        public String generate(String instructions, String source, RoutingModelConfig config) {
            calls++;
            this.instructions = instructions;
            this.source = source;
            this.receivedConfig = config;
            if (fail) throw new IllegalStateException("provider secret response");
            return output;
        }
    }
}
