package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.dag.EvidencePlanValidator.ToolPolicy;
import com.tradeagent.core.dag.EvidenceRoutingPlan;
import com.tradeagent.core.dag.EvidenceRoutingPlan.SlotCandidate;
import com.tradeagent.core.dag.EvidenceRoutingPlan.SlotType;
import com.tradeagent.core.dag.EvidenceRoutingPlan.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tradeagent.modules.ai.intent.TypedSlotParser.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedRoutingResolverTest {
    @Test
    void parsesExplicitQuantitiesWithoutDiscardingUnitsOrChangingSource() {
        for (var example : Map.of("２，０００片", 2000, "2,000套", 2000, "两千片", 2000,
                "一万二千片", 12000, "一百零二块", 102, "1.2万pieces", 12000).entrySet()) {
            var slot = parse(SlotType.QUANTITY, example.getKey(), example.getKey());
            assertEquals(RESOLVED, slot.status(), example.getKey());
            assertEquals(example.getValue(), slot.value());
            assertEquals(example.getKey(), slot.evidence());
            assertNotNull(slot.unit());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"580W", "2026年", "125,000美元", "3个40HQ", "40HQ3个", "2,00套", "-2000套",
            "1.5套", "2000~3000套", "2000 - 3000套", "2000到3000套", "一百二片", "0片", "2147483648片"})
    void rejectsNumbersWithoutValidUnambiguousPurchaseUnits(String evidence) {
        assertNotEquals(RESOLVED, parse(SlotType.QUANTITY, evidence, evidence).status(), evidence);
    }

    @Test
    void cannotCropSignsDecimalsGroupingOrRangesFromOriginalEvidence() {
        for (String original : List.of("采购-2000套", "采购1.2000套", "采购12,2000套", "采购2000~3000套")) {
            String evidence = original.contains("3000") ? "3000套" : "2000套";
            assertNotEquals(RESOLVED, parse(SlotType.QUANTITY, original, evidence).status(), original);
        }
        assertNotEquals(RESOLVED, parse(SlotType.POWER, "功率1580W", "580W").status());
        assertNotEquals(RESOLVED, parse(SlotType.POWER, "功率1.580W", "580W").status());
    }

    @Test
    void originalCompetingQuantitiesAreCheckedEvenIfModelSuppliesOnlyOne() {
        for (String original : List.of("采购2000片或3000片", "采购2000片或-3000片", "采购2000片或2,00片")) {
            assertEquals(AMBIGUOUS, parse(SlotType.QUANTITY, original, "2000片").status(), original);
        }
        assertEquals(RESOLVED, parse(SlotType.QUANTITY,
                "2026年TOPCon580W采购2000片，预算125,000美元，3个40HQ", "2000片").status());
    }

    @Test
    void modelUsesExplicitDemoAliasesAndRepeatedMatchingPowerIsNotAnotherModel() {
        var model = parse(SlotType.MODEL, "TOPCon-580W，标称功率580W，采购2000片", "TOPCon-580W");
        assertEquals(RESOLVED, model.status());
        assertEquals("TOPCon-580W", model.value());
        assertEquals(UNSUPPORTED, parse(SlotType.MODEL, "580W", "580W").status());
        assertEquals(UNSUPPORTED, parse(SlotType.MODEL, "XYZ-540W", "XYZ-540W").status());
        assertEquals(AMBIGUOUS, parse(SlotType.MODEL, "TOPCon580W或PERC550W", "TOPCon580W").status());
        assertEquals(AMBIGUOUS, parse(SlotType.MODEL, "TOPCon580W或540W", "TOPCon580W").status());
    }

    @Test
    void portDictionaryKeepsAbuDhabiDubaiAndJebelAliSeparate() {
        assertEquals("Abu Dhabi", parse(SlotType.PORT, "阿布扎比港", "阿布扎比港").value());
        assertEquals("Dubai", parse(SlotType.PORT, "迪拜", "迪拜").value());
        assertEquals("Jebel Ali", parse(SlotType.PORT, "杰贝阿里", "杰贝阿里").value());
        assertEquals("Jebel Ali", parse(SlotType.PORT, "迪拜杰贝阿里港", "迪拜杰贝阿里港").value());
        assertEquals(UNSUPPORTED, parse(SlotType.PORT, "Fujairah", "Fujairah").status());
        assertEquals(AMBIGUOUS, parse(SlotType.PORT, "Dubai或Abu Dhabi", "Dubai或Abu Dhabi").status());
    }

    @Test
    void portTaskContextSeparatesOriginAndDestinationAndDetectsHiddenCompetition() {
        var policies = Map.of("estimate_freight_cif", new ToolPolicy(
                Map.of("destPort", SlotType.PORT, "originPort", SlotType.PORT, "quantity", SlotType.QUANTITY),
                Set.of("destPort", "quantity")));
        var plan = new EvidenceRoutingPlan(List.of(new Task("estimate_freight_cif",
                Map.of("originPort", "o", "destPort", "d", "quantity", "q"))), List.of(
                slot("o", SlotType.PORT, "宁波"), slot("d", SlotType.PORT, "阿布扎比"), slot("q", SlotType.QUANTITY, "2000片")));
        var valid = TypedRoutingResolver.resolve("从宁波发往阿布扎比，采购2000片", plan, policies);
        assertTrue(valid.parametersValid(), valid.issues().toString());
        assertEquals("Ningbo", valid.resolvedTasks().get(0).parameters().get("originPort"));
        assertEquals("Abu Dhabi", valid.resolvedTasks().get(0).parameters().get("destPort"));
        var conflict = TypedRoutingResolver.resolve("从宁波发往阿布扎比或迪拜，采购2000片", plan, policies);
        assertFalse(conflict.parametersValid());
        assertFalse(conflict.resolvedTasks().get(0).parameters().containsKey("destPort"));
    }

    @Test
    void setsAreParsedButNeverWrittenAsLegacyPieceQuantities() {
        var result = one("采购2000套", SlotType.QUANTITY, "2000套", "quantity");
        assertEquals(RESOLVED, result.resolvedSlots().get(0).status());
        assertEquals(2000, result.resolvedSlots().get(0).value());
        assertEquals("套", result.resolvedSlots().get(0).unit());
        assertFalse(result.parametersValid());
        assertFalse(result.resolvedTasks().get(0).parameters().containsKey("quantity"));
        assertTrue(result.clarifications().get(0).contains("片数"));
    }

    @Test
    void currenciesMoneyAndHsCodesRequireExplicitUnambiguousContext() {
        assertEquals("CNY", parse(SlotType.CURRENCY, "人民币", "人民币").value());
        assertEquals("AED", parse(SlotType.CURRENCY, "AED", "AED").value());
        assertEquals(AMBIGUOUS, parse(SlotType.CURRENCY, "$", "$").status());
        assertEquals(AMBIGUOUS, parse(SlotType.CURRENCY, "￥", "￥").status());
        var money = parse(SlotType.MONEY, "125,000美元", "125,000美元");
        assertEquals(new BigDecimal("125000"), money.value());
        assertEquals("USD", money.unit());
        assertNotEquals(RESOLVED, parse(SlotType.MONEY, "$125,000", "$125,000").status());
        assertEquals("85414300", parse(SlotType.HS_CODE, "HS 8541.4300", "8541.4300").value());
        assertNotEquals(RESOLVED, parse(SlotType.HS_CODE, "编号85414300", "85414300").status());
        assertNotEquals(RESOLVED, parse(SlotType.HS_CODE, "HS 85.414300", "85.414300").status());
    }

    @Test
    void technicalCountsPowerTextAndDocumentIdsHaveSeparateRules() {
        assertEquals(3, parse(SlotType.RESULT_LIMIT, "返回前3条结果", "前3条").value());
        assertNotEquals(RESOLVED, parse(SlotType.RESULT_LIMIT, "采购3片", "3").status());
        assertNotEquals(RESOLVED, parse(SlotType.RESULT_LIMIT, "top 51", "51").status());
        assertEquals(580, parse(SlotType.POWER, "580W", "580W").value());
        assertEquals(3, parse(SlotType.CONTAINER_COUNT, "3个40HQ", "3个40HQ").value());
        assertEquals("40HQ", parse(SlotType.CONTAINER_COUNT, "3个40HQ", "3个40HQ").unit());
        assertEquals(UNSUPPORTED, parse(SlotType.DOCUMENT_ID, "doc-123", "doc-123").status());
        assertEquals("  question  ", parse(SlotType.TEXT, "  question  ", "  question  ").value());
        assertEquals(UNSUPPORTED, parse(SlotType.COMPANY, "Unknown Solar LLC", "Unknown Solar LLC").status());
        assertEquals(RESOLVED, parse(SlotType.COMPANY, "BayWa", "BayWa").status());
    }

    @Test
    void explicitCurrencyDirectionCannotBeReversedByModel() {
        var policy = Map.of("currency_exchange_rate", new ToolPolicy(
                Map.of("baseCurrency", SlotType.CURRENCY, "targetCurrency", SlotType.CURRENCY),
                Set.of("baseCurrency", "targetCurrency")));
        for (boolean reverse : List.of(false, true)) {
            var plan = new EvidenceRoutingPlan(List.of(new Task("currency_exchange_rate",
                    Map.of("baseCurrency", reverse ? "c" : "u", "targetCurrency", reverse ? "u" : "c"))),
                    List.of(slot("u", SlotType.CURRENCY, "美元"), slot("c", SlotType.CURRENCY, "人民币")));
            var result = TypedRoutingResolver.resolve("美元兑换人民币", plan, policy);
            assertEquals(!reverse, result.parametersValid());
            if (reverse) assertTrue(result.resolvedTasks().get(0).parameters().isEmpty());
            var unclear = TypedRoutingResolver.resolve("美元和人民币汇率", plan, policy);
            assertFalse(unclear.parametersValid());
        }
    }

    @Test
    void structureGateStopsNormalizationAndUnusedBadSlotsBlockSuccess() {
        assertThrows(IllegalArgumentException.class,
                () -> one("２０００片", SlotType.QUANTITY, "2000片", "quantity"));
        var policies = Map.of("test", new ToolPolicy(Map.of("quantity", SlotType.QUANTITY), Set.of("quantity")));
        var plan = new EvidenceRoutingPlan(List.of(new Task("test", Map.of("quantity", "q"))),
                List.of(slot("q", SlotType.QUANTITY, "2000片"), slot("unused", SlotType.MODEL, "540W")));
        var result = TypedRoutingResolver.resolve("2000片540W", plan, policies);
        assertFalse(result.parametersValid());
        assertFalse(result.clarifications().isEmpty());
    }

    private static TypedSlotParser.ResolvedSlot parse(SlotType type, String query, String evidence) {
        return TypedSlotParser.parse(query, slot("s", type, evidence));
    }
    private static SlotCandidate slot(String id, SlotType type, String evidence) {
        return new SlotCandidate(id, type, evidence, null, null);
    }
    private static TypedRoutingResolver.Resolution one(String query, SlotType type, String evidence, String parameter) {
        return TypedRoutingResolver.resolve(query, new EvidenceRoutingPlan(
                List.of(new Task("test", Map.of(parameter, "s"))), List.of(slot("s", type, evidence))),
                Map.of("test", new ToolPolicy(Map.of(parameter, type), Set.of(parameter))));
    }
}
