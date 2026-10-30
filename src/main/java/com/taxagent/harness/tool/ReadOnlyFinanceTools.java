package com.taxagent.harness.tool;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.ArrayList;
import static com.taxagent.harness.tool.ToolContract.*;

/** Service-only entry point; Session must come from an independently trusted caller. */
public final class ReadOnlyFinanceTools {
    private final String databaseUrl;
    private final PermissionGuard guard = new PermissionGuard();

    public ReadOnlyFinanceTools() { this(Path.of("data/harness.db")); }
    public ReadOnlyFinanceTools(Path database) {
        databaseUrl = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=ro";
    }

    public InvoiceResult queryInvoices(PermissionGuard.Session session, Query query) {
        return read(session, query, connection -> {
            var rows = new ArrayList<Invoice>();
            long total = 0;
            try (var statement = connection.prepareStatement("""
                    SELECT enterprise_id,id,invoice_date,seller,item,amount_cents,purpose,remarks
                    FROM invoices WHERE enterprise_id=? AND invoice_date>=? AND invoice_date<?
                    ORDER BY invoice_date,id
                    """)) {
                bind(statement, query);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        long amount = result.getLong("amount_cents");
                        rows.add(new Invoice(new InvoiceKey(result.getString("enterprise_id"), result.getString("id")),
                                LocalDate.parse(result.getString("invoice_date")), result.getString("seller"),
                                result.getString("item"), amount, result.getString("purpose"), result.getString("remarks")));
                        total = Math.addExact(total, amount);
                    }
                }
            }
            return new InvoiceResult(rows, total);
        });
    }

    public BankResult queryBankEntries(PermissionGuard.Session session, Query query) {
        return read(session, query, connection -> {
            var rows = new ArrayList<BankEntry>();
            long income = 0, expense = 0;
            try (var statement = connection.prepareStatement("""
                    SELECT enterprise_id,id,transaction_date,direction,amount_cents,counterparty,remarks
                    FROM bank_entries WHERE enterprise_id=? AND transaction_date>=? AND transaction_date<?
                    ORDER BY transaction_date,id
                    """)) {
                bind(statement, query);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        long amount = result.getLong("amount_cents");
                        var direction = Direction.valueOf(result.getString("direction"));
                        rows.add(new BankEntry(new BankKey(result.getString("enterprise_id"), result.getString("id")),
                                LocalDate.parse(result.getString("transaction_date")), direction, amount,
                                result.getString("counterparty"), result.getString("remarks")));
                        if (direction == Direction.收入) income = Math.addExact(income, amount);
                        else expense = Math.addExact(expense, amount);
                    }
                }
            }
            return new BankResult(rows, income, expense);
        });
    }

    private static void bind(PreparedStatement statement, Query query) throws SQLException {
        var month = YearMonth.parse(query.month());
        statement.setString(1, query.enterpriseId());
        statement.setString(2, month.atDay(1).toString());
        statement.setString(3, month.plusMonths(1).atDay(1).toString());
    }

    private <T> T read(PermissionGuard.Session session, Query query, Read<T> operation) {
        if (query == null) throw ToolContract.invalid("Query is required");
        if (session == null) throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED, "Trusted session required");
        try (var connection = DriverManager.getConnection(databaseUrl)) {
            connection.setAutoCommit(false);
            guard.require(connection, session, query.enterpriseId(), PermissionGuard.Access.READ);
            T result = operation.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException | IllegalArgumentException | ArithmeticException | DateTimeException e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "Finance query failed", e);
        }
    }

    @FunctionalInterface
    private interface Read<T> { T execute(Connection connection) throws SQLException; }
}
