package com.tradeagent.core.dag;

import com.tradeagent.modules.ai.tools.ToolDefinition;
import com.tradeagent.modules.ai.tools.TradeToolRules;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Supplier;

/** Server policy, never an LLM-selected flag. Unknown/unclassified tools fail closed. */
@Component
public class HitlRiskEvaluator {
    private static final Set<String> WRITES = Set.of("generate_pi_invoice", "send_email", "send_marketing_email",
            "update_product", "update_product_parameters", "apply_discount");
    private record Permit(String tool, Map<String, Object> parameters) {}
    private static final ThreadLocal<Permit> PERMIT = new ThreadLocal<>();

    public static Optional<String> risk(ToolDefinition tool, Map<String, Object> params) {
        if (tool == null) throw new IllegalArgumentException("工具未注册");
        if (params.containsKey("discountRate")) {
            BigDecimal rate = number(params.get("discountRate"));
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0)
                throw new IllegalArgumentException("discountRate 必须为 0 到 1 的小数");
            if (rate.compareTo(new BigDecimal("0.10")) >= 0) return Optional.of("折扣达到或超过 10%，需要人工审批。");
        }
        if (hasSideEffects(tool))
            return Optional.of("该工具会修改业务数据、生成正式单据或向外部发送内容，需要人工审批。");
        if ("calculate_tier_price".equals(tool.getId()) && params.containsKey("basePricePerWatt")) {
            int quantity = params.containsKey("quantity") ? TradeToolRules.integer(params, "quantity") : 1;
            BigDecimal tier = TradeToolRules.tierPriceFactor(quantity);
            BigDecimal actual = number(params.get("basePricePerWatt")).multiply(tier).setScale(4, RoundingMode.HALF_UP);
            if (actual.compareTo(TradeToolRules.DEMO_BASE_PRICE_PER_WATT.multiply(new BigDecimal("0.90"))) <= 0)
                return Optional.of("预计成交单瓦价较登记演示基准价 0.118 USD 低至少 10%，需要人工审批。");
        }
        return Optional.empty();
    }
    public static boolean hasSideEffects(ToolDefinition tool) {
        return tool == null || WRITES.contains(tool.getId()) || !tool.isReadOnly();
    }
    private static BigDecimal number(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("风险参数必须为数值");
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("风险参数必须为有限数值"); }
    }
    public static boolean authorized(String tool, Map<String, Object> params) {
        Permit permit = PERMIT.get();
        return permit != null && permit.tool().equals(tool) && permit.parameters().equals(params);
    }
    // Package private: only the durable DAG coordinator grants a one-call capability.
    static <T> T approvedCall(String tool, Map<String, Object> params, Supplier<T> call) {
        Permit previous = PERMIT.get();
        try { PERMIT.set(new Permit(tool, Map.copyOf(params))); return call.get(); }
        finally { if (previous == null) PERMIT.remove(); else PERMIT.set(previous); }
    }
}
