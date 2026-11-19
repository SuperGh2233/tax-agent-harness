package com.taxagent.harness;

import com.taxagent.harness.runtime.*;
import com.taxagent.harness.runtime.TaskMemory.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.taxagent.harness.runtime.LlmClient.*;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

class MemoryLifecycleTest {
    @TempDir Path directory;
    Path db;
    TaskMemory memory;
    final Session u1 = new Session("U001"), u2 = new Session("U002");
    static Input said(String value) { return new Input(value, "用户原话：" + value); }

    @BeforeEach void setup() throws Exception {
        db = directory.resolve("memory.db"); DataInitializer.initialize(db); memory = new TaskMemory(db);
    }
    long count(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement(); var r = s.executeQuery(sql)) { return r.getLong(1); }
    }
    String modelInput(String message) {
        var agent = new FinanceAgent(db, u1, "C001", (LlmClient) request -> new Ask("好的"));
        var task = agent.start("偏好", List.of());
        var turn = agent.turn(task.id(), message);
        memory.cancel(u1, "C001", turn.task().id(), turn.task().version());
        return turn.modelInput();
    }

    @Test void newestExplicitStatementReplacesTheOldOneEverywhereTheModelLooks() {
        memory.putMemory(u1, "C001", Scope.USER_ENTERPRISE, "style", said("简洁"), null);
        assertThat(modelInput("你好")).contains("简洁");
        memory.putMemory(u1, "C001", Scope.USER_ENTERPRISE, "style", said("详细"), null);
        assertThat(memory.preferences(u1, "C001")).containsOnlyKeys("style").extractingByKey("style").extracting(Input::value).isEqualTo("详细");
        assertThat(modelInput("你好")).contains("详细").doesNotContain("简洁");
        assertThat(new TaskMemory(db).preferences(u1, "C001").get("style").value()).isEqualTo("详细"); // Restart-safe.
    }

    @Test void mostSpecificScopeWinsAndDeletingItFallsBackCleanly() {
        memory.putMemory(u1, "C001", Scope.ENTERPRISE, "style", said("正式"), null);
        memory.putMemory(u1, "C001", Scope.PERSONAL, "style", said("简洁"), null);
        assertThat(memory.preferences(u1, "C001")).containsOnlyKeys("个人·style");
        memory.putMemory(u1, "C001", Scope.USER_ENTERPRISE, "style", said("表格"), null);
        assertThat(memory.preferences(u1, "C001")).containsOnlyKeys("style");
        assertThat(memory.memories(u1, "C001")).extracting(Memory::scope, Memory::effective).containsExactlyInAnyOrder(
                tuple(Scope.USER_ENTERPRISE, true), tuple(Scope.PERSONAL, false), tuple(Scope.ENTERPRISE, false));
        memory.deleteMemory(u1, "C001", Scope.USER_ENTERPRISE, "style");
        memory.deleteMemory(u1, "C001", Scope.PERSONAL, "style");
        assertThat(memory.preferences(u1, "C001")).containsOnlyKeys("企业·style");
        assertThat(modelInput("你好")).contains("企业·style", "正式").doesNotContain("简洁", "表格");
    }

    @Test void personalAndEnterpriseMemoriesAreStrictlyIsolated() {
        memory.putMemory(u1, "C001", Scope.PERSONAL, "tone", said("先给结论"), null);
        memory.putMemory(u1, "C001", Scope.ENTERPRISE, "approval", said("报销需部门负责人签字"), null);
        memory.putMemory(u1, "C001", Scope.USER_ENTERPRISE, "focus", said("关注九月"), null);
        // U001's personal memory follows U001 into C002; C001's enterprise and user-enterprise memories do not.
        assertThat(memory.preferences(u1, "C002")).containsOnlyKeys("个人·tone");
        // U002 (C001 read-only) sees C001's shared enterprise memory, but none of U001's own memories.
        assertThat(memory.preferences(u2, "C001")).containsOnlyKeys("企业·approval");
        fails(ErrorCode.PERMISSION_DENIED, () -> memory.putMemory(u2, "C001", Scope.ENTERPRISE, "approval", said("无需签字"), null));
        fails(ErrorCode.PERMISSION_DENIED, () -> memory.deleteMemory(u2, "C001", Scope.ENTERPRISE, "approval"));
        fails(ErrorCode.PERMISSION_DENIED, () -> memory.memories(u2, "C002"));
        // U002's own personal memory is separate from U001's personal memory of the same name.
        memory.putMemory(u2, "C001", Scope.PERSONAL, "tone", said("详细说明"), null);
        assertThat(memory.preferences(u1, "C001").get("个人·tone").value()).isEqualTo("先给结论");
        assertThat(memory.preferences(u2, "C001").get("个人·tone").value()).isEqualTo("详细说明");
    }

    @Test void personalMemoryCannotCarryBusinessIdentifiersAndInputIsValidated() {
        for (var value : List.of("F002 的用途是办公", "只看 C001", "T013 是房租"))
            fails(ErrorCode.INVALID_ARGUMENT, () -> memory.putMemory(u1, "C001", Scope.PERSONAL, "x", said(value), null));
        memory.putMemory(u1, "C001", Scope.USER_ENTERPRISE, "x", said("F002 的用途是办公"), null); // Fine within one enterprise.
        fails(ErrorCode.INVALID_ARGUMENT, () -> memory.putMemory(u1, "C001", Scope.PERSONAL, "个人·x", said("y"), null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> memory.putMemory(u1, "C001", Scope.PERSONAL, "x", said("y"), "2020-01-01"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> memory.putMemory(u1, "C001", Scope.PERSONAL, "x", said("y"), "明天"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> memory.putMemory(u1, "C001", Scope.PERSONAL, "x", new Input("y", " "), null));
    }

    @Test void expiredMemoriesAreErasedNotMerelyHidden() throws Exception {
        var tomorrow = LocalDate.now().plusDays(1).toString();
        memory.putMemory(u1, "C001", Scope.PERSONAL, "temp", said("本周先看汇总"), tomorrow);
        assertThat(memory.memories(u1, "C001")).extracting(Memory::expiresOn).containsExactly(tomorrow);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
            s.execute("UPDATE personal_preferences SET expires_on='2026-01-01'");
        }
        assertThat(memory.preferences(u1, "C001")).isEmpty();
        assertThat(count("SELECT count(*) FROM personal_preferences")).isZero();
    }

    @Test void deletedAndOverwrittenTextLeavesNoBytesInTheDatabaseFile() throws Exception {
        memory.putMemory(u1, "C001", Scope.PERSONAL, "secret", said("独特标记偏好甲乙丙"), null);
        memory.putMemory(u1, "C001", Scope.USER_ENTERPRISE, "old", said("即将更正的旧值丁戊己"), null);
        memory.putMemory(u1, "C001", Scope.USER_ENTERPRISE, "old", said("新值"), null);
        memory.deleteMemory(u1, "C001", Scope.PERSONAL, "secret");
        var bytes = new String(Files.readAllBytes(db), StandardCharsets.UTF_8);
        assertThat(bytes).doesNotContain("独特标记偏好甲乙丙").doesNotContain("即将更正的旧值丁戊己");
    }
}
