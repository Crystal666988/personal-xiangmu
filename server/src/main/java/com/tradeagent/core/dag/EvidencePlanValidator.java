package com.tradeagent.core.dag;

import java.util.*;
import static com.tradeagent.core.dag.EvidenceRoutingPlan.*;

/**
 * Pure structural validation of untrusted annotations against trusted backend policy.
 * Does not normalize evidence, parse values, resolve semantic conflicts, or execute tools.
 * Even a valid result (including an empty plan) is never execution authorization: typed
 * parsing, ambiguity/required-value checks and business validation must follow separately.
 */
public final class EvidencePlanValidator {
    private EvidencePlanValidator() {}

    /**
     * The caller constructs these from registered tool schemas and explicit backend-owned
     * semantic mappings. Neither the allowlist nor parameter types/requiredness may come
     * from model output. A tool's JSON primitive type alone cannot infer semantic types.
     */
    public record ToolPolicy(Map<String, SlotType> parameters, Set<String> required) {
        public ToolPolicy {
            parameters = Map.copyOf(parameters);
            required = Set.copyOf(required);
            if (parameters.keySet().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Parameter names must not be blank");
            }
            if (!parameters.keySet().containsAll(required)) {
                throw new IllegalArgumentException("Required parameter has no type");
            }
        }
    }

    public record Issue(String code, String location) {}
    public record Validation(List<Issue> issues) {
        public Validation { issues = List.copyOf(issues); }
        /** True means structurally consistent only, never safe/ready to execute. */
        public boolean valid() { return issues.isEmpty(); }
    }

    public static Validation validate(String originalQuery, EvidenceRoutingPlan plan,
                                      Map<String, ToolPolicy> allowedTools) {
        return validateInternal(originalQuery, null, plan, allowedTools);
    }

    /** Chat annotations must identify one persisted user message; offsets are relative to it. */
    public static Validation validateSources(Map<String, String> sources, EvidenceRoutingPlan plan,
                                             Map<String, ToolPolicy> allowedTools) {
        Objects.requireNonNull(sources, "User sources are required");
        return validateInternal("", Map.copyOf(sources), plan, allowedTools);
    }

    private static Validation validateInternal(String originalQuery, Map<String, String> sources,
                                               EvidenceRoutingPlan plan, Map<String, ToolPolicy> allowedTools) {
        // Invalid trusted configuration is a programmer error, not an LLM validation issue.
        Map<String, ToolPolicy> policies = Map.copyOf(
                Objects.requireNonNull(allowedTools, "Backend tool policies are required"));
        if (policies.keySet().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Tool names must not be blank");
        }
        List<Issue> issues = new ArrayList<>();
        if (originalQuery == null || plan == null || plan.tasks() == null || plan.slotCandidates() == null) {
            return new Validation(List.of(new Issue("INVALID_PLAN", "plan")));
        }
        Map<String, SlotCandidate> candidates = new HashMap<>();
        for (int i = 0; i < plan.slotCandidates().size(); i++) {
            SlotCandidate slot = plan.slotCandidates().get(i);
            String location = "slotCandidates[" + i + "]";
            if (slot == null || slot.id() == null || slot.id().isBlank()) {
                issues.add(new Issue("INVALID_SLOT", location));
                continue;
            }
            if (candidates.putIfAbsent(slot.id(), slot) != null) {
                issues.add(new Issue("DUPLICATE_SLOT", location));
            }
            if (slot.type() == null) issues.add(new Issue("MISSING_TYPE", location));
            String source = sources == null ? originalQuery : slot.sourceMessageId() == null
                    ? null : sources.get(slot.sourceMessageId());
            if (source == null) {
                issues.add(new Issue("UNKNOWN_USER_SOURCE", location));
                continue;
            }
            if (slot.evidence() == null || slot.evidence().isBlank()
                    || !source.contains(slot.evidence())) {
                issues.add(new Issue("EVIDENCE_NOT_IN_QUERY", location));
            }
            // Offsets are optional, but if supplied both must identify exactly the evidence.
            if (slot.start() != null || slot.end() != null) {
                if (slot.start() == null || slot.end() == null || slot.start() < 0
                        || slot.end() <= slot.start() || slot.end() > source.length()
                        || !source.substring(slot.start(), slot.end()).equals(slot.evidence())) {
                    issues.add(new Issue("INVALID_SPAN", location));
                }
            }
        }
        for (int i = 0; i < plan.tasks().size(); i++) {
            Task task = plan.tasks().get(i);
            String location = "tasks[" + i + "]";
            ToolPolicy policy = task == null || task.tool() == null ? null : policies.get(task.tool());
            if (policy == null) {
                issues.add(new Issue("UNKNOWN_TOOL", location));
                continue;
            }
            if (task.slots() == null) {
                issues.add(new Issue("INVALID_BINDINGS", location));
                continue;
            }
            for (String required : new TreeSet<>(policy.required())) {
                if (!task.slots().containsKey(required) || task.slots().get(required) == null) {
                    issues.add(new Issue("MISSING_REQUIRED_SLOT", location + ".slots." + required));
                }
            }
            for (var binding : task.slots().entrySet()) {
                String path = location + ".slots." + binding.getKey();
                SlotType expected = binding.getKey() == null ? null : policy.parameters().get(binding.getKey());
                if (expected == null) issues.add(new Issue("UNKNOWN_PARAMETER", path));
                else if (binding.getValue() != null) {
                    SlotCandidate candidate = candidates.get(binding.getValue());
                    if (candidate == null) {
                        for (SlotCandidate c : candidates.values()) {
                            if (Objects.equals(c.evidence(), binding.getValue())) {
                                candidate = c;
                                break;
                            }
                        }
                    }
                    if (candidate == null && expected == SlotType.TEXT && binding.getValue() != null && !binding.getValue().isBlank()) {
                        candidate = new SlotCandidate(binding.getValue(), SlotType.TEXT, binding.getValue(), null, null, null);
                    }
                    if (candidate == null) issues.add(new Issue("UNKNOWN_SLOT_REFERENCE", path));
                    else if (expected != candidate.type()) issues.add(new Issue("SLOT_TYPE_MISMATCH", path));
                }
            }
        }
        return new Validation(issues);
    }
}
