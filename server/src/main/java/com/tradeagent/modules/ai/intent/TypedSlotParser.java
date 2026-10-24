package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.dag.EvidencePlanValidator.Issue;
import com.tradeagent.core.dag.EvidenceRoutingPlan.SlotCandidate;
import com.tradeagent.core.dag.EvidenceRoutingPlan.SlotType;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Finite, deterministic parsing rules; call only after original-source structural validation. */
final class TypedSlotParser {
    enum Status { RESOLVED, INVALID, AMBIGUOUS, UNSUPPORTED }
    record Source(String rule, Integer start, Integer end, String sourceMessageId) {}
    record ResolvedSlot(String id, SlotType type, String evidence, Status status,
                        Object value, String unit, Source source, List<Issue> issues) {
        ResolvedSlot { issues = List.copyOf(issues); }
    }
    record Parsed(Object value, String unit, String rule) {}
    record PortMention(String value, String role) {}

    private static final String NUMBER = "(?:[0-9][0-9,]*(?:\\.[0-9]+)?[百千万]?|[零〇一二两三四五六七八九十百千万]+)";
    private static final String CHINESE_UNIT = "(?:片|块|套|件|台)";
    private static final String ENGLISH_UNIT = "(?:pieces?|pcs|sets?)";
    private static final String PURCHASE_UNIT = "(?:" + CHINESE_UNIT + "|" + ENGLISH_UNIT + ")";
    private static final Pattern QUANTITIES = Pattern.compile(
            "(?iu)(?<![a-z0-9.,+\\-~～至到零〇一二两三四五六七八九十百千万])(" + NUMBER + ")\\s*(" + CHINESE_UNIT + "|" + ENGLISH_UNIT + "(?![a-z0-9]))");
    private static final Pattern QUANTITY_MENTIONS = Pattern.compile(
            "(?iu)(?<![a-z0-9.,])([+\\-]?" + NUMBER + ")\\s*(" + CHINESE_UNIT + "|" + ENGLISH_UNIT + "(?![a-z0-9]))");
    private static final Pattern RANGE = Pattern.compile("(?iu)" + NUMBER + "\\s*(?:[-~～至到]|or|或)\\s*" + NUMBER + "\\s*" + PURCHASE_UNIT);
    // This is a small backend-maintained DEMO alias catalog, grounded in the two tool schema
    // model examples. There is no product/catalog database in the current project.
    private static final Map<String, String> MODELS = aliases(new String[][]{
            {"TOPCon-580W", "TOPCon580W", "TOPCon 580W", "TOPCon-580W组件", "TOPCon-580W光伏组件", "TOPCon-580W板"},
            {"PERC-550W", "PERC550W", "PERC 550W", "PERC-550W组件", "PERC-550W光伏组件", "PERC-550W板"}});
    private static final Map<String, String> PORTS = aliases(new String[][]{
            {"Abu Dhabi", "阿布扎比", "阿布扎比港"}, {"Dubai", "迪拜", "迪拜港"},
            {"Jebel Ali", "杰贝阿里", "杰贝阿里港", "迪拜杰贝阿里港", "迪拜杰贝阿里", "Dubai (Jebel Ali)", "迪拜Jebel Ali港", "迪拜Jebel Ali", "Jebel Ali港"},
            {"Hamburg", "汉堡", "汉堡港"},
            {"Rotterdam", "鹿特丹", "鹿特丹港"}, {"Singapore", "新加坡", "新加坡港"},
            {"Los Angeles", "洛杉矶", "洛杉矶港"}, {"Antwerp", "安特卫普", "安特卫普港"},
            {"Ningbo", "宁波", "宁波港"}, {"Shanghai", "上海", "上海港"}});
    private static final Map<String, String> COMPANIES = aliases(new String[][]{
            {"Al-Futtaim Solar Energy Solutions LLC", "Al-Futtaim Solar", "Al-Futtaim"},
            {"BayWa r.e. Solar Trade GmbH", "BayWa r.e.", "BayWa"}});
    private static final Map<String, String> CURRENCIES = aliases(new String[][]{
            {"USD", "美元", "美金", "US$"}, {"CNY", "人民币", "RMB", "CN¥"},
            {"EUR", "欧元", "€"}, {"AED", "迪拉姆", "阿联酋迪拉姆"},
            {"GBP", "英镑", "£"}, {"JPY", "日元"}, {"HKD", "港币", "港元"},
            {"CAD", "加元"}, {"AUD", "澳元"}, {"SGD", "新加坡元"}});
    private static final String CURRENCY_TOKEN = CURRENCIES.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote)
            .collect(java.util.stream.Collectors.joining("|")) + "|\\$|¥";
    private static final Pattern MONEY = Pattern.compile("(?iu)^(?:((?:" + CURRENCY_TOKEN + "))\\s*)?(" + NUMBER
            + ")(?:\\s*((?:" + CURRENCY_TOKEN + ")))?(?:\\s*/\\s*(w|瓦))?$");

    static String normalize(String source) { return Normalizer.normalize(source, Normalizer.Form.NFKC).trim(); }
    static boolean latestProvides(SlotType type, String parameter, String text, boolean blocked) {
        String normalized = normalize(text);
        boolean correction = normalized.matches("(?is).*(?:改为|改成|更正|不是|应为).*");
        if (correction && type == SlotType.PORT && !ports(normalized).isEmpty()) return true;
        if (correction && type == SlotType.CURRENCY && !mentions(normalized, CURRENCIES).isEmpty()) return true;
        if (correction && type == SlotType.MONEY && !mentions(normalized, CURRENCIES).isEmpty()
                && normalized.matches("(?s).*\\d+.*")) return true;
        return switch (type) {
            case QUANTITY -> QUANTITY_MENTIONS.matcher(normalized).find();
            case MODEL -> !modelSignatures(normalized).isEmpty();
            case PORT -> ports(normalized).stream().anyMatch(port -> port.role().equals(parameter)
                    || (blocked && port.role().equals("unknown")));
            case CURRENCY -> currencyDirection(normalized) != null;
            case HS_CODE -> normalized.matches("(?is).*(?:HS|海关编码|税号).*\\d{4}.*");
            case COMPANY -> !mentions(normalized, COMPANIES).isEmpty();
            case MONEY -> MONEY.matcher(normalized).matches();
            case POWER -> normalized.matches("(?is).*\\d+\\s*(?:W|瓦).*");
            case RESULT_LIMIT -> normalized.matches("(?is).*(?:top|前|返回)\\s*\\d+.*");
            case DOCUMENT_ID, CONTAINER_COUNT, TEXT -> blocked;
        };
    }
    private static String key(String source) { return normalize(source).toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    static ResolvedSlot parse(String original, SlotCandidate slot) {
        String text = normalize(slot.evidence());
        try {
            Parsed parsed = switch (slot.type()) {
                case QUANTITY -> quantity(text);
                case MODEL -> dictionary(text, MODELS, "BACKEND_DEMO_MODEL_ALIASES", "UNKNOWN_MODEL");
                case PORT -> dictionary(text, PORTS, "BACKEND_PORT_ALIASES", "UNKNOWN_PORT");
                case COMPANY -> dictionary(text, COMPANIES, "BACKEND_DEMO_COMPANY_ALIASES", "UNKNOWN_COMPANY");
                case CURRENCY -> currency(text);
                case MONEY -> money(text);
                case HS_CODE -> hsCode(normalize(original), text);
                case POWER -> power(text);
                case RESULT_LIMIT -> limit(text);
                case DOCUMENT_ID -> throw new ParseFailure(Status.UNSUPPORTED, "DOCUMENT_ID_REQUIRES_CATALOG");
                case CONTAINER_COUNT -> containers(text);
                case TEXT -> new Parsed(slot.evidence(), null, "VERBATIM_TEXT");
            };
            if (slot.type() == SlotType.QUANTITY && !completeSourceToken(original, slot, QUANTITY_MENTIONS)) {
                throw invalid("QUANTITY_SOURCE_TOKEN_BOUNDARY");
            }
            if (slot.type() == SlotType.POWER && !completeSourceToken(original, slot,
                    Pattern.compile("(?iu)(?<![a-z0-9.,])[-+]?\\d+(?:\\.\\d+)?\\s*(?:w|瓦)(?![a-z0-9])"))) {
                throw invalid("POWER_SOURCE_TOKEN_BOUNDARY");
            }
            if (slot.type() == SlotType.RESULT_LIMIT && !Pattern.compile(
                    "(?iu)(?:top\\s*" + Pattern.quote(text) + "|(?:前|返回|最多)\\s*" + NUMBER + "\\s*(?:条|个结果|results?))")
                    .matcher(normalize(original)).find()) {
                throw invalid("RESULT_LIMIT_REQUIRES_CONTEXT");
            }
            if (slot.type() == SlotType.QUANTITY && quantitySignatures(normalize(original)).size() > 1) {
                throw new ParseFailure(Status.AMBIGUOUS, "COMPETING_QUANTITIES_IN_QUERY");
            }
            if (slot.type() == SlotType.QUANTITY && RANGE.matcher(normalize(original)).find()) {
                throw new ParseFailure(Status.AMBIGUOUS, "QUANTITY_RANGE_IN_QUERY");
            }
            if (slot.type() == SlotType.MODEL && modelSignatures(normalize(original)).size() > 1) {
                throw new ParseFailure(Status.AMBIGUOUS, "COMPETING_MODELS_IN_QUERY");
            }
            if (slot.type() == SlotType.COMPANY && mentions(normalize(original), COMPANIES).size() > 1) {
                throw new ParseFailure(Status.AMBIGUOUS, "COMPETING_COMPANIES_IN_QUERY");
            }
            return resolved(slot, parsed);
        } catch (ParseFailure failure) {
            return new ResolvedSlot(slot.id(), slot.type(), slot.evidence(), failure.status, null, null,
                    new Source("DETERMINISTIC_RULES", slot.start(), slot.end(), slot.sourceMessageId()),
                    List.of(new Issue(failure.code, "slotCandidates." + slot.id())));
        }
    }

    private static ResolvedSlot resolved(SlotCandidate slot, Parsed parsed) {
        return new ResolvedSlot(slot.id(), slot.type(), slot.evidence(), Status.RESOLVED,
                parsed.value(), parsed.unit(), new Source(parsed.rule(), slot.start(), slot.end(), slot.sourceMessageId()), List.of());
    }

    private static Parsed quantity(String text) {
        if (RANGE.matcher(text).find()) throw ambiguous("AMBIGUOUS_QUANTITY");
        var matcher = QUANTITY_MENTIONS.matcher(text);
        List<Parsed> found = new ArrayList<>();
        while (matcher.find()) found.add(new Parsed(positiveInteger(matcher.group(1)),
                matcher.group(2).toLowerCase(Locale.ROOT), "EXPLICIT_PURCHASE_UNIT"));
        if (found.isEmpty()) throw invalid("QUANTITY_REQUIRES_PURCHASE_UNIT");
        if (found.size() > 1) throw ambiguous("AMBIGUOUS_QUANTITY");
        // Restrict accepted evidence to the amount/unit plus an optional explicit label.
        String remainder = QUANTITIES.matcher(text).replaceAll("").trim();
        if (!remainder.matches("(?iu)(?:采购|购买|订购|数量|采购数量|purchase|order|quantity)?\\s*[:：]?")) {
            throw invalid("INVALID_QUANTITY_EXPRESSION");
        }
        return found.get(0);
    }

    private static Set<String> quantitySignatures(String text) {
        Set<String> signatures = new LinkedHashSet<>();
        var matcher = QUANTITY_MENTIONS.matcher(text);
        while (matcher.find()) {
            try { signatures.add(positiveInteger(matcher.group(1)) + ":" + matcher.group(2).toLowerCase(Locale.ROOT)); }
            catch (ParseFailure invalid) { signatures.add("INVALID:" + matcher.group()); }
        }
        return signatures;
    }

    private static Set<String> modelSignatures(String text) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = Pattern.compile("(?iu)(?<![a-z0-9])(?:[a-z][a-z0-9]*[- ]?)?\\d{3,4}\\s*w(?![a-z0-9])").matcher(text);
        while (matcher.find()) result.add(MODELS.getOrDefault(key(matcher.group()), key(matcher.group())));
        // Repeating a catalog model's own power is not a second product model.
        for (String model : new ArrayList<>(result)) {
            if (MODELS.containsValue(model)) {
                String watts = model.substring(model.lastIndexOf('-') + 1).toLowerCase(Locale.ROOT);
                result.remove(watts);
            }
        }
        return result;
    }

    private static boolean completeSourceToken(String original, SlotCandidate slot, Pattern tokens) {
        List<Integer> starts = new ArrayList<>();
        if (slot.start() != null) starts.add(slot.start());
        else for (int at = original.indexOf(slot.evidence()); at >= 0;
                  at = original.indexOf(slot.evidence(), at + 1)) starts.add(at);
        String normalized = Normalizer.normalize(original, Normalizer.Form.NFKC);
        for (int start : starts) {
            int begin = Normalizer.normalize(original.substring(0, start), Normalizer.Form.NFKC).length();
            int end = begin + Normalizer.normalize(slot.evidence(), Normalizer.Form.NFKC).length();
            boolean complete = false;
            var matcher = tokens.matcher(normalized);
            while (matcher.find()) {
                if (matcher.start() >= begin && matcher.end() <= end) complete = true;
            }
            if (!complete) return false;
        }
        return !starts.isEmpty();
    }

    private static Parsed dictionary(String text, Map<String, String> dictionary, String rule, String unknown) {
        String value = dictionary.get(key(text));
        if (value != null) return new Parsed(value, null, rule);
        Set<String> unique = mentions(text, dictionary);
        if (unique.size() == 1) return new Parsed(unique.iterator().next(), null, rule);
        if (unique.size() > 1) throw ambiguous("AMBIGUOUS_" + unknown.substring(8));
        throw new ParseFailure(Status.UNSUPPORTED, unknown);
    }

    private static Parsed currency(String text) {
        if (text.equals("$") || text.equals("¥")) throw ambiguous("AMBIGUOUS_CURRENCY_SYMBOL");
        return dictionary(text, CURRENCIES, "EXPLICIT_CURRENCY_ALIAS", "UNKNOWN_CURRENCY");
    }

    private static Parsed money(String text) {
        var matcher = MONEY.matcher(text);
        if (!matcher.matches()) throw invalid("INVALID_MONEY_EXPRESSION");
        String first = matcher.group(1), second = matcher.group(3);
        if (first == null && second == null) throw invalid("MONEY_REQUIRES_CURRENCY");
        String currency = (String) currency(first != null ? first : second).value();
        if (first != null && second != null && !currency.equals(currency(second).value())) {
            throw ambiguous("CONFLICTING_MONEY_CURRENCIES");
        }
        BigDecimal amount = number(matcher.group(2));
        if (amount.signum() <= 0) throw invalid("MONEY_MUST_BE_POSITIVE");
        return new Parsed(amount, currency + (matcher.group(4) == null ? "" : "/W"), "EXPLICIT_CURRENCY_AMOUNT");
    }

    private static Parsed hsCode(String original, String text) {
        String code = text.replaceFirst("(?iu)^(?:hs(?:\\s*code)?|海关编码|商品编码|税号)\\s*[:：]?\\s*", "");
        if (!code.matches("(?:\\d{6}|\\d{8}|\\d{10}|\\d{4}\\.\\d{2}|\\d{4}\\.\\d{4}|\\d{4}\\.\\d{2}\\.\\d{2,4})")) {
            throw invalid("INVALID_HS_CODE_FORMAT");
        }
        var context = Pattern.compile("(?iu)(?:hs(?:\\s*code)?|海关编码|商品编码|税号)\\s*[:：]?\\s*(\\d[\\d.]*)").matcher(original);
        Set<String> codes = new LinkedHashSet<>();
        while (context.find()) codes.add(context.group(1).replace(".", ""));
        if (codes.size() > 1) throw ambiguous("COMPETING_HS_CODES_IN_QUERY");
        if (!codes.contains(code.replace(".", ""))) throw invalid("HS_CODE_REQUIRES_CONTEXT");
        return new Parsed(code.replace(".", ""), null, "HS_LABEL_AND_FORMAT");
    }

    private static Parsed power(String text) {
        var matcher = Pattern.compile("(?iu)^(\\d+)\\s*(?:w|瓦)$").matcher(text);
        if (!matcher.matches()) throw invalid("POWER_REQUIRES_WATTS");
        return new Parsed(positiveInteger(matcher.group(1)), "W", "EXPLICIT_WATTS");
    }

    private static Parsed limit(String text) {
        var matcher = Pattern.compile("(?iu)^(?:(?:top|前|返回|最多)\\s*)?(" + NUMBER + ")\\s*(?:条|个结果|results?)?$").matcher(text);
        if (!matcher.matches()) throw invalid("INVALID_RESULT_LIMIT");
        int count = positiveInteger(matcher.group(1));
        if (count > 50) throw invalid("RESULT_LIMIT_OUT_OF_PREVIEW_RANGE");
        return new Parsed(count, "results", "RESULT_LIMIT_1_TO_50");
    }

    private static Parsed containers(String text) {
        var matcher = Pattern.compile("(?iu)^(" + NUMBER + ")\\s*(?:个|只|柜)?\\s*(40hq|20gp)(?:柜)?$").matcher(text);
        if (!matcher.matches()) throw invalid("CONTAINER_REQUIRES_EXPLICIT_TYPE");
        return new Parsed(positiveInteger(matcher.group(1)), matcher.group(2).toUpperCase(Locale.ROOT), "EXPLICIT_CONTAINER_COUNT");
    }

    static List<PortMention> ports(String original) {
        String text = key(original);
        List<PortMention> result = new ArrayList<>();
        var matcher = dictionaryPattern(PORTS).matcher(text);
        while (matcher.find()) {
            String before = text.substring(Math.max(0, matcher.start() - 30), matcher.start());
            String role = before.matches("(?iu).*(?:从|自|起运港?[:：]?|from)\\s*$") ? "originPort"
                    : before.matches("(?iu).*(?:到|至|发往|运往|目的港?[:：]?|to)\\s*$") ? "destPort" : "unknown";
            result.add(new PortMention(PORTS.get(key(matcher.group())), role));
        }
        return result;
    }

    /** Only explicit pair/direction syntax is supported; unspecified direction stays unresolved. */
    static String currencyDirection(String original) {
        var matcher = Pattern.compile("(?iu)(?:from\\s+)?(" + CURRENCY_TOKEN + ")\\s*"
                + "(?:兑换(?:成|为)?|换(?:成|为)?|兑|转(?:成|为)?|对|to|->|→|/)\\s*(" + CURRENCY_TOKEN + ")")
                .matcher(normalize(original));
        Set<String> pairs = new LinkedHashSet<>();
        while (matcher.find()) {
            String base = CURRENCIES.get(key(matcher.group(1))), target = CURRENCIES.get(key(matcher.group(2)));
            if (base == null || target == null) return null;
            pairs.add(base + "/" + target);
        }
        return pairs.size() == 1 ? pairs.iterator().next() : null;
    }

    private static Set<String> mentions(String text, Map<String, String> dictionary) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = dictionaryPattern(dictionary).matcher(key(text));
        while (matcher.find()) result.add(dictionary.get(key(matcher.group())));
        return result;
    }

    private static Pattern dictionaryPattern(Map<String, String> dictionary) {
        return Pattern.compile("(?iu)(?<![a-z0-9])(?:" + dictionary.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|")) + ")(?![a-z0-9])");
    }

    private static Map<String, String> aliases(String[][] groups) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String[] group : groups) for (String alias : group) result.put(key(alias), group[0]);
        return Collections.unmodifiableMap(result);
    }

    private static int positiveInteger(String source) {
        try {
            int value = number(source).intValueExact();
            if (value <= 0) throw invalid("INTEGER_MUST_BE_POSITIVE");
            return value;
        } catch (ArithmeticException e) { throw invalid("INVALID_INTEGER"); }
    }

    private static BigDecimal number(String source) {
        if (source.matches("[零〇一二两三四五六七八九十百千万]+")) {
            String standard = source.replace('两', '二').replace('〇', '零');
            int value = chineseValue(standard);
            if (!chineseText(value).equals(standard)) throw invalid("AMBIGUOUS_CHINESE_INTEGER");
            return BigDecimal.valueOf(value);
        }
        var matcher = Pattern.compile("^((?:0|[1-9]\\d*|[1-9]\\d{0,2}(?:,\\d{3})+)(?:\\.\\d+)?)([百千万]?)$").matcher(source);
        if (!matcher.matches()) throw invalid("INVALID_NUMBER_FORMAT");
        BigDecimal value = new BigDecimal(matcher.group(1).replace(",", ""));
        int factor = switch (matcher.group(2)) { case "百" -> 100; case "千" -> 1000; case "万" -> 10000; default -> 1; };
        return value.multiply(BigDecimal.valueOf(factor));
    }

    private static int chineseValue(String source) {
        long total = 0, section = 0, digit = 0;
        for (char ch : source.toCharArray()) {
            int d = "零一二三四五六七八九".indexOf(ch);
            if (d >= 0) { digit = d; continue; }
            int unit = switch (ch) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; case '万' -> 10000; default -> 0; };
            if (unit == 10000) { total += (section + digit) * unit; section = 0; }
            else section += (digit == 0 ? 1 : digit) * unit;
            digit = 0;
            if (total > 99_999_999) throw invalid("INVALID_INTEGER");
        }
        return (int) (total + section + digit);
    }

    private static String chineseText(int value) {
        if (value == 0) return "零";
        if (value >= 10000) return chineseText(value / 10000) + "万"
                + (value % 10000 == 0 ? "" : (value % 10000 < 1000 ? "零" : "") + chineseText(value % 10000));
        StringBuilder result = new StringBuilder();
        int[] places = {1000, 100, 10, 1};
        String[] units = {"千", "百", "十", ""};
        boolean zero = false;
        for (int i = 0; i < places.length; i++) {
            int digit = value / places[i]; value %= places[i];
            if (digit == 0) { if (!result.isEmpty() && value > 0) zero = true; continue; }
            if (zero) result.append('零'); zero = false;
            if (!(places[i] == 10 && digit == 1 && result.isEmpty())) result.append("零一二三四五六七八九".charAt(digit));
            result.append(units[i]);
        }
        return result.toString();
    }

    private static ParseFailure invalid(String code) { return new ParseFailure(Status.INVALID, code); }
    private static ParseFailure ambiguous(String code) { return new ParseFailure(Status.AMBIGUOUS, code); }
    private static final class ParseFailure extends RuntimeException {
        final Status status; final String code;
        ParseFailure(Status status, String code) { this.status = status; this.code = code; }
    }
}
