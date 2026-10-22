package com.tradeagent.modules.ai.intent;

import com.tradeagent.core.dag.ReActStep;
import com.tradeagent.core.dag.CheckpointedDagExecutor;
import com.tradeagent.core.dag.ExecutionCheckpointService;
import org.springframework.beans.factory.annotation.Autowired;
import com.tradeagent.modules.ai.chat.AgentMessage;
import com.tradeagent.modules.ai.tools.TurnTelemetry;
import org.springframework.stereotype.Service;

import java.util.*;

/** New chat route/pause/resume/execute workflow. Credentials never enter its durable state. */
@Service
public final class RoutingWorkflowService {
    private final RoutingPreviewService planner;
    private final ValidatedPlanExecutor executor;
    private final RoutingWorkflowStore store;
    private final CheckpointedDagExecutor durableExecutor;
    private final ExecutionCheckpointService checkpoints;
    public RoutingWorkflowService(RoutingPreviewService planner, ValidatedPlanExecutor executor, RoutingWorkflowStore store) {
        this(planner, executor, store, null, null);
    }
    @Autowired
    public RoutingWorkflowService(RoutingPreviewService planner, ValidatedPlanExecutor executor, RoutingWorkflowStore store,
                                  CheckpointedDagExecutor durableExecutor, ExecutionCheckpointService checkpoints) {
        this.planner = planner; this.executor = executor; this.store = store;
        this.durableExecutor = durableExecutor; this.checkpoints = checkpoints;
    }
    public record Result(String routingStatus, RoutingPreviewService.Preview routingPlan, List<String> clarifications,
                         boolean shouldGenerate, String assistantDirective, boolean pending,
                         List<RoutingPreviewService.UserSource> sourceMessages, TurnTelemetry telemetry, List<ReActStep> reactSteps,
                         CheckpointedDagExecutor.View execution) {}

