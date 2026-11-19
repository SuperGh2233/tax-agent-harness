package com.taxagent.harness.tool;

import java.sql.*;
import java.util.Properties;

/**
 * The one way to open connections, so that transactions which read before they write take the write lock up front.
 * A deferred transaction cannot upgrade its read lock while another connection is writing: SQLite then returns BUSY
 * at once, without waiting for busy_timeout, and the request fails closed (HTTP 500). The workspace's parallel
 * requests made this routine. IMMEDIATE takes the write lock when the transaction begins, which does wait.
 * Read-only (mode=ro) connections are unaffected; transactions that never write use {@link #openForRead}.
 */
public final class Sqlite {
    private Sqlite() {}
    /** A transaction that reads and then writes. */
    public static Connection open(String url) throws SQLException {
        var properties = new Properties();
        if (url.contains("mode=rw")) properties.setProperty("transaction_mode", "IMMEDIATE");
        return DriverManager.getConnection(url, properties);
    }
    /**
     * A transaction that only reads. IMMEDIATE must not be used here: BEGIN IMMEDIATE takes the write lock, which a
     * connection under PRAGMA query_only refuses, so SQLite rejects the statement with SQLITE_READONLY.
     */
    public static Connection openForRead(String url) throws SQLException {
        return DriverManager.getConnection(url);
    }
}
