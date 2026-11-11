package com.taxagent.harness.demo;

import com.taxagent.harness.runtime.TaskMemory;
import com.taxagent.harness.tool.*;
import static com.taxagent.harness.tool.ToolContract.*;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Trusted local CLI entry for the official rule import/find/disable requirement. */
public final class RuleAdminCli {
    private RuleAdminCli() {}

    public static int run(String[] args, Path database, PrintStream out, PrintStream err) {
        var options = options(args);
        try {
            var service = new RuleService(database);
            var admin = RuleService.trustedLocalManagement();
            if (options.containsKey("rules-list")) {
                String enterprise = required(options, "enterprise");
                LocalDate date = options.containsKey("date") ? LocalDate.parse(options.get("date")) : null;
                var rules = service.queryRules(new PermissionGuard.Session("U001"), enterprise, date);
                if (rules.isEmpty()) out.println("NO_RULES");
                for (var rule : rules) out.println(rule.key().ruleId() + "\t" + rule.key().version() + "\t"
                        + rule.definition().status() + "\t" + rule.definition().effectiveFrom() + "\t"
                        + rule.definition().name() + "\t" + rule.definition().content());
                return 0;
            }
            if (options.containsKey("rules-import") || options.containsKey("rules-update")) {
                var key = new RuleKey(required(options, "rule-id"), required(options, "version"));
                String content = Files.readString(Path.of(required(options, "content-file")), StandardCharsets.UTF_8);
                var enterprises = new LinkedHashSet<>(Arrays.stream(required(options, "enterprises").split(","))
                        .map(String::trim).filter(value -> !value.isBlank()).toList());
                if (enterprises.isEmpty()) throw new IllegalArgumentException("enterprises must not be empty");
                var definition = new RuleDefinition(required(options, "name"),
                        LocalDate.parse(required(options, "effective-from")),
                        RuleStatus.valueOf(options.getOrDefault("status", "启用")), content);
                var saved = service.importVersion(admin, key, definition, enterprises);
                out.println("IMPORTED\t" + saved.key().ruleId() + "\t" + saved.key().version());
                return 0;
            }
            if (options.containsKey("rules-disable")) {
                var key = new RuleKey(required(options, "rule-id"), required(options, "version"));
                service.disableVersion(admin, key);
                out.println("DISABLED\t" + key.ruleId() + "\t" + key.version());
                return 0;
            }
            if (options.containsKey("prefs-list")) {
                var memory = new TaskMemory(database);
                var preferences = memory.preferences(user(options), required(options, "enterprise"));
                if (preferences.isEmpty()) out.println("NO_PREFERENCES");
                preferences.forEach((name, input) -> out.println(name + "\t" + input.value() + "\t" + input.userRequest()));
                return 0;
            }
            if (options.containsKey("prefs-put")) {
                var memory = new TaskMemory(database);
                memory.putPreference(user(options), required(options, "enterprise"), required(options, "name"),
                        new TaskMemory.Input(required(options, "value"), required(options, "user-request")));
                out.println("PREFERENCE_PUT\t" + options.get("name"));
                return 0;
            }
            if (options.containsKey("prefs-delete")) {
                var memory = new TaskMemory(database);
                memory.deletePreference(user(options), required(options, "enterprise"), required(options, "name"));
                out.println("PREFERENCE_DELETED\t" + options.get("name"));
                return 0;
            }
            err.println("Usage: --rules-list --enterprise=C001 [--date=2026-09-15]");
            err.println("       --rules-import/--rules-update --rule-id=R005 --version=1.0 --name=NAME --effective-from=2026-10-01 "
                    + "--content-file=rule.txt --enterprises=C001,C002 [--status=启用]");
            err.println("       （更新 = 新增版本；已存在的版本号不可原地改写，须先 --rules-disable 再导入新版本）");
            err.println("       --rules-disable --rule-id=R005 --version=1.0");
            err.println("       --prefs-list --enterprise=C001 [--user=U001]");
            err.println("       --prefs-put --enterprise=C001 --name=style --value=简洁 --user-request=用户原话 [--user=U001]");
            err.println("       --prefs-delete --enterprise=C001 --name=style [--user=U001]");
            return 2;
        } catch (Exception failure) {
            err.println("ERROR: " + failure.getMessage());
            return 1;
        }
    }

    private static Map<String, String> options(String[] args) {
        var result = new LinkedHashMap<String, String>();
        for (var arg : args) {
            if (!arg.startsWith("--")) continue;
            var text = arg.substring(2);
            int equals = text.indexOf('=');
            result.put(equals < 0 ? text : text.substring(0, equals), equals < 0 ? "" : text.substring(equals + 1));
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        var value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + name);
        return value;
    }

    private static PermissionGuard.Session user(Map<String, String> options) {
        return new PermissionGuard.Session(options.getOrDefault("user", "U001"));
    }
}