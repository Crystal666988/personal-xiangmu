package com.tradeagent.core.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.core.dag.model.*;
import com.tradeagent.modules.ai.intent.RedisRoutingWorkflowStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only audit events; a replay of the same event ID is idempotent. Apply the SQL migration first. */
@Repository
public class JdbcSagaAuditStore implements SagaAuditStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcSagaAuditStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    @Override public void record(AgentExecutionCheckpoint cp, ExecutedStepSnapshot step, String event) {
        try {
            String id = RedisRoutingWorkflowStore.hash(cp.getCheckpointId() + ":" + step.getStepId() + ":" + step.getCompensationAttempts() + ":" + event);
            jdbc.update("""
                INSERT INTO saga_compensation_log
                (id, tenant_id, session_id, task_id, checkpoint_id, step_id, tool_id, idempotency_key,
                 event_type, attempt, latency_ms, error_code, original_params, forward_result, compensation_result)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, id, cp.getTenantId(), cp.getSessionId(), cp.getTaskId(), cp.getCheckpointId(), step.getStepId(),
                    step.getToolId(), step.getCompensationKey(), event, step.getCompensationAttempts(), step.getLastCompensationLatencyMs(),
                    step.getCompensationErrorCode(), json.writeValueAsString(step.getOriginalParams()),
                    json.writeValueAsString(step.getForwardResult()), json.writeValueAsString(step.getCompensationResult()));
        } catch (Exception e) { throw new IllegalStateException("Saga 审计记录写入失败", e); }
    }
}
