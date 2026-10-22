package com.tradeagent.core.dag.exception;

/** Throw before side effects, after deterministic input validation fails. */
public final class RecoverableToolException extends ToolExecutionException {
    public RecoverableToolException(String code, String safeMessage) {
        super(code, safeMessage, Category.RECOVERABLE, EffectOutcome.NOT_APPLIED);
    }
}
