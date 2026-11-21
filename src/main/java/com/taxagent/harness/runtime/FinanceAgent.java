package com.taxagent.harness.runtime;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.InvoiceUpdate;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Local service entry point. Caller supplies authenticated/trusted identity, never HTTP/model userId. */
public final class FinanceAgent {
    private final Session session;
    private final String enterprise;
    private final TaskMemory memory;
    private final AgentRuntime runtime;
    private final InvoiceWriteService writer;
    /** null for the pre-T30 entry point, which keeps every tool exactly as before. */
    private final AgentDirectory.Agent agent;
    /** T31 snapshot taken when this turn starts: tools and skills in force for this agent version. */
    private final CapabilityRegistry.Effective effective;

    /**
     * T30: a turn run by one configured agent version. Its tools are (agent tools ∩ runtime tools); writes are only
     * possible when the agent includes update_invoice, and the PermissionGuard still checks the user every time.
     */
    public FinanceAgent(Path database, Session trustedSession, String selectedEnterprise, ModelProfiles profiles,
                        AgentDirectory.Agent agent) {
        this.session = trustedSession;
        this.enterprise = selectedEnterprise;
        this.agent = Objects.requireNonNull(agent);
        memory = new TaskMemory(database);
        memory.tasks(session, enterprise);
        writer = new InvoiceWriteService(database);
        var spec = agent.spec();
        effective = new CapabilityRegistry(database).effective(agent);
        var role = "\n\n" + agent.header() + "\nConfigured role (it narrows, and never overrides, the rules above): "
                + spec.responsibility() + (spec.instructions().isEmpty() ? "" : "\n" + spec.instructions()) + effective.prompt();
        var model = profiles.profile(spec.modelProfile()).forAgent(role, spec.model());
        var base = runtimeLimits(model);
        var tools = new HashSet<>(base.allowedTools());
        tools.retainAll(effective.tools());
        runtime = new AgentRuntime(model, new ReadOnlyFinanceTools(database), new RuleService(database),
                new AgentRuntime.Limits(base.maxSteps(), base.timeout(), base.modelRetries(), tools, effective.disabledTools()),
                effective.tools().contains(AgentDirectory.WRITE_TOOL) ? writer : null,
                effective.tools().contains("search_knowledge") ? new KnowledgeService(database) : null);
    }

