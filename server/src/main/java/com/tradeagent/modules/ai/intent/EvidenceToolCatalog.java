package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.dag.EvidencePlanValidator.ToolPolicy;
import com.tradeagent.core.dag.EvidenceRoutingPlan.SlotType;
import com.tradeagent.modules.ai.tools.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registered schemas supply requiredness; backend rules supply semantic types. */
@Component
public final class EvidenceToolCatalog {
    private final ToolRegistry registry;
    private static final Map<String, Map<String, SlotType>> SEMANTICS = Map.of(
            "calculate_tier_price", Map.of("productModel", SlotType.MODEL, "quantity", SlotType.QUANTITY,
                    "wattage", SlotType.POWER, "basePricePerWatt", SlotType.MONEY),
            "estimate_freight_cif", Map.of("destPort", SlotType.PORT, "originPort", SlotType.PORT,
                    "quantity", SlotType.QUANTITY, "fobTotalUsd", SlotType.MONEY),
            "currency_exchange_rate", Map.of("baseCurrency", SlotType.CURRENCY, "targetCurrency", SlotType.CURRENCY),
            "customs_data_lookup", Map.of("companyName", SlotType.COMPANY, "hsCode", SlotType.HS_CODE),
            "rag_vector_search", Map.of("query", SlotType.TEXT, "topK", SlotType.RESULT_LIMIT, "docId", SlotType.DOCUMENT_ID));
    private static final Map<String, String> PURPOSES = Map.of(
            "calculate_tier_price", "Calculate product tier price for a specified model and purchase quantity",
            "estimate_freight_cif", "Estimate freight/CIF for a destination port and purchase quantity",
            "currency_exchange_rate", "Look up an explicitly requested currency pair",
            "customs_data_lookup", "Look up customs records for a specified company",
            "rag_vector_search", "Search technical knowledge using a verbatim question or query excerpt");

    public EvidenceToolCatalog(ToolRegistry registry) { this.registry = registry; }

    public record Parameter(SlotType type, boolean required) {}
    public record Tool(String id, String purpose, Map<String, Parameter> parameters) {}
    public record Snapshot(Map<String, ToolPolicy> policies, List<Tool> tools) {}

    /** One snapshot drives both the prompt and validation; unmapped tools stay unavailable. */
    public Snapshot snapshot() {
        Map<String, ToolPolicy> policies = new LinkedHashMap<>();
        var tools = new java.util.ArrayList<Tool>();
        for (var definition : registry.listAllDefinitions()) {
            var types = SEMANTICS.get(definition.getId());
            if (types == null) continue;
            var schema = definition.getParameterSchema();
            if (schema == null || !schema.keySet().equals(types.keySet())) {
                throw new IllegalStateException("Routing tool schema requires a backend semantic mapping update");
            }
            Set<String> required = new LinkedHashSet<>();
            for (var entry : schema.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> parameter)
                        || !(parameter.get("required") instanceof Boolean)
                        || !(parameter.get("type") instanceof String primitive)
                        || !Set.of("string", "integer", "number").contains(primitive)) {
                    throw new IllegalStateException("Unsupported routing parameter schema");
                }
                if (Boolean.TRUE.equals(parameter.get("required"))) required.add(entry.getKey());
            }
            // The legacy FX schema marks both optional, but a preview must never infer a pair.
            if ("currency_exchange_rate".equals(definition.getId())) required.addAll(types.keySet());
            ToolPolicy policy = new ToolPolicy(types, required);
            policies.put(definition.getId(), policy);
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            types.keySet().stream().sorted().forEach(name -> parameters.put(name,
                    new Parameter(types.get(name), policy.required().contains(name))));
            // Do not expose examples, defaults, or default-bearing schema descriptions.
            tools.add(new Tool(definition.getId(), PURPOSES.get(definition.getId()), Map.copyOf(parameters)));
        }
        return new Snapshot(Map.copyOf(policies), List.copyOf(tools));
    }
}
