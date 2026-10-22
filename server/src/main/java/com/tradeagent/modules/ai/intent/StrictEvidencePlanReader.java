package com.tradeagent.modules.ai.intent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.EvidenceRoutingPlan;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Model JSON is never repaired, stripped from Markdown, or coerced to another type. */
@Component
public final class StrictEvidencePlanReader {
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);

    public EvidenceRoutingPlan read(String source) throws JsonProcessingException {
        if (source == null || source.isBlank() || source.length() > 65_536) {
            throw new IllegalArgumentException("Invalid model JSON");
        }
        JsonNode root = json.readTree(source);
        object(root, Set.of("tasks", "slotCandidates"));
        array(root.get("tasks"));
        array(root.get("slotCandidates"));
        for (JsonNode task : root.get("tasks")) {
            object(task, Set.of("tool", "slots"));
            text(task.get("tool"));
            if (task.get("slots") == null || !task.get("slots").isObject()) invalid();
            for (JsonNode reference : task.get("slots")) {
                if (!reference.isNull()) text(reference);
            }
        }
        for (JsonNode slot : root.get("slotCandidates")) {
            object(slot, Set.of("id", "type", "evidence", "start", "end", "sourceMessageId"));
            text(slot.get("id"));
            text(slot.get("type"));
            text(slot.get("evidence"));
            if (slot.hasNonNull("sourceMessageId")) text(slot.get("sourceMessageId"));
            for (String offset : Set.of("start", "end")) {
                JsonNode value = slot.get(offset);
                if (value != null && !value.isNull()
                        && (!value.isIntegralNumber() || !value.canConvertToInt())) invalid();
            }
        }
        // Explicit shape checks above prevent Jackson number/boolean -> String coercion.
        return json.treeToValue(root, EvidenceRoutingPlan.class);
    }

    private static void object(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) invalid();
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) invalid(); });
    }

    private static void array(JsonNode node) {
        if (node == null || !node.isArray()) invalid();
    }

    private static void text(JsonNode node) {
        if (node == null || !node.isTextual()) invalid();
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid model JSON shape");
    }
}
