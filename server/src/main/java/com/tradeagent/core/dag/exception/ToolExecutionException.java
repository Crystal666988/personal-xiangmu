package com.tradeagent.core.dag.exception;

/** Only NOT_APPLIED errors may enter automatic correction. Unknown write outcomes cannot be replayed. */
public class ToolExecutionException extends RuntimeException {
    public enum Category { RECOVERABLE, FATAL }
    public enum EffectOutcome { NOT_APPLIED, UNKNOWN }
    private final Category category;
    private final EffectOutcome effectOutcome;
    private final String code;
    public ToolExecutionException(String code, String safeMessage, Category category, EffectOutcome effectOutcome) {
        super(safeMessage);
        if (code == null || !code.matches("[A-Z0-9_]{1,80}")) throw new IllegalArgumentException("Invalid tool error code");
        this.code = code; this.category = java.util.Objects.requireNonNull(category);
        this.effectOutcome = java.util.Objects.requireNonNull(effectOutcome);
    }
    public String getCode() { return code; }
    public Category getCategory() { return category; }
    public EffectOutcome getEffectOutcome() { return effectOutcome; }
}
