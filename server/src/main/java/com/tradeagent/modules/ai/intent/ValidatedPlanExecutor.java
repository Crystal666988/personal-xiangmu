package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.dag.ReActStep;
import com.tradeagent.modules.ai.tools.*;
import org.springframework.stereotype.Service;

import java.util.*;

/** The sole chat execution gate: whole-plan preflight, then ordered registered read-only handlers. */
@Service
public final class ValidatedPlanExecutor {
    private final ToolRegistry registry;
    public ValidatedPlanExecutor(ToolRegistry registry) { this.registry = registry; }
    public record Call(int index, String tool, Map<String, Object> parameters, Map<String, String> parameterSources,
                       Integer fobFromTask) {}
    public record Prepared(List<Call> calls, List<String> clarifications) {
        public boolean ready() { return !calls.isEmpty() && clarifications.isEmpty(); }
    }
    public record Execution(boolean success, TurnTelemetry telemetry, List<ReActStep> steps) {}

    public Prepared preflight(RoutingPreviewService.Preview plan, List<RoutingPreviewService.UserSource> sources) {
        if (plan == null || !plan.structurallyValid() || !plan.parametersValid()) return new Prepared(List.of(), List.of("任务参数尚未完整校验，暂不执行。"));
        List<Call> calls = new ArrayList<>(); List<String> questions = new ArrayList<>();
        boolean hasDuplicateNonRag = plan.resolvedTasks().stream()
                .map(TypedRoutingResolver.ResolvedTask::tool)
                .filter(tool -> !"rag_vector_search".equals(tool))
                .collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(), java.util.stream.Collectors.counting()))
                .values().stream().anyMatch(count -> count > 1);
        if (plan.resolvedTasks().size() > 5 || hasDuplicateNonRag) return new Prepared(List.of(), List.of("本轮只支持每个已登记工具调用一次，请拆分多商品或重复任务。"));
        boolean cifRequested = sources.stream().anyMatch(source -> source.content().matches("(?is).*(?:(?<![a-z0-9])cif(?![a-z0-9])|到岸|保险).*"));
        for (var task : plan.resolvedTasks()) try {
            if (!task.valid() || registry.getDefinition(task.tool()) == null) throw new IllegalArgumentException("任务工具尚未注册。");
            var prepared = TradeToolRules.prepare(task.tool(), task.parameters());
            Map<String, String> parameterSources = new LinkedHashMap<>(prepared.parameterSources());
            Integer dependency = null;
            if ("estimate_freight_cif".equals(task.tool()) && !prepared.parameters().containsKey("fobTotalUsd")) {
                var prices = calls.stream().filter(call -> "calculate_tier_price".equals(call.tool())
                        && Objects.equals(call.parameters().get("quantity"), prepared.parameters().get("quantity"))).toList();
                if (prices.size() == 1) { dependency = prices.get(0).index(); parameterSources.put("fobTotalUsd", "DERIVED_TASK[" + dependency + "].fobTotalUsdRaw"); }
                else if (cifRequested) throw new IllegalArgumentException("计算CIF需要明确的FOB总金额（USD）；请补充金额或完整产品报价信息。");
            }
            calls.add(new Call(task.index(), task.tool(), prepared.parameters(), Map.copyOf(parameterSources), dependency));
        } catch (IllegalArgumentException e) { questions.add(e.getMessage()); }
        return new Prepared(List.copyOf(calls), List.copyOf(questions));
    }

    public Execution execute(Prepared prepared) {
        if (!prepared.ready()) throw new IllegalArgumentException("Whole-plan business preflight is required");
        List<ToolExecutionTrace> traces = new ArrayList<>(); List<ReActStep> steps = new ArrayList<>();
        Map<Integer, Double> fob = new HashMap<>(); boolean success = true; int step = 1;
        for (var call : prepared.calls()) {
            Map<String, Object> params = new LinkedHashMap<>(call.parameters());
            if (call.fobFromTask() != null) {
                Double value = fob.get(call.fobFromTask());
                if (value == null) { success = false; break; }
                params.put("fobTotalUsd", value);
            }
            String name = registry.getDefinition(call.tool()).getName();
            steps.add(ReActStep.action(step++, call.tool(), name, Map.copyOf(params)));
            ToolExecutionTrace trace = registry.executeTool(call.tool(), params);
            Object raw = trace.getOutputResult();
            if ("calculate_tier_price".equals(call.tool()) && raw instanceof Map<?, ?> result
                    && result.get("fobTotalUsdRaw") instanceof Number number && number.doubleValue() > 0) fob.put(call.index(), number.doubleValue());
            if (raw != null) trace.setOutputResult(Map.of("result", raw, "parameterSources", call.parameterSources()));
            traces.add(trace);
            steps.add(ReActStep.observation(step++, call.tool(), name, trace.getStatus(), trace.getOutputResult(), trace.getLatencyMs()));
            if (!"SUCCESS".equals(trace.getStatus())) { success = false; break; }
        }
        long total = traces.stream().mapToLong(ToolExecutionTrace::getLatencyMs).sum();
        var telemetry = TurnTelemetry.builder().turnId("turn_" + UUID.randomUUID()).toolsCalledCount(traces.size())
                .totalLatencyMs(total).avgLatencyMs(traces.isEmpty() ? 0 : (double) total / traces.size()).traces(List.copyOf(traces)).build();
        return new Execution(success, telemetry, List.copyOf(steps));
    }
    public static TurnTelemetry emptyTelemetry() {
        return TurnTelemetry.builder().turnId("turn_" + UUID.randomUUID()).toolsCalledCount(0).totalLatencyMs(0)
                .avgLatencyMs(0).traces(List.of()).build();
    }
}
