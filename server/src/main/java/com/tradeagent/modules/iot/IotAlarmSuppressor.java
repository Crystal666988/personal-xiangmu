package com.tradeagent.modules.iot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警抑制器：解决"中午温度超标 30 秒就告警"的误报轰炸。
 * 规则：
 *   1) 持续确认：同 (设备,规则) 需连续 N=3 个上报周期越界才升级告警（首次记录但不推送）；
 *   2) 静默期：告警后同键 10 分钟内不再重复推送（恢复期抖动不刷屏）；
 *   3) CRITICAL 直通：数据中断类(DATA_GAP)不受持续确认约束，立即告警。
 */
@Slf4j
@Service
public class IotAlarmSuppressor {

    private static final int CONFIRM_ROUNDS = 3;
    private static final Duration SILENCE = Duration.ofMinutes(10);

    private record State(int consecutive, LocalDateTime lastAlertAt) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public enum Decision { RECORD_ONLY, ALERT }

    /** 越界时调用：返回该事件是否值得推送飞书（事件本身仍会落库留痕）。 */
    public Decision onBreach(String deviceId, String ruleCode, boolean criticalImmediate) {
        String key = deviceId + ":" + ruleCode;
        LocalDateTime now = LocalDateTime.now();
        State st = states.get(key);
        int consecutive = (st == null ? 0 : st.consecutive()) + 1;

        if (criticalImmediate) {           // DATA_GAP 等致命类直接告警
            states.put(key, new State(consecutive, now));
            return Decision.ALERT;
        }
        if (st != null && st.lastAlertAt() != null
                && Duration.between(st.lastAlertAt(), now).compareTo(SILENCE) < 0) {
            states.put(key, new State(consecutive, st.lastAlertAt()));  // 静默期内
            return Decision.RECORD_ONLY;
        }
        if (consecutive >= CONFIRM_ROUNDS) {  // 连续 3 轮确认
            states.put(key, new State(consecutive, now));
            return Decision.ALERT;
        }
        states.put(key, new State(consecutive, null));
        return Decision.RECORD_ONLY;
    }

    /** 指标恢复正常时调用：清零连续计数。 */
    public void onNormal(String deviceId, String metric) {
        states.keySet().removeIf(k -> k.startsWith(deviceId + ":" ) && k.endsWith("_" + metric.toUpperCase())
                || k.equals(deviceId + ":" + metric.toUpperCase() + "_HIGH")
                || k.equals(deviceId + ":" + metric.toUpperCase() + "_LOW"));
    }
}
