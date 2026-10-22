package com.tradeagent.modules.ai.intent;

/** One model request, no retries, repair, tool callbacks, or execution. */
public interface RoutingPlanGenerator {
    boolean isAvailable(RoutingModelConfig modelConfig);
    String generate(String instructions, String sourceJson, RoutingModelConfig modelConfig);
}
