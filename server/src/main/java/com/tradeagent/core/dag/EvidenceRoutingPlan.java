package com.tradeagent.core.dag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Untrusted semantic annotations, never executable tool arguments.
 * Evidence is copied verbatim from the original query, before any normalization or parsing.
 * Empty task/candidate lists can represent general conversation; null lists are malformed.
 * Missing parameters are omitted or bound to null, never filled with guessed/default values.
 * Snapshot copies preserve malformed/null input so the validator can report it.
 */
public record EvidenceRoutingPlan(List<Task> tasks, List<SlotCandidate> slotCandidates) {
    public EvidenceRoutingPlan {
        if (tasks != null) tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
        if (slotCandidates != null) {
            slotCandidates = Collections.unmodifiableList(new ArrayList<>(slotCandidates));
        }
    }

    /** Slots map exact backend parameter names to candidate IDs, or null for missing. */
    public record Task(String tool, Map<String, String> slots) {
        public Task {
            if (slots != null) slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        }
    }

    /**
     * The type is a model claim to be checked against backend policy, not a parsed value.
     * Optional offsets must be supplied together: zero-based UTF-16 [start, end) indices
     * into the original, unmodified query (Java String indices).
     */
    public record SlotCandidate(String id, SlotType type, String evidence,
                                Integer start, Integer end, String sourceMessageId) {
        public SlotCandidate(String id, SlotType type, String evidence, Integer start, Integer end) {
            this(id, type, evidence, start, end, null);
        }
    }

    public enum SlotType {
        MODEL, QUANTITY, HS_CODE, PORT, CURRENCY, MONEY, CONTAINER_COUNT, COMPANY, TEXT,
        POWER, RESULT_LIMIT, DOCUMENT_ID
    }
}