    public Result run(String tenant, String session, AgentMessage current, List<AgentMessage> history,
                      RoutingModelConfig model, String action) {
        if (action == null) action = "continue";
        if (!Set.of("continue", "new", "cancel").contains(action)) return blocked("INVALID_ACTION", "请选择补充当前任务或发起新问题。", false);
        if (model != null && model.getTenantId() != null && !tenant.equals(model.getTenantId().toString())) {
            return blocked("TENANT_MISMATCH", "模型配置租户与当前会话不一致，请切换到该会话所属租户配置。", false);
        }
        String token = null;
        try {
            token = store.acquire(tenant, session);
            if (token == null) return blocked("BUSY", "当前会话任务正在处理，请等待结果，不要重复提交。", true);
            var receipt = store.receipt(tenant, session, current.getId());
            if (receipt != null) {
                boolean valid = receipt.sourceMessages().stream().allMatch(source -> history.stream().anyMatch(message ->
                        source.id().equals(message.getId()) && session.equals(message.getSessionId())
                                && "user".equals(message.getRole()) && Objects.equals(source.content(), message.getContent())));
                if (!valid || receipt.sourceMessages().stream().noneMatch(source -> source.id().equals(current.getId())
                        && Objects.equals(source.content(), current.getContent()))) {
                    return blocked("SOURCE_CHANGED", "原始用户消息已更改或删除，不能重放旧工具事实，请发起新问题。", true);
                }
                if (receipt.execution() != null && checkpoints != null) {
                    var cp = checkpoints.getCheckpoint(tenant, session, receipt.execution().taskId());
                    if (cp.isPresent()) return withExecution(receipt, durableExecutor.view(cp.get()));
                    if (receipt.pending()) return blocked("CHECKPOINT_EXPIRED", "执行存档已过期，请先核查旧任务结果。", true);
                }
                return receipt;
            }
            var state = store.read(tenant, session);
            if (state != null && checkpoints != null && Set.of("EXECUTING", "CHECKPOINTED").contains(state.phase())) {
                var cp = checkpoints.getCheckpoint(tenant, session, state.messageId());
                if (cp.isPresent()) {
                    var execution = durableExecutor.view(cp.get());
                    if (!Set.of("COMPLETED", "ROLLED_BACK", "REJECTED").contains(execution.status())) {
                        return withExecution(blocked(execution.status(), execution.message(), true), execution);
                    }
                    require(store.save(tenant, session, token, new RoutingWorkflowStore.State(state.messageId(), "DONE", state.sources(), state.plan())));
                    state = store.read(tenant, session);
                } else if ("CHECKPOINTED".equals(state.phase())) {
                    return blocked("CHECKPOINT_EXPIRED", "执行存档已过期或不可读取，请核查旧任务结果后再发起新任务。", true);
                }
            }
            if (state != null && "EXECUTING".equals(state.phase())) return blocked("EXECUTION_UNCERTAIN", "上一任务执行状态尚未确认，为避免重复调用已暂停，请先核查执行记录。", true);
            if ("cancel".equals(action)) return cancelLocked(tenant, session, token);
            if (state != null && "PLANNING".equals(state.phase())) return blocked("BUSY", "上一任务状态尚未确认，请取消未执行任务后重新提交。", true);
            List<RoutingPreviewService.UserSource> sources = new ArrayList<>();
            RoutingPreviewService.Preview previous = null;
            if ("continue".equals(action) && state != null && "WAITING".equals(state.phase())) {
                Map<String, AgentMessage> byId = new HashMap<>(); history.forEach(message -> byId.put(message.getId(), message));
                for (var ref : state.sources()) {
                    var message = byId.get(ref.id());
                    if (message == null || !"user".equals(message.getRole())
                            || !session.equals(message.getSessionId())
                            || !Objects.equals(ref.contentHash(), RedisRoutingWorkflowStore.hash(Objects.toString(message.getContent(), "")))) {
                        return blocked("SOURCE_CHANGED", "待处理任务的原始用户消息已删除或更改，请选择新问题重新发起。", true);
                    }
                    sources.add(new RoutingPreviewService.UserSource(message.getId(), message.getContent()));
                }
                previous = state.plan();
            }
            if (sources.stream().noneMatch(source -> source.id().equals(current.getId()))) {
                sources.add(new RoutingPreviewService.UserSource(current.getId(), Objects.toString(current.getContent(), "")));
            }
            if (sources.size() > 20 || sources.stream().mapToInt(source -> source.content().length()).sum() > RoutingPreviewService.MAX_QUERY_LENGTH) {
                return blocked("SOURCE_LIMIT", "当前任务原文过长，请选择新问题并提供完整且精简的需求。", true);
            }
            List<RoutingWorkflowStore.SourceRef> refs = sources.stream().map(source -> new RoutingWorkflowStore.SourceRef(
                    source.id(), RedisRoutingWorkflowStore.hash(source.content()))).toList();
            require(store.save(tenant, session, token, new RoutingWorkflowStore.State(current.getId(), "PLANNING", refs, previous)));
            var plan = planner.previewSources(sources, previous, model);
            Result result;
            if (plan.status() == RoutingPreviewService.Status.STRUCTURALLY_VALID && plan.plan() != null && plan.plan().tasks().isEmpty()
                    && plan.issues().isEmpty()) {
                result = result("GENERAL", plan, List.of(), true, "本轮没有工具任务，直接回答用户；不得声称调用过任何业务工具。", false, sources,
                        ValidatedPlanExecutor.emptyTelemetry(), List.of());
            } else if (!plan.parametersValid()) {
                List<String> questions = plan.clarifications().isEmpty() ? List.of(switch (plan.status()) {
                    case MODEL_UNAVAILABLE -> "模型暂不可用或未配置，请检查当前租户的模型设置后补充或重试。";
                    case REJECTED -> "本轮任务或证据校验未通过，请明确需求后继续；尚未执行任何工具。";
                    default -> "任务校验未完成，请补充明确的信息；尚未执行任何工具。";
                }) : plan.clarifications();
                result = result(plan.status().name(), plan, questions, false, String.join("\n", questions), true, sources,
                        ValidatedPlanExecutor.emptyTelemetry(), List.of());
            } else {
                var prepared = executor.preflight(plan, sources);
                if (!prepared.ready()) result = result("NEEDS_CLARIFICATION", plan, prepared.clarifications(), false,
                        String.join("\n", prepared.clarifications()), true, sources, ValidatedPlanExecutor.emptyTelemetry(), List.of());
                else {
                    // This durable fence is written before the FIRST actual handler call. A crash or
                    // failed receipt write leaves EXECUTING, so no subsequent request can repeat it.
                    require(store.save(tenant, session, token, new RoutingWorkflowStore.State(current.getId(), "EXECUTING", refs, plan)));
                    if (durableExecutor != null) {
                        var execution = durableExecutor.start(tenant, session, current.getId(),
                                String.join("\n", sources.stream().map(RoutingPreviewService.UserSource::content).toList()), plan, prepared, sources, model);
                        result = withExecution(result("EXECUTING", plan, List.of(), false, "执行中", true, sources,
                                execution.telemetry(), execution.reactSteps()), execution);
                    } else {
                    var execution = executor.execute(prepared);
                    result = result(execution.success() ? "EXECUTED" : "EXECUTION_FAILED", plan, List.of(), execution.success(),
                            execution.success() ? "依据已执行工具结果回答原始用户任务。标为DEMO的数据只是静态演示，必须注明，不能称为实时或已核验数据。不得补造任何价格、运价、汇率或海关记录。"
                                    : "工具执行失败，后续任务已停止。请核查失败原因；已完成的调用不会自动重复。", false,
                            sources, execution.telemetry(), execution.steps());
                    }
                }
            }
            var savedPlan = result.routingPlan() != null && result.routingPlan().plan() != null ? result.routingPlan() : previous;
            require(store.complete(tenant, session, token, current.getId(), result,
                    new RoutingWorkflowStore.State(current.getId(), result.execution() != null && result.pending() ? "CHECKPOINTED"
                            : result.pending() ? "WAITING" : "DONE", refs, savedPlan)));
            return result;
        } catch (RuntimeException e) {
            e.printStackTrace();
            return blocked("STATE_UNAVAILABLE", "任务状态无法安全保存，已停止处理。若此前开始执行，请先核查结果，避免重复调用。", true);
        } finally {
            if (token != null) try { store.release(tenant, session, token); } catch (RuntimeException ignored) {}
        }
    }

