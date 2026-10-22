package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.dag.EvidencePlanValidator;
import com.tradeagent.core.dag.EvidencePlanValidator.Issue;
import com.tradeagent.core.dag.EvidencePlanValidator.ToolPolicy;
import com.tradeagent.core.dag.EvidenceRoutingPlan;
import com.tradeagent.core.dag.EvidenceRoutingPlan.SlotCandidate;
import com.tradeagent.core.dag.EvidenceRoutingPlan.SlotType;
import com.tradeagent.modules.ai.intent.TypedSlotParser.ResolvedSlot;

import java.util.*;

/** Typed preview only. None of these parameter maps authorize a call to a legacy tool. */
final class TypedRoutingResolver {
    record ResolvedTask(int index, String tool, Map<String, Object> parameters,
                        Map<String, String> units, boolean valid, List<Issue> issues) {
        ResolvedTask { parameters = Map.copyOf(parameters); units = Map.copyOf(units); issues = List.copyOf(issues); }
    }
    record Resolution(List<ResolvedSlot> resolvedSlots, List<ResolvedTask> resolvedTasks,
                      List<String> clarifications, List<Issue> issues, boolean parametersValid) {}

    static Resolution resolve(String original, EvidenceRoutingPlan plan, Map<String, ToolPolicy> policies) {
        return resolveInternal(original, null, plan, policies);
    }

    static Resolution resolveSources(Map<String, String> sources, EvidenceRoutingPlan plan, Map<String, ToolPolicy> policies) {
        return resolveInternal("", sources, plan, policies);
    }

