package com.tradeagent.core.dag.model;

import lombok.Data;

@Data
public class AgentExecutionCheckpoint {
    private String checkpointId;
    private String tenantId;
    private String sessionId;
    private String taskId;
    private long version;
    private long createdAt;
    private long updatedAt;
    private DagExecutionState state;
}