    public Result cancel(String tenant, String session) {
        String token = null;
        try {
            token = store.acquire(tenant, session);
            if (token == null) return blocked("BUSY", "任务正在处理中，请等待后再取消。", true);
            var state = store.read(tenant, session);
            if (checkpoints != null && checkpoints.active(tenant, session).isPresent())
                return blocked("WAITING_APPROVAL", "执行已有存档，请通过审批卡片拒绝或恢复；不能直接清除执行状态。", true);
            if (state != null && "EXECUTING".equals(state.phase())) return blocked("EXECUTION_UNCERTAIN", "执行已经开始，不能清除防重记录，请先核查任务结果。", true);
            return cancelLocked(tenant, session, token);
        } catch (RuntimeException e) { return blocked("STATE_UNAVAILABLE", "任务状态不可用，未能取消，请稍后重试。", true); }
        finally { if (token != null) try { store.release(tenant, session, token); } catch (RuntimeException ignored) {} }
    }
    private Result cancelLocked(String tenant, String session, String token) {
        require(store.save(tenant, session, token, new RoutingWorkflowStore.State("", "DONE", List.of(), null)));
        return blocked("CANCELLED", "已取消未执行的待处理任务。", false);
    }
    private static void require(boolean saved) { if (!saved) throw new IllegalStateException("Lost workflow lease"); }
    private static Result blocked(String status, String directive, boolean pending) {
        return result(status, null, List.of(directive), false, directive, pending, List.of(), ValidatedPlanExecutor.emptyTelemetry(), List.of());
    }
    private static Result result(String status, RoutingPreviewService.Preview plan, List<String> questions, boolean generate,
                                 String directive, boolean pending, List<RoutingPreviewService.UserSource> sources,
                                 TurnTelemetry telemetry, List<ReActStep> steps) {
        return new Result(status, plan, List.copyOf(questions), generate, directive, pending, List.copyOf(sources), telemetry, List.copyOf(steps), null);
    }
    private static Result withExecution(Result base, CheckpointedDagExecutor.View view) {
        boolean completed = "COMPLETED".equals(view.status());
        boolean terminal = Set.of("COMPLETED", "REJECTED", "ROLLED_BACK").contains(view.status());
        String directive = completed ? "依据已执行工具结果回答原始用户任务。DEMO/isLive=false 为演示数据，不得补造业务事实。"
                : Objects.toString(view.message(), "任务已暂停，请处理审批或恢复执行。");
        return new Result(completed ? "EXECUTED" : view.status(), base.routingPlan(), List.of(), completed, directive,
                !terminal, base.sourceMessages(), view.telemetry(), view.reactSteps(), view);
    }
}
