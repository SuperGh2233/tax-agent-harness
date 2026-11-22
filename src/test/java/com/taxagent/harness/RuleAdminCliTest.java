package com.taxagent.harness;

import com.taxagent.harness.demo.RuleAdminCli;
import com.taxagent.harness.tool.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Arrays;
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

    /** T51: unusable purpose wording is refused before the immutable version exists, unless explicitly allowed. */
    @Test void refusesUnexecutablePurposeWordingBeforeStoringTheVersion() throws Exception {
        var db = directory.resolve("rules-wording.db");
        DataInitializer.initialize(db);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (var stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
             var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            var reworded = directory.resolve("reworded.txt");
            Files.writeString(reworded, "金额较大的发票原则上必须填写用途。", StandardCharsets.UTF_8);
            assertThat(RuleAdminCli.run(importArgs("R002", "3.0", reworded), db, stdout, stderr)).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("未导入", "每张发票都必须填写用途", "--allow-unexecutable");
            assertThat(versions(db, "R002")).doesNotContain("3.0");

            // A new version of an existing purpose rule id is caught even without the phrase.
            err.reset();
            var vague = directory.resolve("vague.txt");
            Files.writeString(vague, "用途视情况填写。", StandardCharsets.UTF_8);
            assertThat(RuleAdminCli.run(importArgs("R002", "3.1", vague), db, stdout, stderr)).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("规则 R002 已有版本是用途规则");

            // Explicitly allowed: stored (T28 still refuses it at check time) and reported as a warning.
            err.reset(); out.reset();
            var allowed = Arrays.copyOf(importArgs("R002", "3.0", reworded), 8);
            allowed[7] = "--allow-unexecutable";
            assertThat(RuleAdminCli.run(allowed, db, stdout, stderr)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("IMPORTED");
            assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("WARNING");
            assertThat(versions(db, "R002")).contains("3.0");

            // Executable purpose wording and rules that are not purpose rules import without a word.
            err.reset();
            var threshold = directory.resolve("threshold.txt");
            Files.writeString(threshold, "单张发票金额大于或等于 1000.00 元时必须填写用途。", StandardCharsets.UTF_8);
            assertThat(RuleAdminCli.run(importArgs("R002", "4.0", threshold), db, stdout, stderr)).isZero();
            var note = directory.resolve("note.txt");
            Files.writeString(note, "银行流水仅作资金往来参考。", StandardCharsets.UTF_8);
            assertThat(RuleAdminCli.run(importArgs("R009", "1.0", note), db, stdout, stderr)).isZero();
            assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
        }
    }
    private static String[] importArgs(String id, String version, Path content) {
        return new String[]{"--rules-import", "--rule-id=" + id, "--version=" + version, "--name=合成测试规则",
                "--effective-from=2026-10-01", "--content-file=" + content, "--enterprises=C001"};
    }
    private static java.util.List<String> versions(Path db, String id) {
        return new RuleService(db).queryRules(new PermissionGuard.Session("U001"), "C001", null).stream()
                .filter(rule -> rule.key().ruleId().equals(id)).map(rule -> rule.key().version()).toList();
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