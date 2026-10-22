package com.tradeagent.core.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tradeagent.core.dag.EvidencePlanValidator.*;
import static com.tradeagent.core.dag.EvidenceRoutingPlan.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidencePlanValidatorTest {
    private static final Map<String, ToolPolicy> POLICIES = Map.of(
            "calculate_tier_price", new ToolPolicy(
                    Map.of("productModel", SlotType.MODEL, "quantity", SlotType.QUANTITY),
                    Set.of("productModel", "quantity")),
            "estimate_freight_cif", new ToolPolicy(
                    Map.of("quantity", SlotType.QUANTITY, "destPort", SlotType.PORT,
                            "originPort", SlotType.PORT), Set.of("quantity", "destPort")));

    @Test
    void validatesOneEvidenceCandidateSharedByTwoTasksWithoutNormalization() {
        String query = "\uD83D\uDE80 TOPCon-580W 1,200 pieces to Hamburg";
        var model = slot(query, "model", SlotType.MODEL, "TOPCon-580W");
        var quantity = slot(query, "qty", SlotType.QUANTITY, "1,200 pieces");
        var port = slot(query, "port", SlotType.PORT, "Hamburg");
        var plan = new EvidenceRoutingPlan(List.of(
                new Task("calculate_tier_price", Map.of("productModel", "model", "quantity", "qty")),
                new Task("estimate_freight_cif", Map.of("quantity", "qty", "destPort", "port"))),
                List.of(model, quantity, port));

        assertTrue(validate(query, plan, POLICIES).valid());
        assertEquals(3, model.start()); // UTF-16 offsets, not Unicode code-point offsets.
        assertEquals("1,200 pieces", plan.slotCandidates().get(1).evidence());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2000", "Dubai (Jebel Ali)", "Al-Futtaim Solar Energy Solutions LLC"})
    void rejectsInventedDefaultsEvenWhenCandidateIsNotUsed(String inventedEvidence) {
        var plan = new EvidenceRoutingPlan(List.of(), List.of(
                new SlotCandidate("invented", SlotType.TEXT, inventedEvidence, null, null)));
        assertCodes(validate("Please quote solar panels", plan, POLICIES), "EVIDENCE_NOT_IN_QUERY");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "hamburg", "  Hamburg  "})
    void requiresNonblankVerbatimEvidence(String evidence) {
        assertCodes(validate("Hamburg", new EvidenceRoutingPlan(List.of(), List.of(
                new SlotCandidate("port", SlotType.PORT, evidence, null, null))), POLICIES),
                "EVIDENCE_NOT_IN_QUERY");
    }

    @Test
    void optionalOffsetsMustBePairedInBoundsAndMatchTheirExactOccurrence() {
        String query = "Hamburg to Hamburg";
        assertTrue(validate(query, candidatesOnly(
                new SlotCandidate("p", SlotType.PORT, "Hamburg", null, null)), POLICIES).valid());
        assertTrue(validate(query, candidatesOnly(
                new SlotCandidate("p", SlotType.PORT, "Hamburg", 11, 18)), POLICIES).valid());
        List<Integer[]> invalidSpans = Arrays.asList(
                new Integer[]{null, 7}, new Integer[]{0, null}, new Integer[]{-1, 7},
                new Integer[]{0, 0}, new Integer[]{7, 0}, new Integer[]{0, 99},
                new Integer[]{99, 100}, new Integer[]{1, 8});
        for (Integer[] span : invalidSpans) {
            assertCodes(validate(query, candidatesOnly(new SlotCandidate(
                    "p", SlotType.PORT, "Hamburg", span[0], span[1])), POLICIES), "INVALID_SPAN");
        }
    }

    @Test
    void rejectsCodePointOffsetsWhenOriginalContainsSupplementaryCharacters() {
        assertCodes(validate("\uD83D\uDE80 Hamburg", candidatesOnly(
                new SlotCandidate("p", SlotType.PORT, "Hamburg", 2, 9)), POLICIES), "INVALID_SPAN");
    }

    @Test
    void duplicateIdsCannotSelectOneCandidateSilently() {
        var plan = new EvidenceRoutingPlan(List.of(), List.of(
                new SlotCandidate("p", SlotType.PORT, "Hamburg", null, null),
                new SlotCandidate("p", SlotType.PORT, "Dubai", null, null)));
        assertCodes(validate("Hamburg or Dubai", plan, POLICIES), "DUPLICATE_SLOT");
    }

    @Test
    void omittedAndExplicitNullRequiredBindingsReportMissingWithoutInventingValues() {
        for (boolean explicitNull : List.of(false, true)) {
            Map<String, String> bindings = new LinkedHashMap<>();
            bindings.put("productModel", "model");
            if (explicitNull) bindings.put("quantity", null);
            var plan = new EvidenceRoutingPlan(List.of(new Task("calculate_tier_price", bindings)),
                    List.of(new SlotCandidate("model", SlotType.MODEL, "580W", null, null)));
            var result = validate("quote 580W", plan, POLICIES);
            assertEquals(List.of(new Issue("MISSING_REQUIRED_SLOT", "tasks[0].slots.quantity")), result.issues());
            assertEquals(explicitNull, plan.tasks().get(0).slots().containsKey("quantity"));
        }
    }

    @Test
    void optionalNullBindingMeansMissingButUnknownNullBindingIsRejected() {
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("quantity", "qty");
        bindings.put("destPort", "port");
        bindings.put("originPort", null);
        var candidates = List.of(new SlotCandidate("qty", SlotType.QUANTITY, "100 pieces", null, null),
                new SlotCandidate("port", SlotType.PORT, "Hamburg", null, null));
        assertTrue(validate("100 pieces Hamburg", new EvidenceRoutingPlan(
                List.of(new Task("estimate_freight_cif", bindings)), candidates), POLICIES).valid());
        bindings.put("invented", null);
        bindings.put(null, null);
        assertCodes(validate("100 pieces Hamburg", new EvidenceRoutingPlan(
                List.of(new Task("estimate_freight_cif", bindings)), candidates), POLICIES),
                "UNKNOWN_PARAMETER", "UNKNOWN_PARAMETER");
    }

    @Test
    void rejectsDanglingAndBlankReferencesAndSemanticTypeMismatch() {
        for (String reference : List.of("missing", "")) {
            assertCodes(validate("580W", pricePlan(reference, SlotType.MODEL), POLICIES),
                    "UNKNOWN_SLOT_REFERENCE");
        }
        assertCodes(validate("580W", pricePlan("model", SlotType.MODEL), POLICIES),
                "SLOT_TYPE_MISMATCH");
    }

    @Test
    void emptyPlanRepresentsConversationButNullStructureIsMalformed() {
        assertTrue(validate("hello", new EvidenceRoutingPlan(List.of(), List.of()), Map.of()).valid());
        assertCodes(validate(null, new EvidenceRoutingPlan(List.of(), List.of()), POLICIES), "INVALID_PLAN");
        assertCodes(validate("hello", null, POLICIES), "INVALID_PLAN");
        assertCodes(validate("hello", new EvidenceRoutingPlan(null, List.of()), POLICIES), "INVALID_PLAN");
        assertCodes(validate("hello", new EvidenceRoutingPlan(List.of(), null), POLICIES), "INVALID_PLAN");
    }

    @Test
    void malformedCandidatesTasksAndBindingsFailClosedWithoutThrowing() {
        List<SlotCandidate> candidates = Arrays.asList(null,
                new SlotCandidate(null, SlotType.TEXT, "x", null, null),
                new SlotCandidate(" ", SlotType.TEXT, "x", null, null),
                new SlotCandidate("id", null, "x", null, null));
        var plan = new EvidenceRoutingPlan(Arrays.asList(null, new Task(null, Map.of()),
                new Task("", Map.of()), new Task("unregistered_tool", Map.of()),
                new Task("calculate_tier_price", null)), candidates);
        assertCodes(validate("x", plan, POLICIES), "INVALID_SLOT", "INVALID_SLOT", "INVALID_SLOT",
                "MISSING_TYPE", "UNKNOWN_TOOL", "UNKNOWN_TOOL", "UNKNOWN_TOOL", "UNKNOWN_TOOL",
                "INVALID_BINDINGS");
    }

    @Test
    void trustedPoliciesCannotHaveUntypedRequiredParametersOrBlankNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolPolicy(Map.of("quantity", SlotType.QUANTITY), Set.of("destPort")));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolPolicy(Map.of(" ", SlotType.TEXT), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> validate("", candidatesOnly(),
                Map.of(" ", new ToolPolicy(Map.of(), Set.of()))));
        assertThrows(NullPointerException.class, () -> validate("", candidatesOnly(), null));
    }

    @Test
    void contractAndResultAreSnapshotsThatCannotChangeAfterValidation() {
        var bindings = new LinkedHashMap<String, String>();
        bindings.put("quantity", null);
        var tasks = new ArrayList<>(List.of(new Task("calculate_tier_price", bindings)));
        var candidates = new ArrayList<SlotCandidate>();
        var plan = new EvidenceRoutingPlan(tasks, candidates);
        bindings.put("quantity", "invented");
        tasks.clear();
        candidates.add(new SlotCandidate("invented", SlotType.QUANTITY, "2000", null, null));
        assertEquals(1, plan.tasks().size());
        assertNull(plan.tasks().get(0).slots().get("quantity"));
        assertTrue(plan.slotCandidates().isEmpty());
        var result = validate("", plan, POLICIES);
        assertThrows(UnsupportedOperationException.class, () -> plan.tasks().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.slotCandidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.tasks().get(0).slots().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.issues().clear());
    }

    @Test
    void jsonContractPreservesNullMissingBindingsAndVerbatimEvidence() throws Exception {
        var mapper = new ObjectMapper();
        String json = """
                {"tasks":[{"tool":"calculate_tier_price","slots":{"productModel":"m","quantity":null}}],
                 "slotCandidates":[{"id":"m","type":"MODEL","evidence":"TOPCon-580W","start":0,"end":11}]}
                """;
        var plan = mapper.readValue(json, EvidenceRoutingPlan.class);
        assertEquals(plan, mapper.readValue(mapper.writeValueAsString(plan), EvidenceRoutingPlan.class));
        assertCodes(validate("TOPCon-580W", plan, POLICIES), "MISSING_REQUIRED_SLOT");
    }

    @Test
    void structuralValidityDoesNotPretendToParseQuantityOrAuthorizeExecution() {
        // This deliberately passes structure: only the later typed parser can reject 580W
        // as a quantity. No tool execution API or executable values exist in this contract.
        assertTrue(validate("580W", pricePlan("model", SlotType.QUANTITY), POLICIES).valid());
    }

    private static EvidenceRoutingPlan pricePlan(String quantityReference, SlotType type) {
        return new EvidenceRoutingPlan(List.of(new Task("calculate_tier_price",
                Map.of("productModel", "actualModel", "quantity", quantityReference))), List.of(
                new SlotCandidate("actualModel", SlotType.MODEL, "580W", null, null),
                new SlotCandidate("model", type, "580W", null, null)));
    }

    private static EvidenceRoutingPlan candidatesOnly(SlotCandidate... candidates) {
        return new EvidenceRoutingPlan(List.of(), List.of(candidates));
    }

    private static SlotCandidate slot(String query, String id, SlotType type, String evidence) {
        int start = query.indexOf(evidence);
        return new SlotCandidate(id, type, evidence, start, start + evidence.length());
    }

    private static void assertCodes(Validation result, String... codes) {
        assertFalse(result.valid());
        assertEquals(List.of(codes), result.issues().stream().map(Issue::code).toList());
    }
}