    private static Resolution resolveInternal(String original, Map<String, String> sources,
                                               EvidenceRoutingPlan plan, Map<String, ToolPolicy> policies) {
        // Missing required bindings are safe to clarify; every other structural failure stops parsing.
        var structural = sources == null ? EvidencePlanValidator.validate(original, plan, policies)
                : EvidencePlanValidator.validateSources(sources, plan, policies);
        if (structural.issues().stream().anyMatch(issue -> !"MISSING_REQUIRED_SLOT".equals(issue.code()))) {
            throw new IllegalArgumentException("Source validation must succeed before typed parsing");
        }
        var slots = plan.slotCandidates().stream().map(slot -> TypedSlotParser.parse(
                sources == null ? original : sources.get(slot.sourceMessageId()), slot)).toList();
        Map<String, ResolvedSlot> byId = new LinkedHashMap<>();
        slots.forEach(slot -> byId.put(slot.id(), slot));
        List<Issue> issues = new ArrayList<>();
        LinkedHashSet<String> clarifications = new LinkedHashSet<>();
        List<ResolvedTask> tasks = new ArrayList<>();
        // Report missing business fields before optional/invalid fields.
        for (int i = 0; i < plan.tasks().size(); i++) {
            var task = plan.tasks().get(i);
            for (String required : policies.get(task.tool()).required().stream().sorted().toList()) {
                if (task.slots().get(required) == null) clarifications.add(missing(required));
            }
        }
        for (int i = 0; i < plan.tasks().size(); i++) {
            var task = plan.tasks().get(i);
            Map<String, Object> parameters = new LinkedHashMap<>();
            Map<String, String> units = new LinkedHashMap<>();
            List<Issue> taskIssues = new ArrayList<>();
            for (String required : policies.get(task.tool()).required().stream().sorted().toList()) {
                if (task.slots().get(required) == null) {
                    taskIssues.add(new Issue("MISSING_REQUIRED_SLOT", "tasks[" + i + "].slots." + required));
                }
            }
            for (var binding : task.slots().entrySet()) {
                if (binding.getValue() == null) continue;
                var slot = byId.get(binding.getValue());
                if (slot == null) {
                    for (var s : byId.values()) {
                        if (Objects.equals(s.evidence(), binding.getValue())) {
                            slot = s;
                            break;
                        }
                    }
                }
                if (slot == null && policies.get(task.tool()).parameters().get(binding.getKey()) == SlotType.TEXT) {
                    String srcId = sources != null && !sources.isEmpty() ? new ArrayList<>(sources.keySet()).get(sources.size() - 1) : null;
                    SlotCandidate cand = new SlotCandidate(binding.getValue(), SlotType.TEXT, binding.getValue(), null, null, srcId);
                    slot = TypedSlotParser.parse(sources == null ? original : sources.get(srcId), cand);
                }
                if (slot == null) {
                    taskIssues.add(new Issue("UNKNOWN_SLOT_REFERENCE", "tasks[" + i + "].slots." + binding.getKey()));
                    continue;
                }
                String code = slot.status() != TypedSlotParser.Status.RESOLVED ? slot.issues().get(0).code()
                        : bindingIssue(sources == null ? original : sources.get(slot.source().sourceMessageId()),
                                task.tool(), binding.getKey(), slot);
                if (code != null) {
                    taskIssues.add(new Issue(code, "tasks[" + i + "].slots." + binding.getKey()));
                    clarifications.add(question(binding.getKey(), slot.type(), code));
                    continue;
                }
                parameters.put(binding.getKey(), slot.value());
                if (slot.unit() != null) units.put(binding.getKey(), slot.unit());
            }
            if (parameters.containsKey("productModel") && parameters.containsKey("wattage")) {
                String model = parameters.get("productModel").toString();
                int watts = Integer.parseInt(model.replaceAll(".*-(\\d+)W", "$1"));
                if (watts != (Integer) parameters.get("wattage")) {
                    taskIssues.add(new Issue("MODEL_POWER_CONFLICT", "tasks[" + i + "].slots.wattage"));
                    parameters.remove("wattage"); units.remove("wattage");
                    clarifications.add("产品型号与标称功率不一致，请确认完整型号和功率。");
                }
            }
            if ("currency_exchange_rate".equals(task.tool()) && parameters.size() == 2
                    && !(parameters.get("baseCurrency") + "/" + parameters.get("targetCurrency"))
                    .equals(TypedSlotParser.currencyDirection(sources == null ? original
                            : String.join("\n", task.slots().values().stream().filter(Objects::nonNull)
                                .map(byId::get).map(slot -> sources.get(slot.source().sourceMessageId())).distinct().toList())))) {
                taskIssues.add(new Issue("CURRENCY_DIRECTION_REQUIRES_CONFIRMATION", "tasks[" + i + "]"));
                parameters.clear(); units.clear();
                clarifications.add("请确认原币种和目标币种的兑换方向。");
            }
            if ("currency_exchange_rate".equals(task.tool()) && parameters.size() == 2
                    && !Set.of("USD/CNY", "USD/EUR", "USD/AED", "USD/GBP", "EUR/USD", "CNY/USD")
                    .contains(parameters.get("baseCurrency") + "/" + parameters.get("targetCurrency"))) {
                taskIssues.add(new Issue("CURRENCY_PAIR_NOT_SUPPORTED", "tasks[" + i + "]"));
                parameters.clear(); units.clear();
                clarifications.add("当前汇率工具没有该币种组合的登记汇率，请确认所需查询。");
            }
            issues.addAll(taskIssues);
            tasks.add(new ResolvedTask(i, task.tool(), parameters, units, taskIssues.isEmpty(), taskIssues));
        }
        // Unreferenced bad annotations also block success; a model cannot hide conflicts in unused slots.
        for (var slot : slots) if (slot.status() != TypedSlotParser.Status.RESOLVED) {
            issues.addAll(slot.issues());
            clarifications.add(question("", slot.type(), slot.issues().get(0).code()));
        }
        boolean valid = !tasks.isEmpty() && tasks.stream().allMatch(ResolvedTask::valid)
                && slots.stream().allMatch(slot -> slot.status() == TypedSlotParser.Status.RESOLVED);
        return new Resolution(List.copyOf(slots), List.copyOf(tasks), List.copyOf(clarifications), List.copyOf(issues), valid);
    }

