package com.tradeagent.core.dag.model;

/** A registered tool's deterministic alias/catalogue mapping with its original source binding. */
public record ToolCorrectionCandidate(String parameter, Object value, String sourceMessageId, String sourceText) {}
