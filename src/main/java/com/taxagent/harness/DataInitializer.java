package com.taxagent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit, transactional seed import. Existing keys are never overwritten. */
public final class DataInitializer {
    private DataInitializer() {}

    public static void initialize(Path database) throws Exception {
        var absolute = database.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        var tables = new LinkedHashMap<String, String>();
        tables.put("enterprises", "id");
        tables.put("users", "id");
        tables.put("permissions", "user_id,enterprise_id");
        tables.put("invoices", "enterprise_id,id");
        tables.put("bank_entries", "enterprise_id,id");
        tables.put("rules", "id,version");
        tables.put("rule_enterprises", "rule_id,version,enterprise_id");
        try (var schema = DataInitializer.class.getResourceAsStream("/seed/schema.sql");
             var seed = DataInitializer.class.getResourceAsStream("/seed/official-v1.1.0.json")) {
            if (schema == null || seed == null) throw new IllegalStateException("Missing seed resources");
            var data = new ObjectMapper().readTree(seed);
            var ddl = new String(schema.readAllBytes(), StandardCharsets.UTF_8);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + absolute)) {
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys=ON");
                    statement.execute("PRAGMA busy_timeout=5000");
                }
                connection.setAutoCommit(false);
                try {
                    try (var statement = connection.createStatement()) {
                        for (var sql : ddl.split(";")) if (!sql.isBlank()) statement.execute(sql);
                    }
                    for (Map.Entry<String, String> table : tables.entrySet()) {
                        var rows = data.required(table.getKey());
                        int width = rows.get(0).size();
                        var placeholders = String.join(",", java.util.Collections.nCopies(width, "?"));
                        try (var insert = connection.prepareStatement("INSERT INTO " + table.getKey()
                                + " VALUES (" + placeholders + ") ON CONFLICT(" + table.getValue() + ") DO NOTHING")) {
                            for (var row : rows) {
                                for (int i = 0; i < width; i++) {
                                    if (row.get(i).isIntegralNumber()) insert.setLong(i + 1, row.get(i).longValue());
                                    else insert.setString(i + 1, row.get(i).textValue());
                                }
                                insert.executeUpdate();
                            }
                        }
                    }
                    connection.commit();
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }
    }
}