    private static String bindingIssue(String original, String tool, String parameter, ResolvedSlot slot) {
        if (slot.type() == SlotType.QUANTITY
                && !Set.of("片", "块", "piece", "pieces", "pcs").contains(slot.unit())) return "PURCHASE_UNIT_REQUIRES_CONFIRMATION";
        if (slot.type() == SlotType.PORT) {
            var mentions = TypedSlotParser.ports(original);
            var relevant = mentions.stream().filter(port -> port.role().equals(parameter) || port.role().equals("unknown"))
                    .map(TypedSlotParser.PortMention::value).collect(java.util.stream.Collectors.toSet());
            if (relevant.size() > 1) return "COMPETING_PORTS_IN_QUERY";
            if (!mentions.isEmpty() && relevant.isEmpty()) return "PORT_ROLE_MISMATCH";
            if (!relevant.isEmpty() && !relevant.contains(slot.value())) return "PORT_ROLE_MISMATCH";
        }
        if (slot.type() == SlotType.MONEY) {
            if ("basePricePerWatt".equals(parameter) && !"USD/W".equals(slot.unit())) return "UNIT_PRICE_REQUIRES_USD_PER_WATT";
            if ("fobTotalUsd".equals(parameter) && !"USD".equals(slot.unit())) return "FOB_REQUIRES_USD_TOTAL";
        }
        return null;
    }

    private static String missing(String field) {
        return switch (field) {
            case "productModel" -> "请补充明确的完整产品型号。";
            case "quantity" -> "请补充明确的采购数量和单位。";
            case "destPort" -> "请补充明确的目的港。";
            case "baseCurrency" -> "请明确要兑换的原币种。";
            case "targetCurrency" -> "请明确要兑换的目标币种。";
            case "companyName" -> "请补充要查询的完整公司名称。";
            case "query" -> "请补充要检索的具体问题。";
            default -> "请补充明确的“" + field + "”信息。";
        };
    }

    private static String question(String parameter, SlotType type, String code) {
        if ("PURCHASE_UNIT_REQUIRES_CONFIRMATION".equals(code)) return "请确认采购单位与片数的关系；当前工具需要明确片数，不能自动把套数换成片数。";
        return switch (type) {
            case QUANTITY -> code.contains("COMPETING") || code.contains("AMBIGUOUS") || code.contains("RANGE")
                    ? "原文中的采购数量存在多个值或范围，请明确本次采用的数量和单位。" : "采购数量格式或原文边界不明确，请确认完整的数量和单位。";
            case MODEL -> "请确认完整产品型号；仅有功率或多个型号时，不能唯一匹配已登记的演示型号目录。";
            case PORT -> "originPort".equals(parameter) ? "请明确本次起运港；原文港口存在歧义或未登记。"
                    : "请明确本次目的港；原文港口存在歧义或未登记。";
            case CURRENCY -> "请明确具体币种；货币符号或多个币种不能唯一确定本次参数。";
            case COMPANY -> "请确认要查询的完整公司名称；当前仅支持已登记的演示公司别名。";
            case MONEY -> "请确认明确金额、币种和计价单位，区分总金额与每瓦单价。";
            case HS_CODE -> "请提供带 HS 编码说明的完整编码，并确认本次采用的编码。";
            case POWER -> "请确认完整的正整数功率及 W 单位。";
            case RESULT_LIMIT -> "请明确要返回的结果条数（当前预览支持 1 至 50 条）。";
            case DOCUMENT_ID -> "请确认实际文档标识；当前预览尚未校验文档目录和访问权限。";
            case CONTAINER_COUNT -> "请明确柜数和柜型；不能自动换算为采购片数。";
            case TEXT -> "请明确需要使用的原文内容。";
        };
    }
}
