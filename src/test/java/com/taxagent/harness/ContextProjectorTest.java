package com.taxagent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.PermissionGuard;
import com.taxagent.harness.tool.ReadOnlyFinanceTools;
import com.taxagent.harness.tool.RuleService;
import com.taxagent.harness.tool.ToolContract;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.taxagent.harness.runtime.LlmClient.*;
import static org.assertj.core.api.Assertions.*;

class ContextProjectorTest {
    static class FakeLlmClient implements LlmClient {
        final List<Request> requests = new ArrayList<>();
        final Queue<Decision> decisions = new ArrayDeque<>();
        FakeLlmClient(Decision... decisions) { this.decisions.addAll(List.of(decisions)); }
        public Decision next(Request request) { requests.add(request); return decisions.remove(); }
    }

    @Test void projectionKeepsTaskMonthPendingAndDropsUnrelatedPreferences() {
        var task = new TaskMemory.Task("task", TaskMemory.Status.ACTIVE, 1, "检查九月发票",
                List.of("F002.purpose"),
                Map.of("F002.purpose", new TaskMemory.Input("办公室实际采购", "用户确认")),
                null, List.of(new TaskMemory.Summary("query_invoices", "SUCCESS")));
        var preferences = new LinkedHashMap<String, TaskMemory.Input>();
        for (int i = 0; i < 100; i++)
            preferences.put("noise-" + i, new TaskMemory.Input("无关偏好" + i, "用户没提"));
        preferences.put("month", new TaskMemory.Input("九月", "用户要求查九月"));
        var context = new TaskMemory.Context(task, preferences);

        var projection = ContextProjector.project(context, "刚才说错了，查九月");

        assertThat(projection.memoryJson())
                .contains("九月", "F002.purpose", "month", "用户要求查九月")
                .doesNotContain("noise-99");
        assertThat(projection.memoryJson().length()).isLessThanOrEqualTo(ContextProjector.MAX_CHARS);
        assertThat(projection.omittedPreferences()).isEqualTo(100);
        assertThat(context.preferences()).hasSize(101);
    }

    @Test void turnUsesProjectionWhileFullContextRemainsPersisted(@TempDir Path directory) throws Exception {
        var db = directory.resolve("projection.db");
        DataInitializer.initialize(db);
        var memory = new TaskMemory(db);
        var user = new PermissionGuard.Session("U001");
        var task = memory.start(user, "C001", "检查九月发票", List.of("F002.purpose"));
        for (int i = 0; i < 100; i++)
            memory.putPreference(user, "C001", "noise-" + i, new TaskMemory.Input("无关偏好" + i, "用户没提"));
        memory.putPreference(user, "C001", "month", new TaskMemory.Input("九月", "用户要求查九月"));

        var fake = new FakeLlmClient(
                new Call("query", "query_invoices", "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}"),
                new Finish("已完成"));
        var runtime = new AgentRuntime(fake, new ReadOnlyFinanceTools(db), new RuleService(db),
                AgentRuntime.Limits.defaults());
        var turn = memory.continueTask(user, "C001", task.id(), "刚才说错了，查九月", runtime);

        assertThat(fake.requests.getFirst().userMessage())
                .contains("九月", "F002.purpose")
                .doesNotContain("noise-99");
        assertThat(fake.requests.getFirst().userMessage().length()).isLessThan(6000);
        assertThat(turn.memorySource()).isEqualTo("task-memory+projection");
        assertThat(memory.load(user, "C001", task.id()).preferences()).hasSize(101);
        var response = new ObjectMapper().readTree(turn.result().history().getFirst().resultJson());
        assertThat(response.at("/data/total_cents").asLong()).isEqualTo(962206);
        assertThat(turn.task().pending()).contains("F002.purpose");
    }
}