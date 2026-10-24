package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 养殖环控阈值规则引擎（纯确定性，无 LLM）。
 *
 * 温氏场景常见环控指标阈值（可按栋舍/季节在配置中细化，此处为工业默认值）：
 * 温度 18~32℃、湿度 40~85%、氨气 ≤20ppm、二氧化碳 ≤3000ppm。
 * 越界 = CRITICAL（需人工立即处理）；逼近边界 90% = WARN（观察项）。
 * 另含数据中断检测：同一设备两次上报间隔超过 10 分钟视为掉线。
 */
@Slf4j
@Service
public class IotThresholdRuleEngine {

    public record Threshold(String metric, String label, String unit, double low, double high) {}

    public static final List<Threshold> THRESHOLDS = List.of(
            new Threshold("temperature", "舍内温度", "℃", 18, 32),
            new Threshold("humidity", "相对湿度", "%", 40, 85),
            new Threshold("nh3", "氨气浓度", "ppm", 0, 20),
            new Threshold("co2", "二氧化碳", "ppm", 0, 3000));

    public static final long DATA_GAP_MINUTES = 10;

    /** 单指标评估结果（越界/逼近均生成事件，纯只读不落库）。 */
    public record EvalResult(String metric, String label, double value, String ruleCode,
                             String severity, String message) {}

    public List<EvalResult> evaluate(String metric, double value) {
        List<EvalResult> results = new ArrayList<>();
        Threshold th = THRESHOLDS.stream().filter(t -> t.metric().equals(metric)).findFirst().orElse(null);
        if (th == null) return results;

        if (value < th.low()) {
            results.add(new EvalResult(metric, th.label(), value,
                    metric.toUpperCase() + "_LOW", "CRITICAL",
                    String.format("%s %.1f%s 低于下限 %.0f%s", th.label(), value, th.unit(), th.low(), th.unit())));
        } else if (value > th.high()) {
            results.add(new EvalResult(metric, th.label(), value,
                    metric.toUpperCase() + "_HIGH", "CRITICAL",
                    String.format("%s %.1f%s 超过上限 %.0f%s", th.label(), value, th.unit(), th.high(), th.unit())));
        } else {
            double range = th.high() - th.low();
            double warnBand = range * 0.1;
            if (value - th.low() < warnBand) {
                results.add(new EvalResult(metric, th.label(), value,
                        metric.toUpperCase() + "_LOW_RISK", "WARN",
                        String.format("%s %.1f%s 逼近下限 %.0f%s，注意观察", th.label(), value, th.unit(), th.low(), th.unit())));
            } else if (th.high() - value < warnBand) {
                results.add(new EvalResult(metric, th.label(), value,
                        metric.toUpperCase() + "_HIGH_RISK", "WARN",
                        String.format("%s %.1f%s 逼近上限 %.0f%s，注意观察", th.label(), value, th.unit(), th.high(), th.unit())));
            }
        }
        return results;
    }

    /** 数据中断检测：与上次上报的间隔超过阈值即生成 DATA_GAP 事件。 */
    public Optional<EvalResult> evaluateGap(LocalDateTime lastSeen, LocalDateTime now) {
        if (lastSeen == null) return Optional.empty();
        long minutes = java.time.Duration.between(lastSeen, now).toMinutes();
        if (minutes >= DATA_GAP_MINUTES) {
            return Optional.of(new EvalResult("offline", "数据上报", 0,
                    "DATA_GAP", "CRITICAL",
                    String.format("设备数据中断 %d 分钟（上次上报 %s）", minutes,
                            lastSeen.format(DateTimeFormatter.ofPattern("HH:mm")))));
        }
        return Optional.empty();
    }

    /** 指标名中文化（日报汇总用）。 */
    public String labelOf(String metric) {
        return THRESHOLDS.stream().filter(t -> t.metric().equals(metric))
                .map(Threshold::label).findFirst().orElse(metric);
    }

    /** 汇总一批异常为一张卡片的摘要文本（飞书卡片用）。 */
    public String summarize(List<EvalResult> results) {
        if (results.isEmpty()) return "无异常";
        return String.join("；", results.stream().map(EvalResult::message).toList());
    }

    /** 调试输出。 */
    public Map<String, Object> toDebug(List<EvalResult> results) {
        Map<String, Object> map = new LinkedHashMap<>();
        results.forEach(r -> map.put(r.ruleCode(), r.message()));
        return map;
    }
}
