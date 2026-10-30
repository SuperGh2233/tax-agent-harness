package com.taxagent.harness.runtime;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.InvoiceUpdate;
import java.nio.file.Path;
import java.util.*;

/** Local service entry point. Caller supplies authenticated/trusted identity, never HTTP/model userId. */
public final class FinanceAgent {
    private final Session session;
    private final String enterprise;
    private final TaskMemory memory;
    private final AgentRuntime runtime;
    private final InvoiceWriteService writer;

    public FinanceAgent(Path database, Session trustedSession, String selectedEnterprise, LlmClient model) {
        this.session = trustedSession;
        this.enterprise = selectedEnterprise;
        memory = new TaskMemory(database);
        memory.tasks(session, enterprise); // Validate trusted selection before any model invocation.
        writer = new InvoiceWriteService(database);
        runtime = new AgentRuntime(model, new ReadOnlyFinanceTools(database), new RuleService(database),
                AgentRuntime.Limits.defaults(), writer);
    }
    public TaskMemory.Task start(String goal, List<String> pending) {
        return memory.start(session, enterprise, goal, pending);
    }
    public TaskMemory.Context load(String id) { return memory.load(session, enterprise, id); }
    public List<TaskMemory.Task> tasks() { return memory.tasks(session, enterprise); }
    /** Explicit user statements only. This never creates financial write approval. */
    public TaskMemory.Task supplement(String id, long version, Map<String, TaskMemory.Input> statements) {
        return memory.supplement(session, enterprise, id, version, statements);
    }
    public TaskMemory.Turn turn(String id, String message) {
        return memory.continueTask(session, enterprise, id, message, runtime);
    }
    /** Trusted application action, never a model tool or natural-language intent classifier.
     * Caller has verified user explicitly requested this exact save/clear now.
     * Approval is current-turn only and is not stored in TaskMemory.
     */
    public TaskMemory.Turn approvedTurn(String id, String message, String operationId,
                                       InvoiceUpdate update, InvoiceWriteService.Intent explicitIntent) {
        load(id);
        if (update == null || !enterprise.equals(update.key().enterpriseId()))
            throw new DomainException(ErrorCode.PERMISSION_DENIED, "Selected enterprise required");
        var authorization = InvoiceWriteService.authorizeExplicitRequest(session, operationId, update, explicitIntent);
        return memory.continueTask(session, enterprise, id, message, runtime,
                new AgentRuntime.ApprovedWrite(operationId, update, authorization));
    }
    /** Unknown write outcome: query only; empty is never automatic retry permission. */
    public Optional<ToolContract.UpdateResult> recover(String operationId, InvoiceUpdate update) {
        if (update == null || !enterprise.equals(update.key().enterpriseId()))
            throw new DomainException(ErrorCode.PERMISSION_DENIED, "Selected enterprise required");
        return writer.recover(session, operationId, update);
    }
}
