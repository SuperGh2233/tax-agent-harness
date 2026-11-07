package com.taxagent.harness;

import com.taxagent.harness.demo.RuleAdminCli;
import com.taxagent.harness.tool.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RuleAdminCliTest {
    @TempDir Path directory;

    @Test void listsImportsAndDisablesThroughTheLocalCli() throws Exception {
        var db = directory.resolve("rules-cli.db");
        DataInitializer.initialize(db);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (var stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
             var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            assertThat(RuleAdminCli.run(new String[]{"--rules-list", "--enterprise=C001", "--date=2026-09-15"},
                    db, stdout, stderr)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("R004", "银行资料说明");

            var content = directory.resolve("rule.txt");
            Files.writeString(content, "测试规则正文", StandardCharsets.UTF_8);
            assertThat(RuleAdminCli.run(new String[]{"--rules-import", "--rule-id=R005", "--version=1.0",
                    "--name=测试规则", "--effective-from=2026-10-01", "--content-file=" + content,
                    "--enterprises=C001,C002"}, db, stdout, stderr)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("IMPORTED");
            assertThat(new RuleService(db).queryRules(new PermissionGuard.Session("U001"), "C001",
                    LocalDate.of(2026, 10, 1))).extracting(rule -> rule.key().ruleId()).contains("R005");

            out.reset();
            assertThat(RuleAdminCli.run(new String[]{"--rules-disable", "--rule-id=R005", "--version=1.0"},
                    db, stdout, stderr)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("DISABLED");

            out.reset();
            assertThat(RuleAdminCli.run(new String[]{"--rules-list", "--enterprise=C001", "--date=2026-10-01"},
                    db, stdout, stderr)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("R005");

            assertThat(RuleAdminCli.run(new String[]{"--rules-import", "--rule-id=R006", "--version=1.0",
                    "--name=缺失正文", "--effective-from=2026-10-01", "--enterprises=C001"}, db, stdout, stderr))
                    .isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("ERROR");
        }
    }

    @Test void preferencesCliListsPutsAndDeletesExplicitStatements() throws Exception {
        var db = directory.resolve("prefs-cli.db");
        DataInitializer.initialize(db);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (var stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
             var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            assertThat(RuleAdminCli.run(new String[]{"--prefs-list", "--enterprise=C001"}, db, stdout, stderr)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("NO_PREFERENCES");

            assertThat(RuleAdminCli.run(new String[]{"--prefs-put", "--enterprise=C001", "--name=style",
                    "--value=简洁", "--user-request=我喜欢简洁的回答"}, db, stdout, stderr)).isZero();
            assertThat(new com.taxagent.harness.runtime.TaskMemory(db)
                    .preferences(new PermissionGuard.Session("U001"), "C001")).containsKey("style");

            out.reset();
            assertThat(RuleAdminCli.run(new String[]{"--prefs-list", "--enterprise=C001"}, db, stdout, stderr)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("style", "简洁");

            assertThat(RuleAdminCli.run(new String[]{"--prefs-delete", "--enterprise=C001", "--name=style"},
                    db, stdout, stderr)).isZero();
            out.reset();
            assertThat(RuleAdminCli.run(new String[]{"--prefs-list", "--enterprise=C001"}, db, stdout, stderr)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("NO_PREFERENCES");
        }
    }
}