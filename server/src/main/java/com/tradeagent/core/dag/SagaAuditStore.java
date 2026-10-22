package com.tradeagent.core.dag;

import com.tradeagent.core.dag.model.AgentExecutionCheckpoint;
import com.tradeagent.core.dag.model.ExecutedStepSnapshot;

public interface SagaAuditStore {
    void record(AgentExecutionCheckpoint checkpoint, ExecutedStepSnapshot step, String event);
}
