package com.taxagent.harness.tool;

import java.sql.*;

/** Session must be created by the trusted server entry point, never by tool arguments. */
public final class PermissionGuard {
    public record Session(String userId) {
        public Session {
            if (userId == null || userId.isBlank())
                throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED, "Trusted session required");
        }
    }
    public enum Access { READ, WRITE, MANAGE_RULES }

    /** Caller owns the connection; future writes must recheck within their transaction. */
    public void require(Connection connection, Session session, String enterpriseId, Access access) {
        if (session == null) throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED, "Trusted session required");
        ToolContract.required(enterpriseId);
        if (access == null) throw ToolContract.invalid("Access is required");
        if (access == Access.MANAGE_RULES) throw denied();
        try (var statement = connection.prepareStatement("""
                SELECT p.permission FROM permissions p
                JOIN users u ON u.id=p.user_id JOIN enterprises e ON e.id=p.enterprise_id
                WHERE p.user_id=? AND p.enterprise_id=?
                """)) {
            statement.setString(1, session.userId());
            statement.setString(2, enterpriseId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw denied();
                String permission = result.getString(1);
                if (!"可查看并修改".equals(permission)
                        && !(access == Access.READ && "仅查看".equals(permission))) throw denied();
            }
        } catch (SQLException e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "Permission lookup failed", e);
        }
    }

    private static DomainException denied() {
        return new DomainException(ErrorCode.PERMISSION_DENIED, "Access denied");
    }
}
