package com.tradeagent.core.dag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路由参数抽取器（规则评测基线口径）。
 *
 * 生产链路的参数解析使用 TypedSlotParser（LLM 证据片段 + 确定性解析 + 规范化词典）；
 * 本类仅服务于规则路由评测基线（RoutingPlanner），保持与评测期望值一致的抽取口径。
 */
public final class RoutingEntityExtractor {

    private static final Pattern QUANTITY_PATTERN =
            Pattern.compile("(\\d{1,7})\\s*(套|片|个|块|台|pcs|sets)?");

    private static final Pattern PORT_PATTERN =
            Pattern.compile("(?i)(hamburg|dubai|jebel ali|rotterdam|singapore|los angeles|汉堡|迪拜|杰贝阿里|鹿特丹|新加坡|洛杉矶)");

    private static final Pattern MODEL_PATTERN =
            Pattern.compile("(?i)(topcon[-\\s]?580w?|perc[-\\s]?550w?|580w|550w|540w|hjt[-\\s]?700w?)");

    private RoutingEntityExtractor() {
    }

    public static Map<String, Object> extract(String query) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }

        Matcher qm = QUANTITY_PATTERN.matcher(query);
        while (qm.find()) {
            try {
                int q = Integer.parseInt(qm.group(1));
                if (q >= 50 && q <= 500000) {
                    map.put("quantity", q);
                    break;
                }
            } catch (NumberFormatException ignored) {
                // 忽略超长数字，继续向后寻找合法数量
            }
        }

        Matcher pm = PORT_PATTERN.matcher(query);
        if (pm.find()) {
            String p = pm.group(1);
            if (p.equalsIgnoreCase("hamburg") || p.contains("汉堡")) {
                map.put("destPort", "Hamburg, Germany");
            } else if (p.equalsIgnoreCase("dubai") || p.contains("迪拜") || p.contains("杰贝阿里")) {
                map.put("destPort", "Dubai (Jebel Ali)");
            } else if (p.equalsIgnoreCase("rotterdam") || p.contains("鹿特丹")) {
                map.put("destPort", "Rotterdam, Netherlands");
            } else if (p.equalsIgnoreCase("singapore") || p.contains("新加坡")) {
                map.put("destPort", "Singapore Port");
            } else if (p.equalsIgnoreCase("los angeles") || p.contains("洛杉矶")) {
                map.put("destPort", "Los Angeles, USA");
            }
        }

        Matcher mm = MODEL_PATTERN.matcher(query);
        if (mm.find()) {
            map.put("model", mm.group(1).toUpperCase());
        }

        if (query.contains("Al-Futtaim") || query.contains("alfuttaim")) {
            map.put("companyName", "Al-Futtaim Solar Energy Solutions LLC");
        } else if (query.contains("BayWa") || query.contains("baywa")) {
            map.put("companyName", "BayWa r.e. Solar Trade GmbH");
        }

        return map;
    }
}
