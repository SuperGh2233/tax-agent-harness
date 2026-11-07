package com.taxagent.harness.demo;

import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Runs only when a --rules-* command is present; normal web startup is untouched. */
@Component
public final class RuleAdminRunner implements ApplicationRunner {
    private final Path database;

    public RuleAdminRunner(@Value("${taxagent.database:data/harness.db}") String database) {
        this.database = Path.of(database);
    }

    @Override public void run(ApplicationArguments args) {
        if (Arrays.stream(args.getSourceArgs()).noneMatch(arg -> arg.startsWith("--rules-") || arg.startsWith("--prefs-"))) return;
        System.exit(RuleAdminCli.run(args.getSourceArgs(), database, System.out, System.err));
    }
}