package com.tradeagent.modules.ai.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class StrictEvidencePlanReaderTest {
    private final StrictEvidencePlanReader reader = new StrictEvidencePlanReader();

    @Test
    void acceptsContractWithMissingBindingsAndOptionalOffsets() throws Exception {
        var plan = reader.read("""
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m","quantity":null}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"580W","start":0,"end":4}]}
                """);
        assertNull(plan.tasks().get(0).slots().get("quantity"));
        assertEquals("580W", plan.slotCandidates().get(0).evidence());
        assertEquals(4, plan.slotCandidates().get(0).end());
        assertTrue(reader.read("{\"tasks\":[],\"slotCandidates\":[]}").tasks().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[]", "{}", "{\"tasks\":null,\"slotCandidates\":[]}",
            "{\"tasks\":[],\"slotCandidates\":[]} {}", "{\"tasks\":[],\"slotCandidates\":[]} trailing",
            "```json\n{\"tasks\":[],\"slotCandidates\":[]}\n```",
            "{\"tasks\":[],\"tasks\":[],\"slotCandidates\":[]}",
            "{\"tasks\":[],\"slotCandidates\":[],\"executable\":true}",
            "{\"tasks\":[{\"tool\":123,\"slots\":{}}],\"slotCandidates\":[]}",
            "{\"tasks\":[{\"tool\":true,\"slots\":{}}],\"slotCandidates\":[]}",
            "{\"tasks\":[{\"tool\":\"x\",\"slots\":{\"quantity\":2000}}],\"slotCandidates\":[]}",
            "{\"tasks\":[{\"tool\":\"x\",\"slots\":{\"quantity\":true}}],\"slotCandidates\":[]}",
            "{\"tasks\":[{\"tool\":\"x\",\"slots\":{\"quantity\":\"a\",\"quantity\":\"b\"}}],\"slotCandidates\":[]}",
            "{\"tasks\":[{\"tool\":\"x\",\"slots\":{},\"params\":{}}],\"slotCandidates\":[]}",
            "{\"tasks\":[null],\"slotCandidates\":[]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":1,\"type\":\"MODEL\",\"evidence\":\"a\"}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":\"UNKNOWN\",\"evidence\":\"a\"}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":0,\"evidence\":\"a\"}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":\"MODEL\",\"evidence\":580}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":\"MODEL\",\"evidence\":true}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":\"MODEL\",\"evidence\":\"a\",\"value\":580}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":\"MODEL\",\"evidence\":\"a\",\"start\":\"0\"}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":\"MODEL\",\"evidence\":\"a\",\"start\":0.1}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":\"MODEL\",\"evidence\":\"a\",\"start\":2147483648}]}",
            "{\"tasks\":[],\"slotCandidates\":[{\"id\":\"a\",\"type\":\"MODEL\",\"evidence\":\"a\",\"start\":false}]}"
    })
    void rejectsInvalidOrCoercedModelOutput(String output) {
        assertThrows(Exception.class, () -> reader.read(output));
    }

    @Test
    void rejectsNullAndOversizedOutput() {
        assertThrows(IllegalArgumentException.class, () -> reader.read(null));
        assertThrows(IllegalArgumentException.class, () -> reader.read(" ".repeat(65_537)));
    }
}