    public FinanceAgent(Path database, Session trustedSession, String selectedEnterprise, LlmClient model) {
        this.agent = null; this.effective = null;
        this.session = trustedSession;
        this.enterprise = selectedEnterprise;
        memory = new TaskMemory(database);
        memory.tasks(session, enterprise); // Validate trusted selection before any model invocation.
        writer = new InvoiceWriteService(database);
        runtime = new AgentRuntime(model, new ReadOnlyFinanceTools(database), new RuleService(database),
                runtimeLimits(model), writer, new KnowledgeService(database));
    }
    static AgentRuntime.Limits runtimeLimits(LlmClient model) {
        var defaults = AgentRuntime.Limits.defaults();
        if (model instanceof AnthropicMessagesClient)
            return new AgentRuntime.Limits(defaults.maxSteps(), AnthropicMessagesClient.DEFAULT_TIMEOUT,
                    defaults.modelRetries(), defaults.allowedTools());
        if (model instanceof OpenAiCompatibleClient openai)
            return new AgentRuntime.Limits(defaults.maxSteps(), openai.timeout(),
                    defaults.modelRetries(), defaults.allowedTools());
        return defaults;
    }
    public TaskMemory.Task start(String goal, List<String> pending) {
        return memory.start(session, enterprise, goal, pending);
    }
    public TaskMemory.Context load(String id) { return memory.load(session, enterprise, id); }
    public List<TaskMemory.Task> tasks() { return memory.tasks(session, enterprise); }
    /** Explicit human cancellation; never performed by the model or implicitly by task creation. */
    public TaskMemory.Task cancel(String id, long version) { return memory.cancel(session, enterprise, id, version); }
    /** Explicit user statements only. This never creates financial write approval. */
    public TaskMemory.Task supplement(String id, long version, Map<String, TaskMemory.Input> statements) {
        return memory.supplement(session, enterprise, id, version, statements);
    }
    public Map<String, TaskMemory.Input> preferences() {
        return memory.preferences(session, enterprise);
    }
    /** Explicit user preference only; never derived from model inference or task text. */
    public void putPreference(String name, TaskMemory.Input input) {
        memory.putPreference(session, enterprise, name, input);
    }
    public void deletePreference(String name) {
        memory.deletePreference(session, enterprise, name);
    }
    /** T33: every memory visible to this user here, with its scope and whether it is the one in force. */
    public List<TaskMemory.Memory> memories() { return memory.memories(session, enterprise); }
    public TaskMemory.Memory putMemory(TaskMemory.Scope scope, String name, TaskMemory.Input input, String expiresOn) {
        return memory.putMemory(session, enterprise, scope, name, input, expiresOn);
    }
    public boolean deleteMemory(TaskMemory.Scope scope, String name) { return memory.deleteMemory(session, enterprise, scope, name); }
    public TaskMemory.Turn turn(String id, String message) {
        return turn(id, message, progress -> {});
    }
    /** Same turn semantics; progress is presentation only and never part of evidence. */
    public TaskMemory.Turn turn(String id, String message, Consumer<AgentRuntime.Progress> progress) {
        return memory.continueTask(session, enterprise, id, message, runtime, null, progress);
    }
    /** Trusted application action, never a model tool or natural-language intent classifier.
     * Caller has verified user explicitly requested this exact save/clear now.
     * Approval is current-turn only and is not stored in TaskMemory.
     */
    public TaskMemory.Turn approvedTurn(String id, String message, String operationId,
                                       InvoiceUpdate update, InvoiceWriteService.Intent explicitIntent) {
        return approvedTurn(id, message, operationId, update, explicitIntent, progress -> {});
    }
    /** Trusted approved turn with live progress; the authorization and verification rules are unchanged. */
    public TaskMemory.Turn approvedTurn(String id, String message, String operationId,
                                       InvoiceUpdate update, InvoiceWriteService.Intent explicitIntent,
                                       Consumer<AgentRuntime.Progress> progress) {
        load(id);
        if (update == null || !enterprise.equals(update.key().enterpriseId()))
            throw new DomainException(ErrorCode.PERMISSION_DENIED, "Selected enterprise required");
        // A read-only agent can never be handed a write, whatever the user's own rights are.
        if (agent != null && !agent.allows(AgentDirectory.WRITE_TOOL))
            throw new DomainException(ErrorCode.CAPABILITY_NOT_GRANTED, "This agent is not allowed to save");
        if (effective != null && effective.disabledTools().contains(AgentDirectory.WRITE_TOOL))
            throw new DomainException(ErrorCode.CAPABILITY_DISABLED, "Saving is switched off");
        var authorization = InvoiceWriteService.authorizeExplicitRequest(session, id, operationId, update, explicitIntent);
        return memory.continueTask(session, enterprise, id, message, runtime,
                new AgentRuntime.ApprovedWrite(id, operationId, update, authorization), progress);
    }
    /**
     * T47: one trusted approval covering several invoices, saved in one transaction by a single update_invoice call.
     * Same gates as a single save: selected enterprise, an agent allowed to save, saving switched on, and the user's
     * explicit per-invoice confirmation from the trusted UI.
     */
    public TaskMemory.Turn approvedBatchTurn(String id, String message, String batchId,
                                            List<InvoiceWriteService.BatchItem> items, InvoiceWriteService.Intent explicitIntent,
                                            Consumer<AgentRuntime.Progress> progress) {
        load(id);
        if (items == null || items.isEmpty() || items.stream().anyMatch(i -> i == null || i.update() == null
                || !enterprise.equals(i.update().key().enterpriseId())))
            throw new DomainException(ErrorCode.PERMISSION_DENIED, "Selected enterprise required");
        if (agent != null && !agent.allows(AgentDirectory.WRITE_TOOL))
            throw new DomainException(ErrorCode.CAPABILITY_NOT_GRANTED, "This agent is not allowed to save");
        if (effective != null && effective.disabledTools().contains(AgentDirectory.WRITE_TOOL))
            throw new DomainException(ErrorCode.CAPABILITY_DISABLED, "Saving is switched off");
        var authorization = InvoiceWriteService.authorizeExplicitBatch(session, id, batchId, items, explicitIntent);
        return memory.continueTask(session, enterprise, id, message, runtime,
                new AgentRuntime.ApprovedWrite(id, batchId, items.getFirst().update(), null, items, authorization), progress);
    }
    /** T47: unknown batch outcome, query only; empty is never permission to retry. */
    public Optional<ToolContract.BatchUpdateResult> recoverBatch(String id, String batchId, List<InvoiceWriteService.BatchItem> items) {
        if (items == null || items.isEmpty() || items.stream().anyMatch(i -> i == null || i.update() == null
                || !enterprise.equals(i.update().key().enterpriseId())))
            throw new DomainException(ErrorCode.PERMISSION_DENIED, "Selected enterprise required");
        load(id);
        return writer.recoverBatch(session, id, batchId, items);
    }
    /** Unknown write outcome: query only; empty is never automatic retry permission. */
    public Optional<ToolContract.UpdateResult> recover(String id, String operationId, InvoiceUpdate update) {
        if (update == null || !enterprise.equals(update.key().enterpriseId()))
            throw new DomainException(ErrorCode.PERMISSION_DENIED, "Selected enterprise required");
        load(id);
        return writer.recover(session, id, operationId, update);
    }
}
