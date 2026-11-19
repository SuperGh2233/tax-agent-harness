package com.taxagent.harness.tool;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Additive schema steps for services built per request (files, agents, capabilities). Each step runs once per
 * database per process, on a connection that waits for locks: running them on every request made parallel
 * requests fail with SQLITE_BUSY (HTTP 500) as soon as the workspace loaded several panels at once.
 */
public final class Migrations {
    private static final Set<String> DONE = ConcurrentHashMap.newKeySet();
    private Migrations() {}

    @FunctionalInterface public interface Step { void run(Connection c) throws Exception; }

    public static void once(String url, String key, Step step) {
        var id = key + "@" + url;
        if (DONE.contains(id)) return;
        synchronized (Migrations.class) {
            if (DONE.contains(id)) return;
            try (var c = Sqlite.open(url)) {
                try (var s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA foreign_keys=ON"); }
                step.run(c);
            } catch (DomainException e) { throw e;
            } catch (Exception e) { throw new DomainException(ErrorCode.STORAGE_ERROR, "Schema migration failed", e); }
            DONE.add(id);
        }
    }

    /** Runs a resource script whose statements end at a line end (trigger bodies contain ';' mid-line). */
    public static void script(Connection c, Class<?> owner, String resource) throws Exception {
        try (var in = owner.getResourceAsStream(resource); var s = c.createStatement()) {
            for (var sql : new String(Objects.requireNonNull(in, resource).readAllBytes(), StandardCharsets.UTF_8).split(";\\s*\\n"))
                if (!sql.isBlank()) s.execute(sql);
        }
    }
}
