package com.tradeagent.modules.ai.intent;

import java.util.List;

/** Durable authority, never sourced from assistant messages or client routing metadata. */
public interface RoutingWorkflowStore {
    record SourceRef(String id, String contentHash) {}
    record State(String messageId, String phase, List<SourceRef> sources, RoutingPreviewService.Preview plan) {}
    String acquire(String tenant, String session);
    void release(String tenant, String session, String token);
    State read(String tenant, String session);
    RoutingWorkflowService.Result receipt(String tenant, String session, String messageId);
    boolean save(String tenant, String session, String token, State state);
    boolean complete(String tenant, String session, String token, String messageId,
                     RoutingWorkflowService.Result result, State state);
}
